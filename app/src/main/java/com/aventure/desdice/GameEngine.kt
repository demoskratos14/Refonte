// GameEngine.kt
// Portage Kotlin de game_api.py — ÉTAPE 3, partie 2 (complet).
//
// Couvre désormais l'intégralité de game_api.py :
//   - construction de l'état de session envoyé à Compose (sessionToJson,
//     miroir de session_to_dict + _totem_info)
//   - navigation entre histoires : listStories/switchStory/selectStory/
//     deleteStory/createStory
//   - lancers de dés : roll/undo/clear
//   - contraintes de tirage et symboles : setPipSymbol/setPipMode/
//     toggleEnabledSymbol/toggleAllowedValue/resetAllowedValues/
//     toggleAllowedFate/resetAllowedFate
//   - totems ajoutés en cours de partie (avec image, via ImageUtils) :
//     addCustomTotem/removeCustomTotem, énergie totémique : useTotemEnergy
//   - journal : addStoryEntry, completeSideQuest
//   - narration IA (via MistralClient) : buildMechanicsContext/
//     buildFullPrompt/buildAiKickoffMessage, maybeUpdateStoryDigest,
//     runAiNarrator, doSendAiMessage/doSendFullPrompt/doResetAiConversation
//   - configuration Mistral : getConfigScreenState, get/setMistralKey,
//     get/setMistralModel
//   - export/import : exportJournal (via JournalExporter), exportIdentity/
//     importIdentity/applyImportedProgress
//   - dés classiques : classicDiceState/classicDiceRoll/classicDiceClear
//
// NB : TOTEMS/ALLY_HELP_TEXT (dérivés de STORIES, l'histoire codée en dur)
// sont toujours vides dans l'appli actuelle — voir StoryRegistry.kt. Le
// code ci-dessous reflète donc une version simplifiée de _totem_info /
// do_use_totem_energy qui n'a plus besoin de ces deux globales (y compris
// la bizarrerie préservée à l'identique dans useTotemEnergy : le libellé
// "patte"/"baguette" retombe sur la clé elle-même, comme TOTEMS_BY_KEY
// toujours vide le fait côté Python).

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

class GameEngine(private val baseDir: File) {

    private val storyRegistry = StoryRegistry(baseDir)

    var currentStorySlug: String? = null
        private set
    private var currentStory: StoryEntry? = null

    var session: DiceSession? = null
        private set

    /** Session "vide" utilisée avant toute sélection d'histoire (miroir de DiceSession() côté Python, avant switch_story). */
    private fun emptySession(): DiceSession = DiceSession(File(baseDir, "dice_state.json"))

    // ------------------------------------------------------------------
    // Construction de l'état envoyé à Compose (miroir de session_to_dict)
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

