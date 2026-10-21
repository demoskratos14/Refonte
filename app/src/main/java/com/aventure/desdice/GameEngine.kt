// GameEngine.kt
// Moteur de jeu : point d'entrée unique de la logique, appelé par GameViewModel.
//
// Il garde l'état courant (histoire choisie, session) et couvre directement :
//   - navigation entre histoires : listStories/switchStory/selectStory/
//     deleteStory/createStory
//   - lancers de dés : roll/undo/clear
//   - contraintes de tirage et symboles : setPipSymbol/setPipMode/
//     toggleEnabledSymbol/toggleAllowedValue/resetAllowedValues/
//     toggleAllowedFate/resetAllowedFate
//   - totems ajoutés en cours de partie (avec image, via ImageUtils) :
//     addCustomTotem/removeCustomTotem, énergie totémique : useTotemEnergy
//   - journal : addStoryEntry, completeSideQuest
//   - import d'une progression : applyImportedProgress
//
// Le reste est délégué à des classes du même package, sans changer l'API publique
// de GameEngine (les méthodes publiques gardent leur nom et leur signature) :
//   - SessionJson.kt      : état de session envoyé à Compose (sessionToJson)
//   - PromptBuilder.kt    : textes envoyés à l'IA (mécaniques, rappels, prompt complet)
//   - AiNarrator.kt       : appels à Mistral (runAiNarrator) et digest du journal
//   - MistralConfig.kt    : clé API (chiffrée) et modèle Mistral
//   - StoryTransfer.kt    : exports (journal, identité, progression) et analyse d'un import
//   - ClassicDiceEngine.kt : dés classiques (page indépendante des histoires)
//
// NB : TOTEMS/ALLY_HELP_TEXT (dérivés de STORIES, les histoires codées en dur)
// sont toujours vides dans l'appli actuelle — voir StoryRegistry.kt. Le code
// ci-dessous n'a donc pas besoin de ces deux globales (voir la particularité
// de useTotemEnergy : le libellé "patte"/"baguette" retombe sur la clé
// elle-même, puisqu'aucun totem codé en dur n'existe).

package com.aventure.desdice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * @param secretCipher chiffre la clé API Mistral dans app_config.json (KeystoreSecretCipher en
 *   production ; SecretCipher.None par défaut, pour les tests unitaires).
 */
