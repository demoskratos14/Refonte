// GameEngine.kt
// Portage Kotlin de game_api.py — ÉTAPE 3, partie 2 (lot 1/3).
//
// Couvre tout ce qui ne dépend PAS de mistral_client.py / journal_export.py /
// image_utils.py (pas encore fournis) :
//   - construction de l'état de session envoyé à Compose (sessionToJson,
//     miroir de session_to_dict + _totem_info)
//   - navigation entre histoires : listStories/switchStory/selectStory/
//     deleteStory/createStory (miroir de list_stories/switch_story/
//     select_story/do_delete_story/do_create_story)
//   - lancers de dés : roll/undo/clear
//   - contraintes de tirage et symboles : setPipSymbol/setPipMode/
//     toggleEnabledSymbol/toggleAllowedValue/resetAllowedValues/
//     toggleAllowedFate/resetAllowedFate
//   - énergie totémique : useTotemEnergy (sans le déclenchement de la
//     narration IA — voir TODO à l'intérieur)
//   - dés classiques : classicDiceState/classicDiceRoll/classicDiceClear
//
// Ce que ce fichier NE couvre PAS encore (lots suivants) :
//   - run_ai_narrator / do_send_ai_message / do_send_full_prompt /
//     do_reset_ai_conversation (besoin du portage de mistral_client.py)
//   - get_config_screen_state / set_mistral_key / set_mistral_model (idem)
//   - export_journal / export_identity / do_import_identity /
//     do_apply_imported_progress
//   - do_add_custom_totem / do_remove_custom_totem avec image (besoin du
//     portage d'image_utils.resize_totem_bytes — pour l'instant
//     saveTotemImageBytes() enregistre l'image telle quelle, SANS
//     redimensionnement)
//
// NB : TOTEMS/ALLY_HELP_TEXT (dérivés de STORIES, l'histoire codée en dur)
// sont toujours vides dans l'appli actuelle — voir StoryRegistry.kt. Le
// code ci-dessous reflète donc une version simplifiée de _totem_info /
// do_use_totem_energy qui n'a plus besoin de ces deux globales.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
     * Enregistre l'image de totem telle quelle (SANS redimensionnement —
     * voir TODO en tête de fichier ; à remplacer par
     * ImageUtils.resizeTotemBytes() une fois ce fichier porté).
     */
    private fun saveTotemImageBytes(bytes: ByteArray, originalFilename: String): String? {
        if (bytes.isEmpty()) return null
        var ext = originalFilename.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("png", "jpg", "jpeg", "gif", "webp")) ext = "png"
        val dir = File(baseDir, "totem_images")
        dir.mkdirs()
        val generatedFilename = "${UUID.randomUUID().toString().replace("-", "")}.$ext"
        File(dir, generatedFilename).writeBytes(bytes)
        return generatedFilename
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
        protagonistName: String = ""
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
            protagonistName = protagonistName
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
    // Énergie totémique
    // ------------------------------------------------------------------

    /**
     * Miroir de do_use_totem_energy(), MOINS le déclenchement de la
     * narration IA (run_ai_narrator) : le résultat inclut "spent" et
     * "effect" comme en Python, mais "ai_error" reste toujours null pour
     * l'instant.
     * TODO (lot 2, après portage de mistral_client.py) : si spent &&
     * effect.isNotEmpty(), construire le texte d'événement (pendingRoll +
     * describeRecord + effect) et appeler le narrateur IA, comme
     * do_use_totem_energy le fait après avoir dépensé la jauge.
     */
    fun useTotemEnergy(key: String): JSONObject {
        val sess = session ?: return currentSessionJson().apply {
            put("spent", false)
            put("effect", "")
            put("ai_error", JSONObject.NULL)
        }
        var effectText = ""
        val spent = sess.spendTotemEnergy(key)
        if (spent) {
            if (key == "patte" || key == "baguette") {
                val label = sess.customTotems.find { it.key == key }?.label ?: key
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
        return sessionToJson(sess).apply {
            put("spent", spent)
            put("effect", effectText)
            put("ai_error", JSONObject.NULL)
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
