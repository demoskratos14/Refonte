// DiceSession.kt
// Portage Kotlin de dice_engine.py — ÉTAPE 1 + ÉTAPE 2
//
// Ce que ce fichier couvre déjà (étape 1 : cœur des lancers) :
//   - constantes, libellés, faces du destin, combos narratifs
//   - rollSuccess / rollFate / rollBoth / secondSouffle / undoLast / clearHistory / resetAll
//   - save() / load() avec EXACTEMENT les mêmes clés JSON que dice_state_<slug>.json
//   - describeRecord() / historyText()
//   - la plomberie interne nécessaire aux lancers (jauge totem, jauge de menace,
//     choix des pips, pool du destin, création de quête secondaire)
//
// Ce que ce fichier couvre en plus (étape 2 : gestion complète, miroir exact de
// dice_engine.py) :
//   - symboles/totems : toggleEnabledSymbol, setPipSymbol, setPipMode,
//     addCustomTotem, removeCustomTotem, allSymbols(), spendTotemEnergy
//   - contraintes de tirage : toggleAllowedValue/resetAllowedValues,
//     toggleAllowedFate/resetAllowedFate
//   - quêtes secondaires : completeSideQuest (openSideQuests() existait déjà)
//   - symbole ❓ du dé du destin : mécanique selon la longueur de l'histoire
//       courte  -> événement soudain et inattendu (aucune quête annexe)
//       moyenne -> quête secondaire construite en parallèle de l'intrigue principale
//       longue  -> quête courte lancée automatiquement à la fin du chapitre
//   - journal de l'histoire : addStoryEntry/storyLogText
//   - narration IA : addAiMessage, resetAiConversation, pendingDigestMessages,
//     applyStoryDigest, markLastRollAsSent, pendingRoll, aiMessagesToSend
//   - import d'identité : importProgress
//
// Ce qui reste pour l'ÉTAPE 3 (hors de cette classe) :
//   - StoryRegistry côté Kotlin : peupler PIP_SYMBOLS selon l'histoire active
//     et fournir le bon saveFile (dice_state_<slug>.json) par histoire
//   - GameViewModel : orchestration UI <-> DiceSession, équivalent des routes
//     do_* de dice_web.py
//   - Portage de mistral_client.py (appel réseau à l'IA narratrice via Chaquopy
//     ou HTTP direct), journal_export.py, image_utils.py
//
// NB save file : dans dice_engine.py, SAVE_FILE = "dice_state.json" est une constante
// globale, mais dans l'appli réelle chaque histoire a son propre fichier
// (dice_state_<slug>.json), vraisemblablement fixé par StoryRegistry/dice_web.py
// (non visible dans dice_engine.py). En attendant l'étape 3, saveFile est un
// paramètre du constructeur — GameViewModel le pointera vers le bon fichier par histoire.

package com.aventure.desdice // TODO: remplacer par le vrai nom de package de l'appli

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ------------------------------------------------------------------------
// Constantes (miroir exact de dice_engine.py)
// ------------------------------------------------------------------------

const val TOTEM_ENERGY_THRESHOLD = 15
const val THREAT_THRESHOLD = 10
const val AI_HISTORY_WINDOW = 24

val SUCCESS_LABELS: Map<Int, String> = mapOf(
    1 to "Échec critique / conséquence importante (mais jamais la fin de l'histoire : il y a toujours moyen de se rattraper)",
    2 to "Échec, ou réussite très difficile",
    3 to "Réussite partielle",
    4 to "Bonne réussite",
    5 to "Très bonne réussite",
    6 to "Réussite exceptionnelle, héroïque !"
)

data class FateFace(val key: String, val emoji: String, val label: String, val desc: String)

val FATE_FACES: List<FateFace> = listOf(
    FateFace(
        "coeur", "\u2764\uFE0F", "Cœur",
        "Allié / protection / aide providentielle : un ami ou héros peut intervenir, " +
            "une guérison peut survenir, un lien peut se renforcer ou un miracle se produire."
    ),
    FateFace(
        "question", "\u2753", "Point d'interrogation",
        "Le destin s'en mêle : événement soudain (histoire courte), quête secondaire menée en " +
            "parallèle (histoire moyenne) ou quête courte à la fin du chapitre (histoire longue)."
    ),
    FateFace(
        "soleil", "\u2600\uFE0F", "Soleil",
        "Bénédiction / amélioration : énergie positive, pouvoir renforcé ou stabilisé, " +
            "protection, amélioration durable."
    ),
    FateFace(
        "etoile", "\u2B50", "Étoile",
        "Chance exceptionnelle : opportunité rare, découverte précieuse, coup de chance " +
            "ou récompense spéciale."
    ),
    FateFace(
        "exclamation", "\u2757", "Point d'exclamation",
        "De l'aide arrive : une personne que l'on connaît vient prêter main forte, ou un " +
            "animal lié à l'un de nos totems intervient pour nous aider."
    ),
    FateFace(
        "spirale", "\uD83C\uDF00", "Spirale",
        "Chaos / transformation : effet imprévisible, mutation, transformation inhabituelle " +
            "ou conséquence qui peut modifier l'histoire de façon inattendue."
    )
)
val FATE_BY_KEY: Map<String, FateFace> = FATE_FACES.associateBy { it.key }

// Combos narratifs (dé de réussite, dé du destin) -> note, UNIQUEMENT pour
// roll_both et les valeurs extrêmes (1 et 6). Miroir exact de COMBO_NOTES.
val COMBO_NOTES: Map<Pair<Int, String>, String> = mapOf(
    (1 to "coeur") to "Malgré l'échec, un allié providentiel intervient juste à temps pour éviter le pire.",
    (1 to "question") to "L'échec ouvre malgré tout une piste inattendue à explorer plus tard.",
    (1 to "soleil") to "Malgré l'échec, une lueur d'espoir apparaît : quelque chose de positif se prépare déjà.",
    (1 to "etoile") to "L'échec cache une chance inattendue, presque miraculeuse, qui va bientôt se révéler.",
    (1 to "exclamation") to "Au moment le plus difficile, une aide inattendue arrive à la rescousse.",
    (1 to "spirale") to "L'échec déclenche un véritable tournant : la situation bascule de façon totalement inattendue — un moment charnière de l'histoire.",
    (6 to "coeur") to "La réussite s'accompagne d'un moment de grâce : un lien se renforce ou un petit miracle se produit.",
    (6 to "question") to "Ce moment héroïque ouvre une toute nouvelle piste à explorer.",
    (6 to "soleil") to "La réussite est amplifiée : un bienfait durable en découle.",
    (6 to "etoile") to "Un coup de chance exceptionnel vient couronner cette réussite déjà héroïque.",
    (6 to "exclamation") to "En plus de cette réussite, un allié inattendu vient prêter main forte, rendant le moment encore plus marquant.",
    (6 to "spirale") to "Cette réussite héroïque s'accompagne d'une transformation inattendue : quelque chose change durablement dans l'histoire."
)