    /** Miroir de index()/change_story() : état courant, ou session vide si rien n'est sélectionné. */
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
     * Miroir de switch_story() : bascule PIP_SYMBOLS sur l'histoire
     * choisie, ouvre (ou crée) son fichier de sauvegarde dédié, et pose le
     * totem de départ au tout premier lancement.
     */
    fun switchStory(slug: String): Boolean {
        val story = storyRegistry.allStories()[slug] ?: return false

        PIP_SYMBOLS.clear()
        PIP_SYMBOLS.putAll(story.pipSymbols)
        // DEFAULT_PIP_SYMBOL reste "" : aucune histoire codée en dur
        // n'existe plus (StoryEntry.defaultPipSymbol vaut toujours "" pour
        // une histoire personnalisée) — rien à rebasculer ici, contrairement
        // au Python qui réassigne dice_engine.DEFAULT_PIP_SYMBOL.

        val saveFile = File(baseDir, story.saveFile)
        val newSession = DiceSession(saveFile)
        val wasLoaded = newSession.load()

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
     * de totem sous un nom généré. Miroir de _save_totem_image() côté Python.
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
            Base64.getEncoder().encodeToString(file.readBytes())
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Miroir de do_create_story(). bgImageExt n'est plus utilisé : comme en
     * Python (stories.py), l'image de fond est de toute façon toujours
     * réencodée en JPEG par StoryRegistry.createCustomStory().
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
        protagonistName: String = "",
        storyLength: String = "long"
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
            protagonistName = protagonistName,
            storyLength = storyLength
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

    /** Miroir de do_add_custom_totem(). imageBytes peut être vide (pas d'image fournie). */
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

    /** Miroir de do_remove_custom_totem() : supprime aussi le fichier image associé, s'il existe. */
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

    fun completeSideQuest(questId: Int): JSONObject {
        session?.completeSideQuest(questId)
        return currentSessionJson()
    }

    fun addStoryEntry(text: String): JSONObject {
        session?.addStoryEntry(text)
        return currentSessionJson()
    }

    // ------------------------------------------------------------------
    // Narration IA (miroir de build_mechanics_context/build_full_prompt/
    // build_ai_kickoff_message/maybe_update_story_digest/run_ai_narrator)
    // ------------------------------------------------------------------

    private val successCompact: Map<Int, String> = mapOf(
        1 to "Échec critique, rattrapable (jamais la fin de l'histoire)",
        2 to "Échec ou réussite très dure",
        3 to "Réussite partielle",
        4 to "Bonne réussite",
        5 to "Très bonne réussite",
        6 to "Réussite héroïque"
    )

    private val fateCompact: Map<String, String> = mapOf(
        "coeur" to "allié/protection : un ami ou héros intervient, guérison, lien renforcé, ou miracle",
        "question" to "quête secondaire débloquée (nouvel ami ou nouvel objet/totem), à faire quand on veut",
        "soleil" to "bénédiction : énergie positive, pouvoir renforcé/stabilisé, protection, amélioration durable",
        "etoile" to "chance exceptionnelle : opportunité rare, découverte précieuse, ou récompense spéciale",
        "exclamation" to "aide arrive : une connaissance, ou un animal lié à un totem, intervient",
        "spirale" to "chaos/transformation : effet imprévisible, mutation, ou conséquence inattendue"
    )

    private fun buildMechanicsContext(sess: DiceSession, story: StoryEntry?, autoMode: Boolean): String {
        val protagonistRef = story?.protagonistRef?.ifEmpty { "le joueur" } ?: "le joueur"
        val lines = mutableListOf<String>()
        lines += "=== CONTEXTE IA NARRATRICE ==="
        lines += if (autoMode) {
            "Narrateur d'une histoire héroïque interactive, 2e personne ('tu'), adressée à " +
                "$protagonistRef. Mécaniques ci-dessous ; l'histoire se poursuit directement dans " +
                "cette conversation, événement par événement."
        } else {
            "Narrateur d'une histoire héroïque interactive, 2e personne ('tu'), adressée à " +
                "$protagonistRef. Mécaniques ci-dessous, puis l'histoire déjà vécue."
        }
        lines += ""
        lines += "DÉ DE RÉUSSITE (1-6, jamais de fin d'histoire même sur un 1, toujours moyen de se rattraper) :"
        lines += (1..6).joinToString(" | ") { v -> "$v=${successCompact[v]}" }
        lines += ""
        lines += "DÉ DU DESTIN (6 symboles ; le ? ne retombe pas tant qu'une quête secondaire ouverte " +
            "n'est pas terminée, une seule active à la fois) :"
        lines += FATE_FACES.joinToString(" | ") { f -> "${f.emoji}${f.label}=${fateCompact[f.key]}" }
        lines += ""
        lines += "TOTEMS/ALLIÉS (symbole choisi sur le dé de réussite) :"
        for (t in sess.customTotems) {
            val icon = t.emoji.ifEmpty { "\uD83D\uDC3E" }
            val powersTxt = if (t.powers.isNotEmpty()) t.powers.joinToString(", ") else "(pouvoirs non précisés)"
            val specialTxt = if (t.special.isNotEmpty()) " — spé: ${t.special}" else ""
            lines += "$icon${t.label}: $powersTxt$specialTxt"
        }
        val fixedAlliesLine = story?.fixedAlliesLine ?: ""
        if (fixedAlliesLine.isNotEmpty()) lines += fixedAlliesLine
        if (sess.customTotems.isEmpty()) lines += "(aucun pour l'instant -- tout reste à découvrir en jouant)"
        lines += "Jauge par totem/allié : +score obtenu (symbole unique) ou +1/symbole (mélange) à " +
            "chaque lancer concerné -> pleine à $TOTEM_ENERGY_THRESHOLD points, alors utilisable " +
            "(capacité ou aide correspondante)."
        lines += ""
        lines += "JAUGE DE MENACE : +3 sur un 1, -1 sur un 5 ou 6. À $THREAT_THRESHOLD points, " +
            "complication secondaire inattendue puis retombe à 0."
        lines += ""
        lines += buildStoryLengthInstructions(story)
        if (!autoMode) {
            // Mode manuel : buildFullPrompt()/buildFullPromptText() recalcule
            // tout ce texte à chaque clic sur "copier le prompt", donc un
            // compte à jour ici reste toujours exact. En mode auto, ce même
            // calcul existe (buildStoryProgressNote), mais est ajouté à part
            // dans runAiNarrator à CHAQUE appel API, jamais ici : ce texte-ci
            // n'est écrit qu'UNE SEULE fois dans le message "system" figé de
            // la conversation (voir plus bas), un compte à rebours y serait
            // juste devenu faux dès l'échange suivant.
            buildStoryProgressNote(sess, story)?.let {
                lines += ""
                lines += it
            }
        }
        for (paragraph in story?.loreParagraphs ?: emptyList()) {
            lines += ""
            lines += paragraph
        }
        lines += ""
        lines += "TON DU RÉCIT : l'histoire s'adresse à un enfant -- écris de manière vivante et " +
            "chaleureuse, et parsème régulièrement tes paragraphes de quelques emojis/petites icônes " +
            "pertinentes (\u2728\uD83C\uDF1F\uD83D\uDC3E etc.) pour illustrer les événements et " +
            "égayer le texte. Reste sobre : quelques emojis bien placés par paragraphe suffisent, " +
            "jamais un amoncellement d'icônes qui alourdirait la réponse pour rien."
        lines += ""
        lines += if (autoMode) {
            "ATTENDU DE TOI : histoire collaborative et immersive intégrant directement les " +
                "événements de jeu que je te transmets (lancers de dés, pouvoirs utilisés, quêtes...) ; " +
                "à chaque action/incertitude tu me demandes explicitement de lancer le dé de réussite, " +
                "le dé du destin, ou les deux, et tu attends le résultat suivant avant de continuer ; " +
                "tu t'adresses toujours à $protagonistRef en 'tu'."
        } else {
            "ATTENDU DE TOI : histoire collaborative et immersive intégrant mes lancers ; à chaque " +
                "action/incertitude tu me demandes de lancer réussite/destin/les deux et attends mon " +
                "résultat avant de continuer ; si j'utilise une jauge pleine ou qu'un ?/! survient je " +
                "te colle un petit bloc généré par l'appli pour te le signaler précisément ; à la fin " +
                "de chaque chapitre tu me donnes un résumé à coller dans l'appli pour garder une trace " +
                "permanente ; tu t'adresses toujours à $protagonistRef en 'tu'."
        }
        return lines.joinToString("\n")
    }

    /**
     * Explication du format choisi (StoryEntry.storyLength) -- texte fixe,
     * ne dépend PAS du nombre d'échanges déjà écoulés (voir
     * buildStoryProgressNote() pour le compte à jour, séparé). Intégrée une
     * seule fois dans le message "system" en mode auto (figé pour le reste
     * de la conversation), recalculée à chaque clic "copier le prompt" en
     * mode manuel -- dans les deux cas ce texte reste identique du début à
     * la fin de l'histoire, donc aucun souci de fraîcheur ici.
     */
    private fun buildStoryLengthInstructions(story: StoryEntry?): String {
        return when (story?.storyLength ?: "long") {
            "short" -> "FORMAT DE L'HISTOIRE : COURT -- une dizaine d'échanges au total. " +
                "Construis une histoire complète qui se conclut naturellement aux alentours du " +
                "dixième échange : ni bâclée avant, ni étirée artificiellement après. Amorce le " +
                "dénouement dès que tu sens qu'on approche de la fin prévue (voir le rappel de " +
                "progression ci-dessous, mis à jour à chaque échange)."
            "medium" -> "FORMAT DE L'HISTOIRE : MOYEN -- entre 20 et 30 échanges au total. " +
                "Construis une histoire complète qui se conclut naturellement dans cette " +
                "fourchette : ni bâclée avant, ni étirée artificiellement après. Amorce le " +
                "dénouement dès que tu sens qu'on approche de la fin prévue (voir le rappel de " +
                "progression ci-dessous, mis à jour à chaque échange)."
            else -> "FORMAT DE L'HISTOIRE : LONG, en CHAPITRES, sans limite totale d'échanges. " +
                "Chaque chapitre doit poser une vraie problématique puis la résoudre avant sa " +
                "fin -- jamais une coupure arbitraire en plein milieu de l'action. Dès qu'un " +
                "chapitre se termine (problématique résolue), tu t'arrêtes : propose une vraie " +
                "conclusion de chapitre, puis demande explicitement au joueur s'il souhaite " +
                "commencer le chapitre suivant. Tu n'enchaînes JAMAIS automatiquement sur un " +
                "nouveau chapitre tant qu'il n'a pas répondu positivement à cette question."
        }
    }

    /**
     * Rappel de progression COURT et A JOUR, distinct du texte fixe
     * ci-dessus : compte les échanges déjà narrés dans sess.aiConversation
     * (même principe de comptage que maybeUpdateStoryDigest(), qui sait
     * déjà repérer des paquets de 6 messages pour le digest journal --
     * ici, pas de seuil déclencheur, juste une information transmise à
     * chaque appel). Renvoie null pour le format "long" : les chapitres
     * n'ont pas de compte à rebours numérique à suivre, la consigne fixe
     * ci-dessus suffit.
     *
     * IMPORTANT (voir buildMechanicsContext) : ce texte ne doit JAMAIS être
     * écrit une fois pour toutes dans sess.aiConversation (donc jamais via
     * sess.addAiMessage) -- en mode auto il est recalculé et rajouté à part
     * à CHAQUE appel API par runAiNarrator(), sans être sauvegardé, pour
     * rester exact d'un échange à l'autre.
     */
    private fun buildStoryProgressNote(sess: DiceSession, story: StoryEntry?): String? {
        val exchangeCount = sess.aiConversation.count { it.role == "assistant" }
        return when (story?.storyLength ?: "long") {
            "short" -> "RAPPEL DE PROGRESSION (ne mentionne jamais ce rappel technique au joueur) : " +
                "$exchangeCount échange(s) narré(s) jusqu'ici, sur une dizaine visée au total."
            "medium" -> "RAPPEL DE PROGRESSION (ne mentionne jamais ce rappel technique au joueur) : " +
                "$exchangeCount échange(s) narré(s) jusqu'ici, sur 20 à 30 visés au total."
            else -> null
        }
    }

    private fun buildFullPrompt(sess: DiceSession, story: StoryEntry?): String {
        val lines = mutableListOf(buildMechanicsContext(sess, story, autoMode = false), "", "--- HISTOIRE DÉJÀ VÉCUE ---")
        val log = sess.storyLogText()
        lines += log.ifEmpty { "(aucun chapitre enregistré pour l'instant, on commence une aventure toute neuve)" }
        return lines.joinToString("\n")
    }

    private fun buildAiKickoffMessage(sess: DiceSession, story: StoryEntry?): String {
        val lines = mutableListOf(buildMechanicsContext(sess, story, autoMode = true))
        val log = sess.storyLogText()
        if (log.isNotEmpty()) {
            lines += ""
            lines += "--- HISTOIRE DÉJÀ VÉCUE ---"
            lines += log
            lines += ""
            lines += "Commence maintenant le prochain chapitre de l'histoire, dans la continuité " +
                "directe de ce qui précède."
        } else {
            val protagonistRef = story?.protagonistRef?.ifEmpty { "le joueur" } ?: "le joueur"
            lines += ""
            lines += "Aucun chapitre n'a encore été joué. Commence maintenant le tout premier " +
                "chapitre de cette aventure : plante le décor et présente la situation de départ de " +
                "$protagonistRef, puis demande-moi le premier lancer dès que la situation l'exige."
        }
        return lines.joinToString("\n")
    }

    /** Miroir de narrator_note_for_record() : texte à ajouter pour un "?" (quête débloquée) ou "!" (aide). */
    private fun narratorNoteForRecord(record: RollRecord?): String {
        val fateKey = record?.fate ?: return ""
        if (fateKey != "question" && fateKey != "exclamation") return ""
        val face = FATE_BY_KEY[fateKey] ?: return ""
        var text = "${face.emoji} ${face.label} : ${face.desc}"
        val sideQuest = record.sideQuest
        if (sideQuest != null) {
            val kindTxt = if (sideQuest.kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou un nouveau totem)"
            text += "\n(Quête secondaire débloquée : $kindTxt.)"
        }
        return text
    }

    /** Miroir de build_ai_event_text(). */
    private fun buildAiEventText(sess: DiceSession, record: RollRecord): String {
        val parts = mutableListOf(sess.describeRecord(record))
        val note = narratorNoteForRecord(record)
        if (note.isNotEmpty()) parts += note
        return parts.joinToString("\n")
    }

    /**
     * Envoie eventText à l'IA (en amorçant la conversation avec le message
     * système si elle est vide), ajoute la réponse à la conversation, et
     * tente une mise à jour du digest journal/résumé. Miroir de run_ai_narrator().
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
     * @return (texte de la réponse, erreur) — comme MistralClient.chat().
     */
    private fun runAiNarrator(eventText: String): Pair<String?, String?> {
        val sess = session ?: return null to null
        if (!hasMistralKey() || eventText.isEmpty()) return null to null
        if (sess.aiConversation.isEmpty()) {
            sess.addAiMessage("system", buildMechanicsContext(sess, currentStory, autoMode = true))
        }
        sess.addAiMessage("user", eventText)
        val messagesToSend = sess.aiMessagesToSend().toMutableList()
        buildStoryProgressNote(sess, currentStory)?.let { note ->
            // Juste avant le dernier message (le "user" qu'on vient
            // d'ajouter), pour rester proche de la convention "la
            // conversation envoyée se termine par un message user".
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        val (text, error) = MistralClient.chat(
            getMistralKey(),
            messagesToSend,
            model = getMistralModel(),
            promptCacheKey = currentStorySlug
        )
        if (error != null) return null to error
        sess.addAiMessage("assistant", text ?: "")
        maybeUpdateStoryDigest(sess)
        return text to null
    }

    private val digestChapterTag = "###CHAPITRE###"
    private val digestSummaryTag = "###RESUME###"

    /** Miroir de maybe_update_story_digest() — appel "fantôme" : n'écrit jamais dans aiConversation. */
    private fun maybeUpdateStoryDigest(sess: DiceSession) {
        val digestTrigger = 6
        val digestMaxTokens = 700
        if (!hasMistralKey()) return
        while (true) {
            val pending = sess.pendingDigestMessages()
            if (pending.size < digestTrigger) return
            val chunk = pending.take(digestTrigger)
            val block = chunk.joinToString("\n") { m -> "${if (m.role == "user") "Joueur" else "Narrateur"} : ${m.content}" }
            val existingSummary = sess.storySummary

            val digestSystem = "Tu reçois un extrait récent d'une histoire de jeu de rôle pour " +
                "enfant (en français) et dois produire DEUX textes bien distincts, chacun introduit " +
                "par sa balise exacte sur sa propre ligne, rien d'autre avant/après/entre :\n" +
                "$digestChapterTag\n" +
                "Un chapitre de journal racontant cet extrait : à la troisième personne, fluide et " +
                "narratif (pas une liste de faits, pas de dialogue au style direct), 80 à 120 mots.\n" +
                "$digestSummaryTag\n" +
                "Le résumé long terme mis à jour : fusion du résumé existant (s'il y en a un) et des " +
                "nouveaux faits marquants de cet extrait -- personnages, objets/totems, lieux, " +
                "quêtes, événements à retenir. Style neutre et factuel, pas de tournures narratives. " +
                "150 mots maximum."
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
        }
    }

    /**
     * Miroir de build_full_prompt() : texte du prompt complet (mécaniques +
     * histoire déjà vécue), pour le bouton "copier le prompt complet" du
     * mode manuel (sans clé Mistral). Chaîne vide si aucune histoire n'est
     * sélectionnée.
     */
    fun buildFullPromptText(): String {
        val sess = session ?: return ""
        return buildFullPrompt(sess, currentStory)
    }

    /** Miroir de do_send_full_prompt(). */
    fun sendFullPrompt(): JSONObject {
        val sess = session ?: return currentSessionJson()
        val (_, aiError) = runAiNarrator(buildAiKickoffMessage(sess, currentStory))
        return sessionToJson(sess).apply { put("ai_error", aiError ?: JSONObject.NULL) }
    }

    /** Miroir de do_send_ai_message() : envoie le lancer en attente (s'il y en a un) + un texte libre optionnel. */
    fun sendAiMessage(text: String = ""): JSONObject {
        val sess = session ?: return currentSessionJson()
        val pending = sess.pendingRoll()
        val parts = mutableListOf<String>()
        if (pending != null) parts += buildAiEventText(sess, pending)
        if (text.isNotEmpty()) parts += text
        val combined = parts.joinToString("\n")
        var aiError: String? = null
        if (combined.isNotEmpty()) {
            val (_, err) = runAiNarrator(combined)
            aiError = err
            if (pending != null) sess.markLastRollAsSent()
        }
        return sessionToJson(sess).apply { put("ai_error", aiError ?: JSONObject.NULL) }
    }

    /** Miroir de do_reset_ai_conversation() : réinitialise TOUTE la partie (reset_story_to_origin), pas seulement la conversation IA. */
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
    // Configuration Mistral (miroir de load_app_config/save_app_config/
    // get_mistral_key/set_mistral_key/get_mistral_model/set_mistral_model/
    // get_config_screen_state — app_config.json)
    // ------------------------------------------------------------------

    private val appConfigFile = File(baseDir, "app_config.json")
    // Aligné sur MistralClient.DEFAULT_MODEL plutôt que dupliqué en dur :
    // avant ce correctif, cette constante valait "mistral-small-latest",
    // un identifiant absent de MistralClient.MODEL_CHOICES (qui liste
    // "mistral-small-2603" comme modèle recommandé/par défaut) — une
    // première installation sans app_config.json récupérait donc un modèle
    // qui ne correspondait à aucune entrée de la liste déroulante de
    // ConfigureKeyScreen. En pointant vers la même constante que le client,
    // les deux fichiers ne peuvent plus diverger.
    private val defaultMistralModel = MistralClient.DEFAULT_MODEL

    private fun loadAppConfig(): JSONObject {
        if (!appConfigFile.exists()) {
            return JSONObject().apply { put("mistral_api_key", ""); put("mistral_model", defaultMistralModel) }
        }
        return try {
            val data = JSONObject(appConfigFile.readText())
            JSONObject().apply {
                put("mistral_api_key", data.optString("mistral_api_key", ""))
                put("mistral_model", data.optString("mistral_model", defaultMistralModel).ifEmpty { defaultMistralModel })
            }
        } catch (e: Exception) {
            JSONObject().apply { put("mistral_api_key", ""); put("mistral_model", defaultMistralModel) }
        }
    }

    private fun saveAppConfig(config: JSONObject) {
        appConfigFile.writeText(config.toString(2))
    }

    fun getMistralKey(): String = loadAppConfig().optString("mistral_api_key", "")

    fun setMistralKey(key: String) {
        val config = loadAppConfig()
        config.put("mistral_api_key", key.trim())
        saveAppConfig(config)
    }

    fun clearMistralKey() = setMistralKey("")

    fun hasMistralKey(): Boolean = getMistralKey().isNotEmpty()

    fun getMistralModel(): String = loadAppConfig().optString("mistral_model", defaultMistralModel)

    fun setMistralModel(model: String) {
        val config = loadAppConfig()
        config.put("mistral_model", model.trim().ifEmpty { defaultMistralModel })
        saveAppConfig(config)
    }

    fun getConfigScreenState(): JSONObject {
        val key = getMistralKey()
        val maskedKey = if (key.isNotEmpty()) "*".repeat(maxOf(0, key.length - 4)) + key.takeLast(4) else ""
        return JSONObject().apply {
            put("has_key", hasMistralKey())
            put("masked_key", maskedKey)
            put("model", getMistralModel())
            put(
                "model_choices",
                JSONArray(MistralClient.MODEL_CHOICES.map { (value, label) -> JSONArray(listOf(value, label)) })
            )
        }
    }

    // ------------------------------------------------------------------
    // Export / import (miroir de export_journal/export_identity/
    // do_import_identity/do_apply_imported_progress)
    // ------------------------------------------------------------------

    /**
     * Miroir de export_journal() : PDF (JournalExporter) si possible, sinon
     * texte brut (session.storyLogText()) — jamais d'erreur côté joueur.
     * @return (octets, nom de fichier, type MIME), ou triple vide si aucune session active.
     */
    fun exportJournal(): Triple<ByteArray, String, String> {
        val sess = session ?: return Triple(ByteArray(0), "", "")
        val title = currentStory?.title?.ifEmpty { "Journal de l'histoire" } ?: "Journal de l'histoire"
        val subtitle = currentStory?.subtitle ?: ""
        val slug = currentStorySlug ?: "histoire"
        val pdfBytes = JournalExporter.generateJournalPdf(title, subtitle, sess.storyLog)
        return if (pdfBytes != null) {
            Triple(pdfBytes, "journal_$slug.pdf", "application/pdf")
        } else {
            val text = sess.storyLogText().ifEmpty { "(aucun chapitre enregistré pour l'instant)" }
            Triple(text.toByteArray(Charsets.UTF_8), "journal_$slug.txt", "text/plain")
        }
    }

    /**
     * Miroir de export_identity() : le totem de départ (posé par
     * switchStory) n'est pas ré-exporté comme "extra" — seuls ceux ajoutés
     * ensuite par le joueur le sont.
     */
    fun exportIdentity(): Triple<ByteArray, String, String> {
        val story = currentStory
        val slug = currentStorySlug
        if (story == null || !story.isCustom || slug == null) return Triple(ByteArray(0), "", "")
        val sess = session
        val extraTotems = JSONArray()
        var sideQuests = JSONArray()
        var nextQuestId = 1
        var storyLog = JSONArray()
        var storySummary = ""
        if (sess != null) {
            for (t in sess.customTotems.drop(1)) {
                extraTotems.put(
                    JSONObject().apply {
                        put("label", t.label)
                        put("emoji", t.emoji)
                        put("powers", JSONArray(t.powers))
                        put("special", t.special)
                        put("image_b64", totemImageToBase64(t.image))
                    }
                )
            }
            sideQuests = JSONArray(sess.sideQuests.map { it.toJson() })
            nextQuestId = sess.nextQuestId
            storyLog = JSONArray(sess.storyLog)
            storySummary = sess.storySummary
        }
        val data = storyRegistry.exportStoryIdentity(
            slug,
            extraTotems = extraTotems,
            sideQuests = sideQuests,
            nextQuestId = nextQuestId,
            storyLog = storyLog,
            storySummary = storySummary
        ) ?: return Triple(ByteArray(0), "", "")
        val payload = data.toString(2)
        return Triple(payload.toByteArray(Charsets.UTF_8), "histoire_$slug.json", "application/json")
    }

    /** Miroir de do_import_identity() : ne fait qu'analyser/valider le JSON importé, sans rien appliquer. */
    fun importIdentity(rawText: String): JSONObject {
        val parsed = storyRegistry.parseIdentityImport(rawText)
        return JSONObject().apply {
            put("title", parsed.title)
            put("subtitle", parsed.subtitle)
            put("lore_text", parsed.loreText)
            put("totem_label", parsed.totemLabel)
            put("totem_powers", parsed.totemPowers)
            put("totem_special", parsed.totemSpecial)
            put("protagonist_name", parsed.protagonistName)
            // Base64, pas des bytes bruts, pour rester manipulable en JSON —
            // c'est CreateStoryScreen qui décode avant de rappeler createStory.
            put("bg_image_b64", parsed.bgImageB64)
            put("totem_image_b64", parsed.totemImageB64)
            // Totems supplémentaires : à recréer à part via addCustomTotem
            // juste après createStory — jamais transmis à createStory
            // lui-même, qui ne doit jamais poser plus d'un totem de départ.
            put(
                "extra_totems",
                JSONArray(
                    parsed.extraTotems.map { t ->
                        JSONObject().apply {
                            put("label", t.label)
                            put("emoji", t.emoji)
                            put("powers", JSONArray(t.powers))
                            put("special", t.special)
                            put("image_b64", t.imageB64)
                        }
                    }
                )
            )
            // Quêtes secondaires et journal : à appliquer à part via
            // applyImportedProgress, une fois l'histoire créée.
            put("side_quests", parsed.sideQuests)
            put("next_quest_id", parsed.nextQuestId)
            put("story_log", parsed.storyLog)
            put("story_summary", parsed.storySummary)
            put("story_length", parsed.storyLength)
        }
    }

    /** Miroir de do_apply_imported_progress() : restaure quêtes/journal sur la session en cours. */
    fun applyImportedProgress(sideQuestsJson: JSONArray, nextQuestId: Int, storyLogJson: JSONArray, storySummary: String): JSONObject {
        val sess = session ?: return currentSessionJson()
        sess.importProgress(sideQuests = sideQuestsJson, nextQuestId = nextQuestId, storyLog = storyLogJson, storySummary = storySummary)
        return sessionToJson(sess)
    }

    // ------------------------------------------------------------------
    // Énergie totémique
    // ------------------------------------------------------------------

    /** Miroir de do_use_totem_energy(), narration IA comprise. */
    fun useTotemEnergy(key: String, text: String = ""): JSONObject {
        val sess = session ?: return currentSessionJson().apply {
            put("spent", false)
            put("effect", "")
            put("ai_error", JSONObject.NULL)
        }
        var effectText = ""
        val spent = sess.spendTotemEnergy(key)
        if (spent) {
            if (key == "patte" || key == "baguette") {
                // TOTEMS_BY_KEY (histoires codées en dur) est toujours vide
                // dans l'appli actuelle : comme en Python, le libellé
                // retombe donc sur la clé elle-même plutôt que sur le
                // totem personnalisé homonyme.
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
            if (pending != null) parts += buildAiEventText(sess, pending)
            if (text.isNotEmpty()) parts += text
            parts += effectText
            val (_, err) = runAiNarrator(parts.joinToString("\n"))
            aiError = err
            if (pending != null) sess.markLastRollAsSent()
        }
        return sessionToJson(sess).apply {
            put("spent", spent)
            put("effect", effectText)
            put("ai_error", aiError ?: JSONObject.NULL)
        }
    }

    // ------------------------------------------------------------------
    // Dés classiques (page indépendante, aucune dépendance manquante)
    // ------------------------------------------------------------------

    private val classicDiceFile = File(baseDir, "classic_dice_state.json")
    private val classicFateKeys = FATE_FACES.map { it.key }

    private fun loadClassicDiceState(): JSONObject {
        if (!classicDiceFile.exists()) {
            return JSONObject().apply { put("history", JSONArray()); put("next_id", 1) }
        }
        return try {
            JSONObject(classicDiceFile.readText())
        } catch (e: Exception) {
            JSONObject().apply { put("history", JSONArray()); put("next_id", 1) }
        }
    }

    private fun saveClassicDiceState(state: JSONObject) {
        classicDiceFile.writeText(state.toString(2))
    }

    /**
     * Enrichit chaque entrée avec l'emoji/libellé du destin, comme
     * classic_dice_to_dict() côté Python.
     */
    private fun classicDiceToJson(state: JSONObject): JSONObject {
        val rawHistory = state.optJSONArray("history") ?: JSONArray()
        val history = JSONArray()
        for (i in 0 until rawHistory.length()) {
            val entry = rawHistory.optJSONObject(i) ?: continue
            val item = JSONObject(entry.toString())
            val fateKey = if (entry.isNull("fate")) null else entry.optString("fate", null)
            val face = FATE_BY_KEY[fateKey]
            if (face != null) {
                item.put("fate_emoji", face.emoji)
                item.put("fate_label", face.label)
            }
            history.put(item)
        }
        return JSONObject().apply {
            put("history", history)
            put("next_id", state.optInt("next_id", 1))
        }
    }

    fun classicDiceState(): JSONObject = classicDiceToJson(loadClassicDiceState())

    fun classicDiceRoll(kind: String): JSONObject {
        val state = loadClassicDiceState()
        val history = state.optJSONArray("history") ?: JSONArray().also { state.put("history", it) }
        val nextId = state.optInt("next_id", 1)

        val entry = JSONObject().apply {
            put("id", nextId)
            put("success", if (kind == "success" || kind == "both") Random.nextInt(1, 7) else JSONObject.NULL)
            put("fate", if (kind == "fate" || kind == "both") classicFateKeys.random() else JSONObject.NULL)
        }
        history.put(entry)
        state.put("next_id", nextId + 1)

        // Ne garde que les 30 derniers lancers, comme en Python (state["history"][-30:]).
        val trimmed = JSONArray()
        val start = maxOf(0, history.length() - 30)
        for (i in start until history.length()) trimmed.put(history.get(i))
        state.put("history", trimmed)

        saveClassicDiceState(state)
        return classicDiceToJson(state)
    }

    fun classicDiceClear(): JSONObject {
        val state = JSONObject().apply { put("history", JSONArray()); put("next_id", 1) }
        saveClassicDiceState(state)
        return classicDiceToJson(state)
    }
}
