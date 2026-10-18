// GameEngine.kt
// Moteur de jeu : point d'entrée unique de la logique, appelé par GameViewModel.
//
// Il couvre :
//   - construction de l'état de session envoyé à Compose (sessionToJson,
//     y compris les infos de totem)
//   - navigation entre histoires : listStories/switchStory/selectStory/
//     deleteStory/createStory
//   - lancers de dés : roll/undo/clear
//   - contraintes de tirage et symboles : setPipSymbol/setPipMode/
//     toggleEnabledSymbol/toggleAllowedValue/resetAllowedValues/
//     toggleAllowedFate/resetAllowedFate
//   - totems ajoutés en cours de partie (avec image, via ImageUtils) :
//     addCustomTotem/removeCustomTotem, énergie totémique : useTotemEnergy
//   - journal : addStoryEntry, completeSideQuest
//   - narration IA (via MistralClient) : maybeUpdateStoryDigest, runAiNarrator,
//     sendAiMessage/sendFullPrompt/resetAiConversation ; les textes envoyés à
//     l'IA (buildMechanicsContext/buildFullPrompt/buildAiKickoffMessage...)
//     sont construits dans PromptBuilder.kt
//   - configuration Mistral : getConfigScreenState, get/setMistralKey,
//     get/setMistralModel (simple délégation à MistralConfig.kt)
//   - export/import : exportJournal (via JournalExporter), exportIdentity/
//     importIdentity/applyImportedProgress
//   - dés classiques : classicDiceState/classicDiceRoll/classicDiceClear
//     (simple délégation à ClassicDiceEngine.kt)
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

    /** Session "vide" utilisée avant toute sélection d'histoire (voir switchStory). */
    private fun emptySession(): DiceSession = DiceSession(File(baseDir, "dice_state.json"))

    // ------------------------------------------------------------------
    // Construction de l'état envoyé à Compose (sessionToJson)
    // ------------------------------------------------------------------

    /**
     * Fiche de chaque totem/symbole cliquable : totems ajoutés par le
     * joueur + symboles "relance" (patte/baguette) sans fiche dédiée.
     * TOTEMS et ALLY_HELP_TEXT (histoires codées en dur) sont toujours
     * vides ici — voir le commentaire en tête de fichier.
     */
    private fun totemInfoJson(sess: DiceSession): JSONObject {
        val info = JSONObject()
        for (t in sess.customTotems) {
            info.put(
                t.key,
                JSONObject().apply {
                    put("icon", t.emoji)
                    put("label", t.label.ifEmpty { t.key })
                    put("powers", JSONArray(t.powers))
                    put("special", t.special)
                }
            )
        }
        for ((key, sym) in sess.allSymbols()) {
            if (info.has(key)) continue
            if (key != "patte" && key != "baguette") continue
            info.put(
                key,
                JSONObject().apply {
                    put("icon", sym.emoji)
                    put("label", sym.label.ifEmpty { key })
                    put("powers", JSONArray())
                    put("special", "\uD83D\uDD04 Second Souffle : relance immédiatement le dernier lancer de réussite.")
                    put("badge", false)
                }
            )
        }
        return info
    }

    private fun symbolInfoJson(key: String, sym: SymbolInfo): JSONObject = JSONObject().apply {
        put("key", key)
        put("emoji", sym.emoji)
        put("label", sym.label)
        put("image", sym.image ?: JSONObject.NULL)
        put("powers", JSONArray(sym.powers))
        put("special", sym.special)
        put("is_custom", sym.isCustom)
    }

    fun sessionToJson(sess: DiceSession): JSONObject {
        val lastRecord = sess.history.lastOrNull()
        val lastResult = lastRecord?.toJson()?.apply {
            put("description", sess.describeRecord(lastRecord))
        }
        val totemEnergyJson = JSONObject()
        for ((k, v) in sess.totemEnergy) totemEnergyJson.put(k, v)

        return JSONObject().apply {
            put("history", JSONArray(sess.history.map { it.toJson() }))
            put("next_id", sess.nextId)
            put("pip_symbol", sess.pipSymbol)
            put("pip_mode", sess.pipMode)
            put("enabled_symbols", JSONArray(sess.enabledSymbols))
            put("custom_totems", JSONArray(sess.customTotems.map { it.toJson() }))
            put("allowed_success_values", JSONArray(sess.allowedSuccessValues))
            put("allowed_fate_keys", JSONArray(sess.allowedFateKeys))
            put("totem_energy", totemEnergyJson)
            put("threat_level", sess.threatLevel)
            put("side_quests", JSONArray(sess.sideQuests.map { it.toJson() }))
            put("next_quest_id", sess.nextQuestId)
            put("story_log", JSONArray(sess.storyLog))
            put("ai_conversation", JSONArray(sess.aiConversation.map { it.toJson() }))
            put("story_summary", sess.storySummary)
            put("last_journal_index", sess.lastJournalIndex)
            put("last_ai_sent_id", sess.lastAiSentId)
            put("all_symbols", JSONArray(sess.allSymbols().map { (k, v) -> symbolInfoJson(k, v) }))
            put("story_title", currentStory?.title ?: "")
            put(
                "header_title",
                currentStory?.headerTitle?.ifEmpty { "Les Dés de l'Aventure" } ?: "Les Dés de l'Aventure"
            )
            put("totem_info", totemInfoJson(sess))
            put("last_result", lastResult ?: JSONObject.NULL)
        }
    }

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
        moralGoal: String = ""
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
            moralGoal = moralGoal
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

    // Balises que l'IA ajoute à ses réponses : définies avec les consignes qui les demandent
    // (PromptBuilder.kt), retirées des réponses plus bas par runAiNarrator().
    private val questLaunchedTag = PromptBuilder.questLaunchedTag
    private val storyEndedTag = PromptBuilder.storyEndedTag
    private val chapterEndedTag = PromptBuilder.chapterEndedTag

    /**
     * Envoie eventText à l'IA (en amorçant la conversation avec le message
     * système si elle est vide), ajoute la réponse à la conversation, et
     * tente une mise à jour du digest journal/résumé..
     *
     * Le message "system" initial (mécaniques + FORMAT DE L'HISTOIRE) n'est
     * écrit qu'une fois, puis reste figé tel quel dans sess.aiConversation
     * pour le reste de la partie (voir plus haut) -- un compte à rebours
     * qui y serait inclus deviendrait donc faux dès l'échange suivant. Le
     * rappel de progression (buildStoryProgressNote) est pour cette raison
     * recalculé et ajouté à part ICI, à CHAQUE appel, sur la copie de
     * messages effectivement envoyée à l'API -- jamais sur sess.aiConversation
     * elle-même, donc jamais sauvegardé ni affiché au joueur (AiPanel.kt
     * n'affiche que ce qui est dans aiConversation).
     *
     * @param visible false : le message "user" envoyé (lancer de dés, prompt de mécaniques...)
     *   ne sera jamais montré dans AiPanel.kt (mais reste envoyé à l'IA et sauvegardé).
     * @param displayText si `visible` est vrai et ce paramètre non vide : c'est lui qui est
     *   affiché au joueur à la place du texte technique complet (ex. son propre message libre).
     * @return (texte de la réponse, erreur) — comme MistralClient.chat().
     */
    private fun runAiNarrator(
        eventText: String,
        visible: Boolean = true,
        displayText: String = ""
    ): Pair<String?, String?> {
        val sess = session ?: return null to null
        if (!hasMistralKey() || eventText.isEmpty()) return null to null
        if (sess.aiConversation.isEmpty()) {
            sess.addAiMessage("system", PromptBuilder.buildMechanicsContext(sess, currentStory, autoMode = true), visible = false)
        }
        // Quête lancée par l'ajout manuel d'un chapitre (addStoryEntry) : consigne transmise une seule fois.
        val announcement = sess.consumeQuestAnnouncement()
        val fullText = if (announcement != null) "$eventText\n$announcement" else eventText
        sess.addAiMessage("user", fullText, visible = visible, displayContent = displayText)
        val messagesToSend = sess.aiMessagesToSend().toMutableList()
        PromptBuilder.buildStoryProgressNote(sess, currentStory)?.let { note ->
            // Juste avant le dernier message (le "user" qu'on vient
            // d'ajouter), pour rester proche de la convention "la
            // conversation envoyée se termine par un message user".
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        messagesToSend.add(
            (messagesToSend.size - 1).coerceAtLeast(0),
            AiMessage("system", PromptBuilder.buildGaugeStateNote(sess))
        )
        PromptBuilder.buildPendingQuestNote(sess, currentStory)?.let { note ->
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        PromptBuilder.buildChapterEndNote(currentStory)?.let { note ->
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        val (text, error) = MistralClient.chat(
            getMistralKey(),
            messagesToSend,
            model = getMistralModel(),
            promptCacheKey = currentStorySlug
        )
        if (error != null) return null to error
        var replyText = text ?: ""
        // Fin d'un chapitre (pas de l'histoire entière) : le journal est digéré tout de suite,
        // même s'il reste moins de 6 échanges depuis le dernier résumé.
        var chapterEnded = false
        if (replyText.contains(chapterEndedTag)) {
            replyText = replyText.replace(chapterEndedTag, "").trim()
            chapterEnded = true
        }
        if (replyText.contains(questLaunchedTag)) {
            // L'IA a conclu le chapitre et lancé la quête courte : on retire la balise et on ouvre la quête.
            // C'est aussi une fin de chapitre, même si elle a oublié la balise dédiée.
            replyText = replyText.replace(questLaunchedTag, "").trim()
            chapterEnded = true
            if (sess.launchPendingQuest(announce = false)) sess.save()
        }
        // Fin définitive de l'histoire (pas juste un chapitre) : le journal ne doit pas rester
        // en attente d'un 6e échange qui ne viendra jamais -- le reliquat, même court, est
        // digéré tout de suite pour ne rien perdre du dernier chapitre.
        var storyEnded = false
        if (replyText.contains(storyEndedTag)) {
            replyText = replyText.replace(storyEndedTag, "").trim()
            storyEnded = true
        }
        sess.addAiMessage("assistant", replyText)
        maybeUpdateStoryDigest(sess, finalChapter = storyEnded, chapterEnd = chapterEnded)
        return replyText to null
    }

    private val digestChapterTag = "###CHAPITRE###"
    private val digestSummaryTag = "###RESUME###"

    /**
     * Met à jour le digest de l'histoire — appel "fantôme" : n'écrit jamais dans aiConversation.
     * @param finalChapter true quand l'IA vient de signaler la fin définitive de l'histoire
     *   ($storyEndedTag) : le reliquat d'échanges pas encore digéré (moins de 6, éventuellement)
     *   est alors digéré quand même, comme dernier chapitre, au lieu d'attendre un 6e échange
     *   qui n'arrivera jamais.
     * @param chapterEnd true quand l'IA vient de conclure un chapitre ($chapterEndedTag) : même
     *   principe, tout le reliquat est digéré tout de suite pour que la fin du chapitre ne reste
     *   pas coincée dans la tranche suivante (mélangée au début du chapitre d'après).
     */
    private fun maybeUpdateStoryDigest(
        sess: DiceSession,
        finalChapter: Boolean = false,
        chapterEnd: Boolean = false,
        flushAll: Boolean = false
    ) {
        val digestTrigger = 6
        // Chapitre (~130 mots) + résumé long terme avec fiches personnages (~350 mots).
        val digestMaxTokens = 1200
        if (!hasMistralKey()) return
        // flushAll : digère tout le reliquat sans prétendre que l'extrait clôt un chapitre
        // (utilisé juste avant un export, en plein milieu d'un chapitre).
        val flush = finalChapter || chapterEnd || flushAll
        while (true) {
            val pending = sess.pendingDigestMessages()
            val fullChunk = pending.size >= digestTrigger
            if (!fullChunk && (!flush || pending.isEmpty())) return
            val chunk = pending.take(digestTrigger)
            // Dernière tranche = celle qui vide le reliquat (même si elle fait pile 6 messages).
            val isLastChunk = (finalChapter || chapterEnd) && pending.size <= digestTrigger
            val isFlushEnd = flush && pending.size <= digestTrigger
            val block = chunk.joinToString("\n") { m -> "${if (m.role == "user") "Joueur" else "Narrateur"} : ${m.content}" }
            val existingSummary = sess.storySummary

            val digestSystem = "Tu reçois " +
                (if (isLastChunk && finalChapter) "le tout dernier extrait (celui qui clôt définitivement l'histoire) "
                else if (isLastChunk) "la fin d'un chapitre (l'extrait se termine sur la conclusion du chapitre) "
                else "un extrait récent ") +
                "d'une histoire de jeu de rôle pour enfant (en français) et dois produire DEUX textes " +
                "bien distincts, chacun introduit par sa balise exacte sur sa propre ligne, rien " +
                "d'autre avant/après/entre :\n" +
                "$digestChapterTag\n" +
                "Un chapitre de journal racontant cet extrait : à la troisième personne, fluide et " +
                "narratif (pas une liste de faits, pas de dialogue au style direct), 80 à 130 mots" +
                (if (isLastChunk && finalChapter) ", qui se termine sur la conclusion de l'histoire.\n"
                else if (isLastChunk) ", qui se termine sur la conclusion du chapitre.\n"
                else ".\n") +
                "Présente chaque nouveau personnage important (humain, créature, animal, esprit...) au " +
                "moment où il apparaît pour la première fois : en une courte phrase, dis qui il est, son " +
                "apparence et son rôle, comme pour un lecteur qui le découvre. Un personnage est nouveau " +
                "s'il ne figure pas déjà dans la partie PERSONNAGES du résumé existant ; ne parle jamais " +
                "d'un personnage nouveau comme s'il était déjà connu du lecteur.\n" +
                "$digestSummaryTag\n" +
                "Le résumé long terme mis à jour, en DEUX parties. D'abord la ligne « PERSONNAGES : » " +
                "suivie d'une ligne par personnage (« - Nom : apparence, caractère, rôle, lien avec le " +
                "joueur, signe distinctif »). Puis la ligne « FAITS : » suivie des faits marquants " +
                "(objets/totems, lieux, quêtes, événements à retenir). Fusionne le résumé existant (s'il y " +
                "en a un) et les nouveautés de cet extrait. Ne supprime JAMAIS un personnage déjà listé : " +
                "garde ses traits, ne les modifie que si l'histoire les a réellement changés, et ajoute " +
                "les nouveaux personnages. Si le résumé existant n'a pas de partie PERSONNAGES, crée-la " +
                "avec tous les personnages connus. Style neutre et factuel, sans tournures narratives. " +
                "350 mots maximum : si la place manque, raccourcis les FAITS en priorité, jamais les " +
                "personnages."
            val userPrompt = buildString {
                if (existingSummary.isNotEmpty()) append("Résumé existant :\n$existingSummary\n\n")
                append("Extrait de l'histoire (échanges joueur/narrateur) :\n$block\n\n")
                append("Réponds avec les deux textes demandés, balises $digestChapterTag et $digestSummaryTag incluses.")
            }
            val (text, error) = MistralClient.chat(
                getMistralKey(),
                listOf(AiMessage("system", digestSystem), AiMessage("user", userPrompt)),
                model = getMistralModel(),
                maxTokens = digestMaxTokens,
                promptCacheKey = currentStorySlug?.let { "$it-digest" }
            )
            if (error != null || text.isNullOrEmpty()) return

            val chapterPos = text.indexOf(digestChapterTag)
            val summaryPos = text.indexOf(digestSummaryTag)
            if (chapterPos == -1 || summaryPos == -1 || summaryPos <= chapterPos) return
            val chapter = text.substring(chapterPos + digestChapterTag.length, summaryPos).trim()
            if (chapter.isEmpty()) return
            val updatedSummary = text.substring(summaryPos + digestSummaryTag.length).trim().ifEmpty { existingSummary }
            sess.applyStoryDigest(chapter, updatedSummary, chunk.size)
            if (isFlushEnd) return
        }
    }

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
        return sessionToJson(sess).apply { if (aiError != null) put("ai_error", aiError) }
    }

    /** Envoie le lancer en attente (s'il y en a un) + un texte libre optionnel. */
    fun sendAiMessage(text: String = ""): JSONObject {
        val sess = session ?: return currentSessionJson()
        val pending = sess.pendingRoll()
        val parts = mutableListOf<String>()
        if (pending != null) parts += PromptBuilder.buildAiEventText(sess, pending)
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
            if (pending != null) sess.markLastRollAsSent()
        }
        return sessionToJson(sess).apply { if (aiError != null) put("ai_error", aiError) }
    }

    /** Réinitialise TOUTE la partie (reset_story_to_origin), pas seulement la conversation IA. */
    fun resetAiConversation(): JSONObject {
        resetStoryToOrigin()
        return currentSessionJson()
    }

    private fun resetStoryToOrigin() {
        val story = currentStory ?: return
        val saveFile = File(baseDir, story.saveFile)
        if (saveFile.exists()) saveFile.delete()
        story.seedStateFile?.let { seedPath ->
            val seedFile = File(baseDir, seedPath)
            if (seedFile.exists()) seedFile.copyTo(saveFile, overwrite = true)
        }
        val newSession = DiceSession(saveFile)
        val wasLoaded = newSession.load()
        newSession.setStoryLength(story.storyLength)
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
    }

    // ------------------------------------------------------------------
    // Configuration Mistral : logique dans MistralConfig.kt (app_config.json)
    // ------------------------------------------------------------------

    private val mistralConfig = MistralConfig(baseDir, secretCipher)

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
            if (pending != null) parts += PromptBuilder.buildAiEventText(sess, pending)
            if (text.isNotEmpty()) parts += text
            parts += effectText
            val (_, err) = runAiNarrator(parts.joinToString("\n"))
            aiError = err
            if (pending != null) sess.markLastRollAsSent()
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