// Longueurs d'histoire : conditionnent la mécanique du symbole "question".
const val LENGTH_SHORT = "courte"
const val LENGTH_MEDIUM = "moyenne"
const val LENGTH_LONG = "longue"

// Mode d'une quête secondaire : "parallele" (histoire moyenne) ou "fin_chapitre" (histoire longue).
const val QUEST_MODE_PARALLEL = "parallele"
const val QUEST_MODE_END_OF_CHAPTER = "fin_chapitre"

/** Accepte "courte"/"court"/"short", "longue"/"long", tout le reste -> "moyenne". */
fun normalizeStoryLength(raw: String?): String {
    val s = raw?.trim()?.lowercase().orEmpty()
    return when {
        s.startsWith("court") || s.startsWith("short") -> LENGTH_SHORT
        s.startsWith("long") -> LENGTH_LONG
        else -> LENGTH_MEDIUM
    }
}

/**
 * Symboles de pips de base pour l'histoire ACTIVE. Volontairement une
 * MutableMap au niveau module (jamais réassignée, seulement vidée + remplie
 * via clear()+putAll()), miroir exact du dict PIP_SYMBOLS en Python : peuplée
 * par StoryRegistry côté Kotlin (étape 3) quand on change d'histoire, pour
 * que toute référence existante voie toujours le contenu à jour.
 */
data class PipSymbolInfo(val emoji: String, val label: String)
val PIP_SYMBOLS: MutableMap<String, PipSymbolInfo> = mutableMapOf()
const val DEFAULT_PIP_SYMBOL = ""

/** Symbole fusionné (base ou totem ajouté), miroir du dict renvoyé par all_symbols() en Python. */
data class SymbolInfo(
    val emoji: String,
    val label: String,
    val image: String?,
    val powers: List<String>,
    val special: String,
    val isCustom: Boolean
)

// ------------------------------------------------------------------------
// Structures de données
// ------------------------------------------------------------------------

data class SideQuest(
    val id: Int,
    val kind: String,
    var status: String,
    val mode: String = QUEST_MODE_PARALLEL
) {
    // kind: "ami" | "objet"
    // status: "en_attente" (histoire longue : se lancera à la fin du chapitre) | "ouverte" | "terminee"
    // mode: QUEST_MODE_PARALLEL | QUEST_MODE_END_OF_CHAPTER
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("status", status); put("mode", mode)
    }
    companion object {
        fun fromJson(o: JSONObject): SideQuest? {
            if (!o.has("id")) return null
            val kind = o.optString("kind", null) ?: return null
            val status = o.optString("status", null) ?: return null
            val mode = o.optString("mode", QUEST_MODE_PARALLEL)
            return SideQuest(o.optInt("id"), kind, status, mode)
        }
    }
}

data class CustomTotem(
    val key: String,
    val label: String,
    val emoji: String = "",
    val image: String? = null,
    val powers: List<String> = emptyList(),
    val special: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("label", label); put("emoji", emoji)
        put("image", image ?: JSONObject.NULL)
        put("powers", JSONArray(powers))
        put("special", special)
    }
    companion object {
        fun fromJson(o: JSONObject): CustomTotem? {
            val key = o.optString("key", "").ifEmpty { return null }
            val label = o.optString("label", "").ifEmpty { return null }
            val image = if (o.isNull("image")) null else o.optString("image", null)
            val powersArr = o.optJSONArray("powers")
            val powers = mutableListOf<String>()
            if (powersArr != null) {
                for (i in 0 until powersArr.length()) {
                    val p = powersArr.opt(i)
                    if (p is String) powers.add(p)
                }
            }
            return CustomTotem(
                key = key,
                label = label,
                emoji = o.optString("emoji", ""),
                image = image,
                powers = powers,
                special = o.optString("special", "")
            )
        }
    }
}

data class AiMessage(
    val role: String,
    val content: String,
    // false : message technique (lancer de dés, prompt de mécaniques...), envoyé à l'IA
    // mais jamais montré au joueur dans AiPanel.kt. true par défaut, y compris pour les
    // anciennes sauvegardes (champ absent -> considéré visible).
    val visible: Boolean = true,
    // Si non vide ET visible : texte à afficher à la place de `content` (ex. le message
    // vraiment tapé par le joueur, quand `content` contient aussi la description technique
    // du lancer en attente). Ignoré quand `visible` est faux.
    val displayContent: String = ""
) {
    // role: "system" | "user" | "assistant"
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role); put("content", content)
        put("visible", visible)
        if (displayContent.isNotEmpty()) put("display_content", displayContent)
    }
    companion object {
        fun fromJson(o: JSONObject): AiMessage? {
            val role = o.optString("role", "")
            if (role !in setOf("system", "user", "assistant")) return null
            val content = o.optString("content", "")
            if (content.isBlank()) return null
            return AiMessage(
                role, content,
                visible = o.optBoolean("visible", true),
                displayContent = o.optString("display_content", "")
            )
        }
    }
}