class GameEngine(
    private val baseDir: File,
    private val secretCipher: SecretCipher = SecretCipher.None
) {

    private val storyRegistry = StoryRegistry(baseDir)

    var currentStorySlug: String? = null
        private set
    private var currentStory: StoryEntry? = null

    var session: DiceSession? = null
        private set

    // Note de transition après un changement de palier en cours d'histoire (voir setPlayerAge) :
    // transmise une seule fois à l'IA, au prochain échange réussi. Volontairement pas sauvegardée.
    private var pendingFrameNote: String? = null

    /**
     * Dernier envoi à l'IA qui a échoué, pour le bouton « Réessayer » (retryAiRequest). Seul ce qui
     * ne se retrouve pas dans la partie est gardé : le texte libre du joueur, la quête à terminer
     * quand l'envoi aboutira, ou le démarrage de l'histoire. Le lancer de dé, lui, reste « en
     * attente » dans la session et repart de lui-même avec le réessai. Non sauvegardé : après un
     * redémarrage de l'appli, il ne reste que le lancer en attente.
     */
    private data class FailedAiRequest(
        val text: String = "",
        val questId: Int = 0,
        val kickoff: Boolean = false
    )
    private var failedAiRequest: FailedAiRequest? = null

    /** Session "vide" utilisée avant toute sélection d'histoire (voir switchStory). */
    private fun emptySession(): DiceSession = DiceSession(File(baseDir, "dice_state.json"))

    // ------------------------------------------------------------------
    // État envoyé à Compose (construit dans SessionJson.kt)
    // ------------------------------------------------------------------

    /** Fiche de chaque totem/symbole, historique, quêtes, journal... : voir SessionJson.kt. */
    fun sessionToJson(sess: DiceSession): JSONObject = SessionJson.build(sess, currentStory)

    /** État courant, ou session vide si rien n'est sélectionné. */
    fun currentSessionJson(): JSONObject = sessionToJson(session ?: emptySession())

    // ------------------------------------------------------------------
    // Navigation entre histoires
    // ------------------------------------------------------------------

    fun listStories(): JSONObject {
        val all = storyRegistry.allStories()
        val result = JSONArray()
        for (slug in storyRegistry.allStoryOrder()) {
            val story = all[slug] ?: continue
            result.put(
                JSONObject().apply {
                    put("slug", slug)
                    put("title", story.title)
                    put("subtitle", story.subtitle)
                    put("bg_image_b64", story.bgImageB64)
                    put("is_custom", story.isCustom)
                }
            )
        }
        return JSONObject().apply {
            put("stories", result)
            put("current_story", currentStorySlug ?: "")
        }
    }

    /**
     * Bascule PIP_SYMBOLS sur l'histoire
     * choisie, ouvre (ou crée) son fichier de sauvegarde dédié, et pose le
     * totem de départ au tout premier lancement.
     */
    fun switchStory(slug: String): Boolean {
        val story = storyRegistry.allStories()[slug] ?: return false

        PIP_SYMBOLS.clear()
        PIP_SYMBOLS.putAll(story.pipSymbols)
        // DEFAULT_PIP_SYMBOL reste "" : aucune histoire n'est codée en dur
        // (StoryEntry.defaultPipSymbol vaut toujours "" pour une histoire
        // personnalisée) — rien à rebasculer ici.

        val saveFile = File(baseDir, story.saveFile)
        val newSession = DiceSession(saveFile)
        val wasLoaded = newSession.load()
        newSession.setStoryLength(story.storyLength)
        newSession.applyThreatThreshold(PlayerAge.threatThreshold(story.playerAge))

        val defaultTotem = story.defaultTotem
        if (!wasLoaded && defaultTotem != null && defaultTotem.label.isNotEmpty()) {
            val key = newSession.addCustomTotem(
                defaultTotem.label,
                powersText = defaultTotem.powersText,
                special = defaultTotem.special,
                imageFilename = defaultTotem.imageFilename
            )
            if (key != null) newSession.setPipSymbol(key)
        }

        currentStorySlug = slug
        currentStory = story
        session = newSession
        pendingFrameNote = null
        failedAiRequest = null
        return true
    }

    fun selectStory(slug: String): JSONObject {
        if (slug in storyRegistry.allStories()) switchStory(slug)
        return currentSessionJson()
    }

    /** Déplace une histoire dans la liste (offset -1 = vers le début, +1 = vers la fin). */
    fun moveStory(slug: String, offset: Int): Boolean =
        storyRegistry.moveCustomStory(slug, offset)

    fun deleteStory(slug: String): JSONObject {
        storyRegistry.deleteCustomStory(slug)
        if (currentStorySlug == slug) {
            currentStorySlug = null
            currentStory = null
            session = null
        }
        return currentSessionJson()
    }

    /**
     * Redimensionne (ImageUtils.resizeTotemBytes) et enregistre une image
     * de totem sous un nom généré.
     */
    private fun saveTotemImageBytes(bytes: ByteArray, originalFilename: String): String? {
        if (bytes.isEmpty()) return null
        var ext = originalFilename.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("png", "jpg", "jpeg", "gif", "webp")) ext = "png"
        val (resizedBytes, resizedExt) = ImageUtils.resizeTotemBytes(bytes)
        if (resizedExt != null) ext = resizedExt
        val dir = File(baseDir, "totem_images")
        dir.mkdirs()
        val generatedFilename = "${UUID.randomUUID().toString().replace("-", "")}.$ext"
        File(dir, generatedFilename).writeBytes(resizedBytes)
        return generatedFilename
    }

    private fun totemImageToBase64(filename: String?): String {
        if (filename.isNullOrEmpty()) return ""
        val file = File(File(baseDir, "totem_images"), filename)
        if (!file.exists()) return ""
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Crée une histoire personnalisée. bgImageExt est ignoré :
     * l'image de fond est toujours réencodée en JPEG par
     * StoryRegistry.createCustomStory().
     */
    fun createStory(
        title: String,
        subtitle: String,
        loreText: String,
        totemLabel: String,
        totemPowers: String,
        totemSpecial: String,
        bgImageBytes: ByteArray,
        totemImageBytes: ByteArray,
        totemImageFilename: String,
        protagonistNames: List<String> = emptyList(),
        storyLength: String = "long",
        moralGoal: String = "",
        playerAge: Int = PlayerAge.UNKNOWN
    ): JSONObject {
        val savedTotemFilename = if (totemImageBytes.isNotEmpty()) {
            saveTotemImageBytes(totemImageBytes, totemImageFilename)
        } else {
            null
        }
        val slug = storyRegistry.createCustomStory(
            title = title,
            subtitle = subtitle,
            loreText = loreText,
            bgImageBytes = bgImageBytes,
            totemLabel = totemLabel,
            totemImageFilename = savedTotemFilename,
            totemPowers = totemPowers,
            totemSpecial = totemSpecial,
            protagonistNames = protagonistNames,
            storyLength = storyLength,
            moralGoal = moralGoal,
            playerAge = playerAge
        )
        switchStory(slug)
        return currentSessionJson()
    }

    // ------------------------------------------------------------------
    // Lancers de dés
    // ------------------------------------------------------------------

    fun roll(action: String): JSONObject {
        val sess = session ?: return currentSessionJson()
        when (action) {
            "success" -> sess.rollSuccess("")
            "fate" -> sess.rollFate("")
            "both" -> sess.rollBoth("")
        }
        return sessionToJson(sess)
    }

    fun undo(): JSONObject {
        session?.undoLast()
        return currentSessionJson()
    }

    fun clear(): JSONObject {
        session?.clearHistory()
        return currentSessionJson()
    }

    // ------------------------------------------------------------------
    // Pip / symboles / contraintes de tirage
    // ------------------------------------------------------------------

    fun setPipSymbol(symbol: String): JSONObject {
        session?.setPipSymbol(symbol)
        return currentSessionJson()
    }

    fun setPipMode(mode: String): JSONObject {
        session?.setPipMode(mode)
        return currentSessionJson()
    }

    fun toggleEnabledSymbol(symbol: String): JSONObject {
        session?.toggleEnabledSymbol(symbol)
        return currentSessionJson()
    }

    fun toggleAllowedValue(value: Int): JSONObject {
        session?.toggleAllowedValue(value)
        return currentSessionJson()
    }

    fun resetAllowedValues(): JSONObject {
        session?.resetAllowedValues()
        return currentSessionJson()
    }

    fun toggleAllowedFate(key: String): JSONObject {
        session?.toggleAllowedFate(key)
        return currentSessionJson()
    }

    fun resetAllowedFate(): JSONObject {
        session?.resetAllowedFate()
        return currentSessionJson()
    }

    // ------------------------------------------------------------------
    // Totems ajoutés en cours de partie (avec image) / quêtes / journal
    // ------------------------------------------------------------------

    /** Ajoute un totem à la partie en cours. imageBytes peut être vide (pas d'image fournie). */
    fun addCustomTotem(
        label: String,
        powers: String = "",
        special: String = "",
        emoji: String = "",
        imageBytes: ByteArray = ByteArray(0),
        imageFilename: String = ""
    ): JSONObject {
        val sess = session ?: return currentSessionJson()
        val savedFilename = if (imageBytes.isNotEmpty()) saveTotemImageBytes(imageBytes, imageFilename) else null
        sess.addCustomTotem(label, powersText = powers, special = special, emoji = emoji, imageFilename = savedFilename)
        return sessionToJson(sess)
    }

    /** Retire un totem : supprime aussi le fichier image associé, s'il existe. */
    fun removeCustomTotem(key: String): JSONObject {
        val sess = session ?: return currentSessionJson()
        val entry = sess.customTotems.find { it.key == key }
        sess.removeCustomTotem(key)
        val image = entry?.image
        if (!image.isNullOrEmpty()) {
            val file = File(File(baseDir, "totem_images"), image)
            if (file.exists()) file.delete()
        }
        return sessionToJson(sess)
    }

    /** Modifie les pouvoirs / la capacité spéciale d'un totem existant (voir DiceSession.updateCustomTotem). */
    fun updateCustomTotem(key: String, powers: List<String>, special: String): JSONObject {
        val sess = session ?: return currentSessionJson()
        sess.updateCustomTotem(key, powers, special)
        return sessionToJson(sess)
    }

    fun completeSideQuest(questId: Int): JSONObject {
        session?.completeSideQuest(questId)
        return currentSessionJson()
    }

    fun addStoryEntry(text: String): JSONObject {
        session?.addStoryEntry(text)
        return currentSessionJson()
    }

    // ------------------------------------------------------------------
    // Narration IA (digest du journal, appel à l'IA) ; les textes envoyés à l'IA
    // (contexte de mécaniques, prompt complet, message de démarrage) sont construits
    // dans PromptBuilder.kt
    // ------------------------------------------------------------------

    // Appels à l'IA : logique dans AiNarrator.kt ; ici, uniquement l'état courant (session,
    // histoire, slug) transmis à chaque appel. Le champ `narrator` est déclaré plus bas,
    // juste après `mistralConfig` dont il dépend (l'ordre des champs compte à l'initialisation).

    /** @return (texte de la réponse, erreur) — comme MistralClient.chat(). */
    private fun runAiNarrator(
        eventText: String,
        visible: Boolean = true,
        displayText: String = ""
    ): Pair<String?, String?> {
        val sess = session ?: return null to null
        val note = pendingFrameNote
        val result = narrator.runAiNarrator(sess, currentStory, currentStorySlug, eventText, visible, displayText, note)
        // La note n'est consommée que si l'échange a abouti : sinon elle repart au prochain essai.
        if (note != null && result.second == null) pendingFrameNote = null
        return result
    }

    /**
     * Change le palier de contenu (via l'âge du plus jeune joueur, voir AgeTier.kt) de l'histoire
     * en cours, y compris au milieu d'une partie. L'âge est enregistré dans l'histoire, puis le
     * cadre déjà écrit dans la conversation avec l'IA est remplacé par le nouveau : sans cela,
     * le message "system" figé au premier échange continuerait d'imposer l'ancien palier.
     * Les réponses déjà écrites ne sont pas modifiées ; une note ponctuelle demande à l'IA
     * d'appliquer le nouveau ton dès sa prochaine réponse.
     */
    fun setPlayerAge(age: Int): JSONObject {
        val slug = currentStorySlug ?: return currentSessionJson()
        val story = currentStory ?: return currentSessionJson()
        val oldAge = story.playerAge
        val newAge = PlayerAge.normalize(age)
        if (newAge == oldAge) return currentSessionJson()
        if (!storyRegistry.updatePlayerAge(slug, newAge)) return currentSessionJson()
        val newStory = story.copy(playerAge = newAge)
        currentStory = newStory
        session?.applyThreatThreshold(PlayerAge.threatThreshold(newAge))

        val sess = session
        if (sess != null && sess.aiConversation.isNotEmpty()) {
            val newFrame = PromptBuilder.contentFrameText(newAge)
            sess.replaceInConversation(PromptBuilder.contentFrameText(oldAge), newFrame)
            // Message "system" écrit par une version plus ancienne de l'appli (texte du cadre
            // différent, donc non reconnu) : il est reconstruit en entier, comme au premier échange.
            val head = sess.aiConversation.firstOrNull()
            if (head != null && head.role == "system" && !head.content.contains(newFrame)) {
                sess.replaceSystemMessage(PromptBuilder.buildMechanicsContext(sess, newStory, autoMode = true))
            }
            if (PlayerAge.tierOf(oldAge) != PlayerAge.tierOf(newAge)) {
                pendingFrameNote = PromptBuilder.buildFrameChangeNote(newAge)
            }
        }
        return currentSessionJson()
    }

    private fun maybeUpdateStoryDigest(
        sess: DiceSession,
        finalChapter: Boolean = false,
        chapterEnd: Boolean = false,
        flushAll: Boolean = false
    ) = narrator.maybeUpdateStoryDigest(sess, currentStorySlug, finalChapter, chapterEnd, flushAll)

    /**
     * Texte du prompt complet (mécaniques +
     * histoire déjà vécue), pour le bouton "copier le prompt complet" du
     * mode manuel (sans clé Mistral). Chaîne vide si aucune histoire n'est
     * sélectionnée.
     */
    fun buildFullPromptText(): String {
        val sess = session ?: return ""
        val prompt = PromptBuilder.buildFullPrompt(sess, currentStory)
        // Quête courte lancée par la fin de chapitre (histoire longue) : consigne jointe une seule fois.
        val announcement = sess.consumeQuestAnnouncement() ?: return prompt
        return "$prompt\n\n$announcement"
    }

    /** Envoie le prompt complet à l'IA. */
    fun sendFullPrompt(): JSONObject {
        val sess = session ?: return currentSessionJson()
        // Prompt de mécaniques + instructions à l'IA : jamais affiché tel quel dans AiPanel.kt,
        // seule sa réponse (le début du chapitre) doit apparaître au joueur.
        val (_, aiError) = runAiNarrator(PromptBuilder.buildAiKickoffMessage(sess, currentStory), visible = false)
        failedAiRequest = if (aiError != null) FailedAiRequest(kickoff = true) else null
        return sessionToJson(sess).apply { if (aiError != null) put("ai_error", aiError) }
    }

    /**
     * Texte du lancer en attente pour l'IA, précédé d'un rappel si un lancer précédent resté sans
     * réponse avait fait exploser la jauge de menace (sinon cette complication serait perdue).
     */
    private fun pendingRollParts(sess: DiceSession, pending: RollRecord): List<String> {
        val parts = mutableListOf<String>()
        if (sess.missedThreatEvents() > 0) parts += PromptBuilder.buildMissedThreatNote()
        parts += PromptBuilder.buildAiEventText(sess, pending)
        return parts
    }

    /**
     * Envoie le lancer en attente (s'il y en a un) + un texte libre optionnel.
     * Le lancer n'est marqué « transmis » que si l'IA l'a bien reçu : après une erreur il reste en
     * attente, et le réessai (retryAiRequest) le renvoie sans qu'il faille relancer le dé.
     * @param questId quête secondaire à terminer quand cet envoi aboutira lors d'un réessai
     *   (0 = aucune) ; en temps normal, c'est l'interface qui la termine après un envoi réussi.
     */
    fun sendAiMessage(text: String = "", questId: Int = 0): JSONObject {
        val sess = session ?: return currentSessionJson()
        val pending = sess.pendingRoll()
        val parts = mutableListOf<String>()
        if (pending != null) parts += pendingRollParts(sess, pending)
        val trimmedText = text.trim()
        if (trimmedText.isNotEmpty()) parts += trimmedText
        val combined = parts.joinToString("\n")
        var aiError: String? = null
        if (combined.isNotEmpty()) {
            // La description technique du lancer (dé, quête, note de combo...) ne doit jamais
            // s'afficher comme si le joueur l'avait tapée. Si le joueur a AUSSI écrit un texte
            // libre, seul CE texte est montré ; sans lui, le message reste entièrement masqué.
            val (_, err) = runAiNarrator(combined, visible = trimmedText.isNotEmpty(), displayText = trimmedText)
            aiError = err
            if (pending != null && aiError == null) sess.markLastRollAsSent()
        }
        failedAiRequest = if (aiError != null) FailedAiRequest(text = trimmedText, questId = questId) else null
        return sessionToJson(sess).apply { if (aiError != null) put("ai_error", aiError) }
    }

    /**
     * Relance le dernier envoi à l'IA qui a échoué (bouton « Réessayer ») : démarrage de l'histoire,
     * ou message du joueur avec le lancer resté en attente. Si l'appli a été redémarrée entre-temps
     * (la requête n'est pas sauvegardée), on repart de l'état de la partie : démarrage si
     * l'histoire n'a pas encore commencé, sinon renvoi du lancer en attente.
     */
    fun retryAiRequest(): JSONObject {
        val sess = session ?: return currentSessionJson()
        val failed = failedAiRequest
        val kickoff = failed?.kickoff ?: sess.aiConversation.none { it.role != "system" }
        if (kickoff) return sendFullPrompt()
        val questId = failed?.questId ?: 0
        val result = sendAiMessage(failed?.text.orEmpty(), questId)
        // « Commencer » une quête secondaire avait échoué : la demande est partie, la quête est donc terminée.
        if (questId != 0 && !result.has("ai_error")) return completeSideQuest(questId)
        return result
    }

    /** Réinitialise TOUTE la partie (reset_story_to_origin), pas seulement la conversation IA. */
    fun resetAiConversation(): JSONObject {
        resetStoryToOrigin()
        return currentSessionJson()
    }

    private fun resetStoryToOrigin() {
        val story = currentStory ?: return
        val saveFile = File(baseDir, story.saveFile)
        SafeFile.deleteAll(saveFile) // aussi la copie .bak : sinon l'ancienne partie pourrait revenir
        story.seedStateFile?.let { seedPath ->
            val seedFile = File(baseDir, seedPath)
            if (seedFile.exists()) seedFile.copyTo(saveFile, overwrite = true)
        }
        val newSession = DiceSession(saveFile)
        val wasLoaded = newSession.load()
        newSession.setStoryLength(story.storyLength)
        newSession.applyThreatThreshold(PlayerAge.threatThreshold(story.playerAge))
        val defaultTotem = story.defaultTotem
        if (!wasLoaded && defaultTotem != null && defaultTotem.label.isNotEmpty()) {
            val key = newSession.addCustomTotem(
                defaultTotem.label,
                powersText = defaultTotem.powersText,
                special = defaultTotem.special,
                imageFilename = defaultTotem.imageFilename
            )
            if (key != null) newSession.setPipSymbol(key)
        }
        newSession.save()
        session = newSession
        pendingFrameNote = null
        failedAiRequest = null
    }

    // ------------------------------------------------------------------
    // Configuration Mistral : logique dans MistralConfig.kt (app_config.json)
    // ------------------------------------------------------------------

    private val mistralConfig = MistralConfig(baseDir, secretCipher)

    private val narrator = AiNarrator(mistralConfig)

    fun getMistralKey(): String = mistralConfig.getMistralKey()

    fun setMistralKey(key: String) = mistralConfig.setMistralKey(key)

    fun clearMistralKey() = mistralConfig.clearMistralKey()

    fun hasMistralKey(): Boolean = mistralConfig.hasMistralKey()

    fun getMistralModel(): String = mistralConfig.getMistralModel()

    fun setMistralModel(model: String) = mistralConfig.setMistralModel(model)

    fun getConfigScreenState(): JSONObject = mistralConfig.getConfigScreenState()

    // ------------------------------------------------------------------
    // Export / import (journal, identité, progression) : formats et analyse dans
    // StoryTransfer.kt ; ici, uniquement l'état courant et le résumé IA avant export.
    // ------------------------------------------------------------------

    private val transfer = StoryTransfer(storyRegistry, this::totemImageToBase64)

    fun exportJournal(): Triple<ByteArray, String, String> =
        transfer.exportJournal(session, currentStory, currentStorySlug)

    /**
     * Exporte l'identité (histoire personnalisée uniquement). Avant d'exporter : les derniers
     * échanges pas encore résumés le sont maintenant, sinon ils seraient perdus à la
     * réinstallation (seuls le résumé et le journal sont exportés). Sans clé Mistral ou en cas
     * d'échec réseau, on exporte simplement l'état actuel.
     */
    fun exportIdentity(): Triple<ByteArray, String, String> {
        val story = currentStory
        val slug = currentStorySlug
        if (story == null || !story.isCustom || slug == null) return Triple(ByteArray(0), "", "")
        val sess = session
        if (sess != null) {
            maybeUpdateStoryDigest(sess, flushAll = true)
            sess.save()
        }
        return transfer.exportIdentity(slug, sess)
    }

    /**
     * Sauvegarde légère de la progression (marche pour TOUTES les histoires). Les derniers
     * échanges pas encore résumés le sont d'abord (sans clé Mistral ou en cas d'échec réseau,
     * on exporte simplement l'état actuel).
     */
    fun exportProgress(): Triple<ByteArray, String, String> {
        val sess = session ?: return Triple(ByteArray(0), "", "")
        maybeUpdateStoryDigest(sess, flushAll = true)
        sess.save()
        return transfer.exportProgress(sess, currentStory, currentStorySlug)
    }

    /** Ne fait qu'analyser/valider le JSON importé, sans rien appliquer. */
    fun importIdentity(rawText: String): JSONObject = transfer.importIdentity(rawText)

    /** Restaure quêtes/journal sur la session en cours. */
    fun applyImportedProgress(sideQuestsJson: JSONArray, nextQuestId: Int, storyLogJson: JSONArray, storySummary: String): JSONObject {
        val sess = session ?: return currentSessionJson()
        sess.importProgress(sideQuests = sideQuestsJson, nextQuestId = nextQuestId, storyLog = storyLogJson, storySummary = storySummary)
        return sessionToJson(sess)
    }

    // ------------------------------------------------------------------
    // Énergie totémique
    // ------------------------------------------------------------------

    /** Dépense l'énergie d'un totem, narration IA comprise. */
    fun useTotemEnergy(key: String, text: String = ""): JSONObject {
        val sess = session ?: return currentSessionJson().apply {
            put("spent", false)
            put("effect", "")
        }
        var effectText = ""
        val spent = sess.spendTotemEnergy(key)
        if (spent) {
            if (key == "patte" || key == "baguette") {
                // TOTEMS_BY_KEY (histoires codées en dur) est toujours vide
                // dans l'appli actuelle : le libellé retombe donc sur la
                // clé elle-même plutôt que sur le totem personnalisé homonyme.
                val label = key
                sess.secondSouffle(note = "Relance ($label)")
                effectText = "\uD83D\uDD04 $label ! Le dernier lancer de réussite est annulé et relancé à l'instant."
            } else {
                val info = sess.allSymbols()[key]
                effectText = when {
                    info == null -> ""
                    info.special.isNotEmpty() -> info.special
                    info.powers.isNotEmpty() -> "Le pouvoir de ${info.label} se manifeste : ${info.powers.joinToString(", ")}."
                    else -> "${info.label} intervient pour aider !"
                }
            }
        }
        var aiError: String? = null
        if (spent && effectText.isNotEmpty()) {
            val pending = sess.pendingRoll()
            val parts = mutableListOf<String>()
            if (pending != null) parts += pendingRollParts(sess, pending)
            if (text.isNotEmpty()) parts += text
            parts += effectText
            val (_, err) = runAiNarrator(parts.joinToString("\n"))
            aiError = err
            if (pending != null && aiError == null) sess.markLastRollAsSent()
            // L'énergie du totem est déjà dépensée : le réessai renvoie son effet à l'IA sans la dépenser de nouveau.
            failedAiRequest = if (aiError != null) {
                FailedAiRequest(text = listOf(text, effectText).filter { it.isNotEmpty() }.joinToString("\n"))
            } else null
        }
        return sessionToJson(sess).apply {
            put("spent", spent)
            put("effect", effectText)
            if (aiError != null) put("ai_error", aiError)
        }
    }

    // ------------------------------------------------------------------
    // Dés classiques : logique dans ClassicDiceEngine.kt (page indépendante)
    // ------------------------------------------------------------------

    private val classicDice = ClassicDiceEngine(baseDir)

    fun classicDiceState(): JSONObject = classicDice.classicDiceState()

    fun classicDiceRollMany(dice: List<Int>): JSONObject = classicDice.classicDiceRollMany(dice)

    /** Ancienne API (kind = "success" / "fate" / "both"). */
    fun classicDiceRoll(kind: String, faces: Int = 6): JSONObject = classicDice.classicDiceRoll(kind, faces)

    fun classicDiceClear(): JSONObject = classicDice.classicDiceClear()
}