data class RollRecord(
    val id: Int,
    val type: String,              // "success" | "fate" | "both"
    val success: Int?,             // 1..6 ou null
    val fate: String?,             // clé de la face du destin, ou null
    val note: String = "",
    val pipChoice: List<String>? = null,
    val pipMode: String? = null,
    val threatTriggered: Boolean? = null,
    val sideQuest: SideQuest? = null,
    val suddenEvent: Boolean? = null   // histoire courte : ❓ = événement soudain (pas de quête)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("success", success ?: JSONObject.NULL)
        put("fate", fate ?: JSONObject.NULL)
        put("note", note)
        if (pipChoice != null) put("pip_choice", JSONArray(pipChoice))
        if (pipMode != null) put("pip_mode", pipMode)
        if (threatTriggered != null) put("threat_triggered", threatTriggered)
        if (sideQuest != null) put("side_quest", sideQuest.toJson())
        if (suddenEvent != null) put("sudden_event", suddenEvent)
    }
    companion object {
        fun fromJson(o: JSONObject): RollRecord {
            val pipChoiceArr = o.optJSONArray("pip_choice")
            val pipChoice = if (pipChoiceArr != null) {
                (0 until pipChoiceArr.length()).map { pipChoiceArr.optString(it, "") }
            } else null
            val sideQuestObj = o.optJSONObject("side_quest")
            return RollRecord(
                id = o.optInt("id"),
                type = o.optString("type", "success"),
                success = if (o.isNull("success") || !o.has("success")) null else o.optInt("success"),
                fate = if (o.isNull("fate") || !o.has("fate")) null else o.optString("fate"),
                note = o.optString("note", ""),
                pipChoice = pipChoice,
                pipMode = if (o.has("pip_mode")) o.optString("pip_mode") else null,
                threatTriggered = if (o.has("threat_triggered")) o.optBoolean("threat_triggered") else null,
                sideQuest = sideQuestObj?.let { SideQuest.fromJson(it) },
                suddenEvent = if (o.has("sudden_event")) o.optBoolean("sudden_event") else null
            )
        }
    }
}

// ------------------------------------------------------------------------
// DiceSession
// ------------------------------------------------------------------------

class DiceSession(private var saveFile: File) {

    var history: MutableList<RollRecord> = mutableListOf()
        private set
    var nextId: Int = 1
        private set

    var pipSymbol: String = DEFAULT_PIP_SYMBOL
        private set
    // "single" -> un symbole fixe (pipSymbol) sur toutes les faces
    // "random" -> un symbole tiré au sort (parmi enabledSymbols) par lancer
    // "mixed"  -> chaque pip peut avoir un symbole différent
    var pipMode: String = "single"
        private set
    var enabledSymbols: MutableList<String> = mutableListOf()

    var customTotems: MutableList<CustomTotem> = mutableListOf()
        private set

    // Au moins une valeur/un symbole toujours actif (garanti par les toggle_* de l'étape 2).
    var allowedSuccessValues: MutableList<Int> = mutableListOf(1, 2, 3, 4, 5, 6)
    var allowedFateKeys: MutableList<String> = FATE_FACES.map { it.key }.toMutableList()

    var totemEnergy: MutableMap<String, Int> = mutableMapOf()
    var threatLevel: Int = 0
        private set

    var sideQuests: MutableList<SideQuest> = mutableListOf()
        private set
    var nextQuestId: Int = 1
        private set

    /** "courte" | "moyenne" | "longue" : réglage de l'histoire, conservé par resetAll(). */
    var storyLength: String = LENGTH_MEDIUM
        private set

    /** Id d'une quête tout juste lancée en fin de chapitre (histoire longue), à annoncer à l'IA. 0 = rien. */
    private var questToAnnounceId: Int = 0

    var storyLog: MutableList<String> = mutableListOf()
    var aiConversation: MutableList<AiMessage> = mutableListOf()
    var storySummary: String = ""
    var lastAiSentId: Int = 0
    var lastJournalIndex: Int = 0

    init {
        resetState()
    }

    /** Remet tous les champs à leur valeur initiale (utilisé par le constructeur et resetAll()). */
    private fun resetState() {
        history = mutableListOf()
        nextId = 1
        pipSymbol = DEFAULT_PIP_SYMBOL
        pipMode = "single"
        enabledSymbols = PIP_SYMBOLS.keys.toMutableList()
        customTotems = mutableListOf()
        allowedSuccessValues = mutableListOf(1, 2, 3, 4, 5, 6)
        allowedFateKeys = FATE_FACES.map { it.key }.toMutableList()
        totemEnergy = PIP_SYMBOLS.keys.associateWith { 0 }.toMutableMap()
        threatLevel = 0
        sideQuests = mutableListOf()
        nextQuestId = 1
        questToAnnounceId = 0
        storyLog = mutableListOf()
        aiConversation = mutableListOf()
        storySummary = ""
        lastAiSentId = 0
        lastJournalIndex = 0
    }

    // ---------- persistance ----------

    fun save() {
        val data = JSONObject().apply {
            put("history", JSONArray(history.map { it.toJson() }))
            put("next_id", nextId)
            put("pip_symbol", pipSymbol)
            put("pip_mode", pipMode)
            put("enabled_symbols", JSONArray(enabledSymbols))
            put("allowed_success_values", JSONArray(allowedSuccessValues))
            put("allowed_fate_keys", JSONArray(allowedFateKeys))
            put("totem_energy", JSONObject(totemEnergy as Map<*, *>))
            put("threat_level", threatLevel)
            put("side_quests", JSONArray(sideQuests.map { it.toJson() }))
            put("next_quest_id", nextQuestId)
            put("story_length", storyLength)
            put("quest_to_announce_id", questToAnnounceId)
            put("story_log", JSONArray(storyLog))
            put("ai_conversation", JSONArray(aiConversation.map { it.toJson() }))
            put("story_summary", storySummary)
            put("last_ai_sent_id", lastAiSentId)
            put("last_journal_index", lastJournalIndex)
            put("custom_totems", JSONArray(customTotems.map { it.toJson() }))
        }
        saveFile.parentFile?.mkdirs()
        saveFile.writeText(data.toString(2))
    }

    /** @return true si un fichier existait et a été chargé, false sinon (miroir de dice_engine.load()). */
    fun load(): Boolean {
        if (!saveFile.exists()) return false
        val data = JSONObject(saveFile.readText())

        history = (data.optJSONArray("history") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { RollRecord.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }
        nextId = data.optInt("next_id", 1)
        pipSymbol = data.optString("pip_symbol", DEFAULT_PIP_SYMBOL)
        val loadedPipMode = data.optString("pip_mode", "single")
        pipMode = if (loadedPipMode in setOf("single", "random", "mixed")) loadedPipMode else "single"

        // Les totems ajoutés par le joueur sont chargés AVANT enabledSymbols et
        // totemEnergy, car ces deux-là dépendent de allSymbolKeys() (base + ajoutés)
        // pour ne pas les perdre au filtrage — même ordre que dice_engine.load().
        val rawCustom = data.optJSONArray("custom_totems") ?: JSONArray()
        customTotems = mutableListOf()
        for (i in 0 until rawCustom.length()) {
            val t = rawCustom.optJSONObject(i) ?: continue
            val key = t.optString("key", "")
            if (key.isEmpty() || key in PIP_SYMBOLS) continue // ne doit jamais écraser un symbole de base
            CustomTotem.fromJson(t)?.let { customTotems.add(it) }
        }
        val validKeys = allSymbolKeys()

        val enabled = (data.optJSONArray("enabled_symbols") ?: JSONArray())
            .let { arr -> (0 until arr.length()).map { arr.optString(it, "") } }
            .filter { it in validKeys }
        enabledSymbols = (enabled.ifEmpty { validKeys.toList() }).toMutableList()

        val rawAllowed = (data.optJSONArray("allowed_success_values") ?: JSONArray())
            .let { arr -> (0 until arr.length()).map { arr.optInt(it, -1) } }
            .filter { it in 1..6 }
        allowedSuccessValues = (rawAllowed.toSortedSet().toList().ifEmpty { listOf(1, 2, 3, 4, 5, 6) }).toMutableList()

        val rawAllowedFate = (data.optJSONArray("allowed_fate_keys") ?: JSONArray())
            .let { arr -> (0 until arr.length()).map { arr.optString(it, "") } }
            .filter { it in FATE_BY_KEY }
        allowedFateKeys = (rawAllowedFate.ifEmpty { FATE_FACES.map { it.key } }).toMutableList()

        val energyObj = data.optJSONObject("totem_energy") ?: JSONObject()
        totemEnergy = validKeys.associateWith { energyObj.optInt(it, 0) }.toMutableMap()

        threatLevel = maxOf(0, data.optInt("threat_level", 0))

        val questsArr = data.optJSONArray("side_quests") ?: JSONArray()
        sideQuests = (0 until questsArr.length())
            .mapNotNull { SideQuest.fromJson(questsArr.getJSONObject(it)) }
            .toMutableList()
        nextQuestId = data.optInt("next_quest_id", sideQuests.size + 1)
        storyLength = normalizeStoryLength(data.optString("story_length", LENGTH_MEDIUM))
        questToAnnounceId = data.optInt("quest_to_announce_id", 0)

        val storyLogArr = data.optJSONArray("story_log") ?: JSONArray()
        storyLog = (0 until storyLogArr.length())
            .map { storyLogArr.optString(it, "") }
            .filter { it.isNotBlank() }
            .toMutableList()

        val convArr = data.optJSONArray("ai_conversation") ?: JSONArray()
        aiConversation = (0 until convArr.length())
            .mapNotNull { AiMessage.fromJson(convArr.getJSONObject(it)) }
            .toMutableList()

        storySummary = data.optString("story_summary", "").trim()

        val restLen = aiConversation.size - (if (aiConversation.isNotEmpty() && aiConversation[0].role == "system") 1 else 0)
        lastAiSentId = data.optInt("last_ai_sent_id", 0)
        val jidx = data.optInt("last_journal_index", 0)
        lastJournalIndex = maxOf(0, minOf(jidx, restLen))

        return true
    }

    // ---------- totems : lecture seule nécessaire aux lancers (gestion complète = étape 2) ----------

    /** Ensemble de toutes les clés de symboles valides : celles de base (PIP_SYMBOLS) + totems ajoutés. */
    fun allSymbolKeys(): Set<String> = PIP_SYMBOLS.keys + customTotems.map { it.key }.toSet()

    // ---------- pool du dé de réussite ----------

    private fun rollSuccessValue(): Int {
        val pool = allowedSuccessValues.ifEmpty { listOf(1, 2, 3, 4, 5, 6) }
        return pool.random()
    }

    /**
     * Détermine le symbole utilisé pour chaque pip d'une face, selon pipMode.
     * Le nombre de pips vaut toujours `value` (1 à 6). Si aucun symbole n'est
     * encore disponible, on renvoie pipSymbol (potentiellement vide) sans tirage,
     * pour ne jamais planter sur une liste vide — miroir de _resolve_pip_choice.
     */
    private fun resolvePipChoice(value: Int): List<String> {
        val pool = enabledSymbols.ifEmpty { allSymbolKeys().toList() }
        if (pool.isEmpty()) return List(value) { pipSymbol }
        return when (pipMode) {
            "random" -> {
                val symbol = pool.random()
                List(value) { symbol }
            }
            "mixed" -> List(value) { pool.random() }
            else -> List(value) { pipSymbol }
        }
    }

    // ---------- jauge d'énergie totémique ----------

    private fun applyTotemEnergy(value: Int, pipChoice: List<String>, pipMode: String) {
        if (pipMode == "mixed") {
            for (k in pipChoice) {
                if (k.isNotEmpty()) totemEnergy[k] = (totemEnergy[k] ?: 0) + 1
            }
        } else {
            val k = pipChoice.firstOrNull()
            if (!k.isNullOrEmpty()) totemEnergy[k] = (totemEnergy[k] ?: 0) + value
        }
    }

    fun isTotemReady(key: String): Boolean = (totemEnergy[key] ?: 0) >= TOTEM_ENERGY_THRESHOLD

    // ---------- jauge de menace ----------

    /** @return true si le seuil vient d'être atteint (la jauge est alors réinitialisée). */
    private fun applyThreat(value: Int): Boolean {
        if (value == 1) threatLevel += 3
        else if (value >= 5) threatLevel = maxOf(0, threatLevel - 1)
        if (threatLevel >= THREAT_THRESHOLD) {
            threatLevel = 0
            return true
        }
        return false
    }

    // ---------- symbole ❓ : mécanique selon la longueur de l'histoire ----------

    /**
     * Règle la longueur de l'histoire. Accepte "short"/"medium"/"long" (StoryEntry.storyLength)
     * comme "courte"/"moyenne"/"longue". Pas de save() : la source de vérité reste StoryEntry,
     * réappliquée par GameEngine après chaque load(), et la valeur est écrite au prochain save().
     */
    fun setStoryLength(raw: String) {
        storyLength = normalizeStoryLength(raw)
    }

    /**
     * Crée la quête secondaire (histoires moyenne et longue uniquement) :
     *  - moyenne : ouverte tout de suite, à construire en parallèle de l'intrigue principale ;
     *  - longue  : mise "en_attente", elle se lance toute seule à la fin du chapitre (addStoryEntry).
     */
    private fun spawnSideQuest(): SideQuest {
        val kind = listOf("ami", "objet").random()
        val (mode, status) = if (storyLength == LENGTH_LONG) {
            QUEST_MODE_END_OF_CHAPTER to "en_attente"
        } else {
            QUEST_MODE_PARALLEL to "ouverte"
        }
        val quest = SideQuest(nextQuestId, kind, status, mode)
        nextQuestId += 1
        sideQuests.add(quest)
        return quest
    }

    fun openSideQuests(): List<SideQuest> = sideQuests.filter { it.status == "ouverte" }

    /** Quêtes d'histoire longue qui attendent la fin du chapitre pour se lancer. */
    fun pendingSideQuests(): List<SideQuest> = sideQuests.filter { it.status == "en_attente" }

    fun completeSideQuest(questId: Int): Boolean {
        val quest = sideQuests.find { it.id == questId } ?: return false
        quest.status = "terminee"
        save()
        return true
    }

    /**
     * Fin de chapitre (histoire longue) : la quête en attente devient ouverte.
     * @param announce true -> une consigne de lancement sera à transmettre à l'IA
     *   (consumeQuestAnnouncement) ; false -> l'IA vient déjà de lancer la quête elle-même.
     * Sans effet (false) s'il n'y a aucune quête en attente. Ne sauvegarde pas : l'appelant s'en charge.
     */
    fun launchPendingQuest(announce: Boolean = false): Boolean {
        val next = sideQuests.firstOrNull { it.status == "en_attente" } ?: return false
        next.status = "ouverte"
        if (announce) questToAnnounceId = next.id
        return true
    }

    /**
     * À appeler par GameEngine au moment de construire le prochain message envoyé à l'IA :
     * renvoie (une seule fois) la consigne de lancement de la quête courte déclenchée
     * par la fin de chapitre, ou null s'il n'y en a pas.
     */
    fun consumeQuestAnnouncement(): String? {
        if (questToAnnounceId == 0) return null
        val quest = sideQuests.find { it.id == questToAnnounceId }
        questToAnnounceId = 0
        save()
        if (quest == null || quest.status != "ouverte") return null
        val kindTxt = if (quest.kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou totem)"
        return "\uD83D\uDCDC Fin de chapitre : une quête secondaire COURTE se lance maintenant ($kindTxt). " +
            "Ouvre le nouveau chapitre en la lançant naturellement, avec un objectif simple et clair, " +
            "à résoudre en quelques échanges seulement, puis reprends le fil de l'histoire principale."
    }

    /** Description de la face du destin, adaptée à la longueur de l'histoire pour le symbole ❓. */
    fun fateDescription(face: FateFace): String {
        if (face.key != "question") return face.desc
        return when (storyLength) {
            LENGTH_SHORT ->
                "Événement soudain et inattendu : quelque chose surgit brusquement dans la scène en cours " +
                    "(rencontre, découverte, imprévu...) et doit être géré tout de suite. " +
                    "Aucune quête annexe n'est ouverte."
            LENGTH_LONG ->
                "Une quête secondaire courte est mise de côté : elle se lancera automatiquement à la fin " +
                    "du chapitre en cours. Ne la développe pas dans la scène actuelle."
            else ->
                "Une quête secondaire naît et se construit en parallèle de l'histoire principale : " +
                    "entrelace-la à l'intrigue, en distillant indices et étapes au fil des scènes " +
                    "(l'occasion de se faire un nouvel ami ou de trouver un nouvel objet, voire un totem)."
        }
    }

    /** Note de combo (réussite + destin) ; pour ❓ elle dépend de la longueur de l'histoire. */
    fun comboNote(success: Int, fateKey: String): String? {
        if (fateKey != "question") return COMBO_NOTES[success to fateKey]
        val failure = success == 1
        return when (storyLength) {
            LENGTH_SHORT ->
                if (failure) "L'échec provoque un événement soudain et inattendu qui bouscule la scène en cours."
                else "Ce moment héroïque déclenche un événement soudain et inattendu dans la scène en cours."
            LENGTH_LONG ->
                if (failure) "L'échec cache une piste inattendue : elle sera explorée à la fin du chapitre."
                else "Ce moment héroïque ouvre une piste qui sera explorée à la fin du chapitre."
            else ->
                if (failure) "L'échec ouvre malgré tout une piste inattendue, à tisser avec l'intrigue principale."
                else "Ce moment héroïque ouvre une nouvelle piste, à tisser avec l'intrigue principale."
        }
    }

    /**
     * Pool de tirage du dé du destin : symboles cochés (allowedFateKeys), moins
     * "question" si une quête secondaire est déjà ouverte ou en attente (histoires
     * moyenne et longue) — sauf si ça viderait complètement le pool, auquel cas
     * "question" reste exceptionnellement permis. En histoire courte, ❓ est un
     * événement instantané : il n'est jamais retiré du pool.
     */
    private fun fatePool(): List<FateFace> {
        val allowed = allowedFateKeys.ifEmpty { FATE_FACES.map { it.key } }
        val pool = FATE_FACES.filter { it.key in allowed }
        if (storyLength != LENGTH_SHORT && (openSideQuests().isNotEmpty() || pendingSideQuests().isNotEmpty())) {
            val poolWoQuestion = pool.filter { it.key != "question" }
            if (poolWoQuestion.isNotEmpty()) return poolWoQuestion
        }
        return pool
    }

    // ---------- lancers ----------

    fun rollSuccess(note: String = ""): RollRecord {
        val value = rollSuccessValue()
        val pipChoice = resolvePipChoice(value)
        applyTotemEnergy(value, pipChoice, pipMode)
        val threatTriggered = applyThreat(value)
        val record = RollRecord(
            id = nextId, type = "success", success = value, fate = null, note = note,
            pipChoice = pipChoice, pipMode = pipMode, threatTriggered = threatTriggered
        )
        nextId += 1
        history.add(record)
        save()
        return record
    }

    fun rollFate(note: String = ""): RollRecord {
        val face = fatePool().random()
        val isQuestion = face.key == "question"
        val sideQuest = if (isQuestion && storyLength != LENGTH_SHORT) spawnSideQuest() else null
        val suddenEvent = if (isQuestion && storyLength == LENGTH_SHORT) true else null
        val record = RollRecord(
            id = nextId, type = "fate", success = null, fate = face.key, note = note,
            sideQuest = sideQuest, suddenEvent = suddenEvent
        )
        nextId += 1
        history.add(record)
        save()
        return record
    }

    fun rollBoth(note: String = ""): RollRecord {
        val value = rollSuccessValue()
        val face = fatePool().random()
        val pipChoice = resolvePipChoice(value)
        applyTotemEnergy(value, pipChoice, pipMode)
        val threatTriggered = applyThreat(value)
        val isQuestion = face.key == "question"
        val sideQuest = if (isQuestion && storyLength != LENGTH_SHORT) spawnSideQuest() else null
        val suddenEvent = if (isQuestion && storyLength == LENGTH_SHORT) true else null
        val record = RollRecord(
            id = nextId, type = "both", success = value, fate = face.key, note = note,
            pipChoice = pipChoice, pipMode = pipMode,
            threatTriggered = threatTriggered, sideQuest = sideQuest, suddenEvent = suddenEvent
        )
        nextId += 1
        history.add(record)
        save()
        return record
    }

    /**
     * Relance immédiate (pouvoir "Second Souffle" d'un totem, identifié par sa
     * clé exacte "patte" ou "baguette" côté appelant — étape 2/GameViewModel) :
     * retire le dernier lancer de réussite (ou mixte) de l'historique et
     * relance immédiatement le dé de réussite à sa place.
     */
    fun secondSouffle(note: String = "Second Souffle"): RollRecord {
        for (i in history.indices.reversed()) {
            if (history[i].type == "success" || history[i].type == "both") {
                history.removeAt(i)
                break
            }
        }
        return rollSuccess(note)
    }

    fun undoLast(): RollRecord? {
        if (history.isEmpty()) return null
        val record = history.removeAt(history.size - 1)
        save()
        return record
    }

    fun clearHistory() {
        history = mutableListOf()
        nextId = 1
        save()
    }

    /**
     * Remet TOUT à zéro : historique, jauges, menace, quêtes, valeurs autorisées,
     * symbole/mode choisis, conversation IA, totems personnalisés. Utilise le
     * roster de base (PIP_SYMBOLS) actuellement chargé par l'histoire active —
     * ne change pas d'histoire en cours de route.
     */
    fun resetAll() {
        resetState()
        save()
    }

    // ---------- affichage ----------

    fun describeRecord(record: RollRecord): String {
        val parts = mutableListOf<String>()
        if (record.success != null) {
            parts.add("Dé de réussite : ${record.success} (${SUCCESS_LABELS[record.success]})")
        }
        if (record.fate != null) {
            val face = FATE_BY_KEY.getValue(record.fate)
            parts.add("Dé du destin : ${face.emoji} ${face.label} - ${fateDescription(face)}")
        }
        if (record.threatTriggered == true) {
            parts.add("\u26A0\uFE0F La jauge de menace explose : une complication secondaire inattendue survient !")
        }
        if (record.suddenEvent == true) {
            parts.add("\u26A1 Événement soudain et inattendu : il surgit MAINTENANT dans la scène en cours (pas de quête annexe à ouvrir).")
        }
        record.sideQuest?.let { sq ->
            val kindTxt = if (sq.kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou totem)"
            if (sq.mode == QUEST_MODE_END_OF_CHAPTER) {
                if (sq.status == "en_attente") {
                    parts.add("\uD83D\uDCDC Quête secondaire courte ($kindTxt) : elle se lancera automatiquement à la fin du chapitre — ne pas la développer maintenant.")
                } else {
                    parts.add("\uD83D\uDCDC Quête secondaire courte lancée : $kindTxt.")
                }
            } else {
                parts.add("\uD83D\uDCDC Nouvelle quête secondaire ($kindTxt) : à construire en parallèle de l'histoire principale, en l'entrelaçant à l'intrigue au fil des scènes.")
            }
        }
        if (record.type == "both" && record.success != null && record.fate != null) {
            comboNote(record.success, record.fate)?.let { combo ->
                parts.add("\u2728 Coïncidence marquante : $combo")
            }
        }
        return parts.joinToString("\n")
    }

    fun historyText(): String {
        if (history.isEmpty()) return "(aucun lancer pour l'instant)"
        return history.joinToString("\n") { r ->
            var prefix = "#${r.id}"
            if (r.note.isNotEmpty()) prefix += " [${r.note}]"
            val bits = mutableListOf<String>()
            if (r.success != null) bits.add("Réussite=${r.success}")
            if (r.fate != null) {
                val face = FATE_BY_KEY.getValue(r.fate)
                bits.add("Destin=${face.emoji} ${face.label}")
            }
            "$prefix : " + bits.joinToString(" | ")
        }
    }

    // ------------------------------------------------------------------------
    // ÉTAPE 2 — gestion des symboles / totems / contraintes de tirage
    // ------------------------------------------------------------------------

    /**
     * Fusionne les symboles de base et les totems ajoutés par le joueur en un
     * seul Map {clé -> SymbolInfo}, utilisé partout où l'appli doit afficher ou
     * proposer TOUS les symboles disponibles (choix du pip, jauges totémiques,
     * contexte envoyé à l'IA...). Miroir exact de all_symbols().
     */
    fun allSymbols(): Map<String, SymbolInfo> {
        val merged = LinkedHashMap<String, SymbolInfo>()
        for ((k, v) in PIP_SYMBOLS) {
            merged[k] = SymbolInfo(
                emoji = v.emoji, label = v.label, image = null,
                powers = emptyList(), special = "", isCustom = false
            )
        }
        for (t in customTotems) {
            merged[t.key] = SymbolInfo(
                emoji = t.emoji.ifEmpty { "\uD83D\uDC3E" }, // 🐾, miroir de "\U0001F43E"
                label = t.label,
                image = t.image,
                powers = t.powers,
                special = t.special,
                isCustom = true
            )
        }
        return merged
    }

    private fun slugifyTotemKey(label: String): String {
        var base = label.map { c -> if (c.isLetterOrDigit()) c.lowercaseChar() else '_' }.joinToString("")
        base = base.trim('_')
        while ("__" in base) base = base.replace("__", "_")
        if (base.isEmpty()) base = "totem"
        var key = base
        val existing = allSymbolKeys()
        var n = 2
        while (key in existing) {
            key = "${base}_$n"
            n += 1
        }
        return key
    }

    /**
     * Ajoute un nouveau totem/allié en cours de partie : nouvelle entrée dans le
     * sélecteur de symboles, nouvelle jauge d'énergie (à 0), actif par défaut.
     * Renvoie la clé attribuée, ou null si le nom est vide. Miroir de add_custom_totem.
     */
    fun addCustomTotem(
        label: String,
        powersText: String = "",
        special: String = "",
        emoji: String = "",
        imageFilename: String? = null
    ): String? {
        val trimmedLabel = label.trim()
        if (trimmedLabel.isEmpty()) return null
        val key = slugifyTotemKey(trimmedLabel)
        val powers = powersText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        customTotems.add(
            CustomTotem(
                key = key, label = trimmedLabel, emoji = emoji.trim(),
                image = imageFilename, powers = powers, special = special.trim()
            )
        )
        if (key !in enabledSymbols) enabledSymbols.add(key)
        totemEnergy[key] = 0
        save()
        return key
    }

    /**
     * Retire un totem ajouté par le joueur (jauge et image comprises — le
     * fichier image lui-même doit être supprimé côté appelant). Sans effet sur
     * les symboles de base (PIP_SYMBOLS). Miroir de remove_custom_totem.
     */
    fun removeCustomTotem(key: String): Boolean {
        val before = customTotems.size
        customTotems = customTotems.filter { it.key != key }.toMutableList()
        if (customTotems.size == before) return false
        if (key in enabledSymbols && enabledSymbols.size > 1) enabledSymbols.remove(key)
        totemEnergy.remove(key)
        save()
        return true
    }

    /** Coche/décoche un symbole dans le pool des modes aléatoire/mixte. Au moins un reste toujours actif. */
    fun toggleEnabledSymbol(key: String): Boolean {
        if (key !in allSymbolKeys()) return false
        if (key in enabledSymbols) {
            if (enabledSymbols.size > 1) enabledSymbols.remove(key)
        } else {
            enabledSymbols.add(key)
        }
        save()
        return true
    }

    fun setPipSymbol(key: String): Boolean {
        if (key in allSymbolKeys()) {
            pipSymbol = key
            pipMode = "single"
            save()
            return true
        }
        return false
    }

    /**
     * Active le mode "aléatoire" ou "mixte". Réactive au passage toutes les
     * constellations (l'utilisateur peut ensuite en décocher). Si le mode
     * demandé est déjà actif, un second appel le désactive et revient au choix
     * d'une constellation simple. Miroir de set_pip_mode.
     */
    fun setPipMode(mode: String): Boolean {
        if (mode !in setOf("random", "mixed")) return false
        if (pipMode == mode) {
            pipMode = "single"
        } else {
            pipMode = mode
            enabledSymbols = allSymbolKeys().toMutableList()
        }
        save()
        return true
    }

    /** Coche/décoche une valeur (1-6) du dé de réussite. Au moins une reste toujours active. */
    fun toggleAllowedValue(value: Int): Boolean {
        if (value !in 1..6) return false
        if (value in allowedSuccessValues) {
            if (allowedSuccessValues.size > 1) allowedSuccessValues.remove(value)
        } else {
            allowedSuccessValues.add(value)
            allowedSuccessValues.sort()
        }
        save()
        return true
    }

    /** Réactive les 6 valeurs (désactive toute contrainte). */
    fun resetAllowedValues() {
        allowedSuccessValues = mutableListOf(1, 2, 3, 4, 5, 6)
        save()
    }

    /** Coche/décoche un symbole du dé du destin. Au moins un reste toujours actif. */
    fun toggleAllowedFate(key: String): Boolean {
        if (key !in FATE_BY_KEY) return false
        if (key in allowedFateKeys) {
            if (allowedFateKeys.size > 1) allowedFateKeys.remove(key)
        } else {
            allowedFateKeys.add(key)
        }
        save()
        return true
    }

    /** Réactive les 6 symboles du dé du destin (désactive toute contrainte). */
    fun resetAllowedFate() {
        allowedFateKeys = FATE_FACES.map { it.key }.toMutableList()
        save()
    }

    /**
     * Consomme la jauge d'un totem/allié si elle est pleine. Renvoie true si
     * elle a bien été dépensée (l'effet peut alors être déclenché côté
     * appelant), false si elle n'était pas encore prête.
     */
    fun spendTotemEnergy(key: String): Boolean {
        if (key !in allSymbolKeys() || !isTotemReady(key)) return false
        totemEnergy[key] = 0
        save()
        return true
    }

    // ------------------------------------------------------------------------
    // ÉTAPE 2 — journal de l'histoire
    // ------------------------------------------------------------------------

    /**
     * Chapitre ajouté par le joueur (résumé collé à la fin d'un chapitre) : c'est une vraie fin
     * de chapitre, donc la quête courte en attente (histoire longue) se lance et sera annoncée à l'IA.
     * Les "chapitres" produits automatiquement par le digest (applyStoryDigest) ne comptent PAS :
     * ce sont de simples tranches de journal, pas des fins de chapitre narratives.
     */
    fun addStoryEntry(text: String): Boolean {
        if (!appendStoryEntry(text)) return false
        launchPendingQuest(announce = true)
        save()
        return true
    }

    private fun appendStoryEntry(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        storyLog.add(trimmed)
        save()
        return true
    }

    fun storyLogText(): String {
        if (storyLog.isEmpty()) return ""
        return storyLog.mapIndexed { i, entry -> "--- Chapitre ${i + 1} ---\n$entry" }
            .joinToString("\n\n")
    }

    // ------------------------------------------------------------------------
    // ÉTAPE 2 — narration automatique (IA)
    // ------------------------------------------------------------------------

    /**
     * @param visible false pour un message technique (lancer de dés, prompt de mécaniques...) :
     *   toujours transmis à l'IA et conservé dans la sauvegarde, jamais affiché dans AiPanel.kt.
     * @param displayContent si non vide et `visible` est vrai : ce qui est montré au joueur à la
     *   place de `content` (ex. seulement le texte tapé par le joueur, quand `content` contient
     *   aussi la description technique d'un lancer de dés en attente).
     */
    fun addAiMessage(role: String, content: String, visible: Boolean = true, displayContent: String = "") {
        if (role !in setOf("system", "user", "assistant")) return
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        aiConversation.add(AiMessage(role, trimmed, visible, displayContent.trim()))
        save()
    }

    /**
     * Efface uniquement la conversation avec l'IA narratrice (elle sera
     * régénérée, mécaniques comprises, au prochain lancer) -- le reste de la
     * partie (jauges, quêtes, historique des dés...) n'est pas touché.
     */
    fun resetAiConversation() {
        aiConversation = mutableListOf()
        storySummary = ""
        lastJournalIndex = 0
        lastAiSentId = 0
        save()
    }

    /** aiConversation sans le message "system" initial (s'il existe). Miroir de _rest_conversation. */
    private fun restConversation(): List<AiMessage> {
        return if (aiConversation.isNotEmpty() && aiConversation[0].role == "system") {
            aiConversation.drop(1)
        } else {
            aiConversation
        }
    }

    /**
     * Messages (hors message system) pas encore intégrés à une "digestion"
     * (chapitre de journal + résumé long terme mis à jour, produits en un seul
     * appel IA). Miroir de pending_digest_messages.
     */
    fun pendingDigestMessages(): List<AiMessage> = restConversation().drop(lastJournalIndex)

    /**
     * Enregistre en une fois le résultat d'une digestion : le chapitre de
     * journal (storyLog) ET le résumé long terme mis à jour (déjà fusionné
     * ancien+nouveau par l'appelant), puis avance le curseur partagé d'autant
     * de messages consommés, pour ne jamais re-traiter la même tranche.
     */
    fun applyStoryDigest(chapterText: String, updatedSummary: String, messagesConsumed: Int): Boolean {
        val added = appendStoryEntry(chapterText)
        if (!added) return false
        storySummary = updatedSummary.trim()
        lastJournalIndex += messagesConsumed
        save()
        return true
    }

    /** À appeler juste après avoir transmis le dernier lancer à l'IA, pour ne pas le renvoyer deux fois. */
    fun markLastRollAsSent() {
        if (history.isNotEmpty()) {
            lastAiSentId = history.last().id
            save()
        }
    }

    /** Le dernier lancer effectué s'il n'a pas encore été transmis à l'IA narratrice, sinon null. */
    fun pendingRoll(): RollRecord? {
        if (history.isEmpty()) return null
        val last = history.last()
        if (last.id <= lastAiSentId) return null
        return last
    }

    /**
     * Messages à effectivement transmettre à l'API : le message système
     * (mécaniques du jeu, toujours conservé) + le résumé long terme s'il existe
     * + une fenêtre récente de l'échange, pour rester sous la limite de contexte
     * du modèle. L'intégralité de la conversation reste conservée dans
     * aiConversation (et dans la sauvegarde) pour l'affichage.
     */
    fun aiMessagesToSend(): List<AiMessage> {
        if (aiConversation.isEmpty()) return emptyList()
        val head = if (aiConversation[0].role == "system") listOf(aiConversation[0]) else emptyList()
        val window = restConversation().takeLast(AI_HISTORY_WINDOW)
        if (storySummary.isNotEmpty()) {
            val summaryMsg = AiMessage(
                "system",
                "Résumé de l'histoire avant les échanges récents ci-dessous (personnages, " +
                    "objets/totems, lieux, quêtes, événements marquants à ne pas oublier) :\n" +
                    storySummary
            )
            return head + listOf(summaryMsg) + window
        }
        return head + window
    }

    // ------------------------------------------------------------------------
    // ÉTAPE 2 — import d'identité (quêtes + journal)
    // ------------------------------------------------------------------------

    /**
     * Restaure quêtes secondaires et journal à partir d'une identité importée,
     * avec la même validation que load(), pour ne jamais injecter de données
     * malformées dans la session en cours. Chaque paramètre est optionnel et
     * laisse la session inchangée sur ce point si absent (null) ; ne touche
     * jamais aux totems, aux dés ou à la menace. Miroir de import_progress.
     */
    fun importProgress(
        sideQuests: JSONArray? = null,
        nextQuestId: Int? = null,
        storyLog: JSONArray? = null,
        storySummary: String? = null
    ) {
        if (sideQuests != null) {
            val quests = (0 until sideQuests.length())
                .mapNotNull { sideQuests.optJSONObject(it) }
                .mapNotNull { SideQuest.fromJson(it) }
            this.sideQuests = quests.toMutableList()
            this.nextQuestId = nextQuestId ?: ((quests.maxOfOrNull { it.id } ?: 0) + 1)
        }
        if (storyLog != null) {
            this.storyLog = (0 until storyLog.length())
                .map { storyLog.optString(it, "") }
                .filter { it.isNotBlank() }
                .toMutableList()
        }
        if (storySummary != null) {
            this.storySummary = storySummary.trim()
        }
        save()
    }
}
