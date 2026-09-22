// DiceSession.kt
// Portage Kotlin de dice_engine.py — ÉTAPE 1 : cœur (dés, historique, sauvegarde)
//
// Ce que ce fichier couvre déjà (étape 1) :
//   - constantes, libellés, faces du destin, combos narratifs
//   - roll_success / roll_fate / roll_both / second_souffle / undo_last / clear_history / reset_all
//   - save() / load() avec EXACTEMENT les mêmes clés JSON que dice_state_<slug>.json
//   - describe_record() / history_text()
//   - la plomberie interne nécessaire aux lancers (jauge totem, jauge de menace,
//     choix des pips, pool du destin, création de quête secondaire) : ce n'est
//     pas de la "gestion" de totems/quêtes à proprement parler (ça, c'est l'étape 2),
//     mais roll_success/roll_both ne peuvent pas fonctionner sans.
//
// Ce qui reste pour l'ÉTAPE 2 (volontairement non traduit ici, cf. plan) :
//   spend_totem_energy, add_custom_totem, remove_custom_totem, complete_side_quest,
//   pending_digest_messages/apply_story_digest, reset_ai_conversation, ai_messages_to_send,
//   mark_last_roll_as_sent, pending_roll, import_progress, tous les toggle_*,
//   set_pip_symbol, set_pip_mode, add_story_entry, story_log_text, add_ai_message, all_symbols().
//   Ces méthodes sont listées en TODO en bas de la classe pour mémoire.
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
        "Une quête secondaire se débloque, à faire quand on veut : l'occasion de se faire " +
            "un nouvel ami, ou de trouver un nouvel objet (voire un nouveau totem)."
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

// ------------------------------------------------------------------------
// Structures de données
// ------------------------------------------------------------------------

data class SideQuest(val id: Int, val kind: String, var status: String) {
    // kind: "ami" | "objet" — status: "ouverte" | "terminee"
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("status", status)
    }
    companion object {
        fun fromJson(o: JSONObject): SideQuest? {
            if (!o.has("id")) return null
            val kind = o.optString("kind", null) ?: return null
            val status = o.optString("status", null) ?: return null
            return SideQuest(o.optInt("id"), kind, status)
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

data class AiMessage(val role: String, val content: String) {
    // role: "system" | "user" | "assistant"
    fun toJson(): JSONObject = JSONObject().apply { put("role", role); put("content", content) }
    companion object {
        fun fromJson(o: JSONObject): AiMessage? {
            val role = o.optString("role", "")
            if (role !in setOf("system", "user", "assistant")) return null
            val content = o.optString("content", "")
            if (content.isBlank()) return null
            return AiMessage(role, content)
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
    val sideQuest: SideQuest? = null
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
                sideQuest = sideQuestObj?.let { SideQuest.fromJson(it) }
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

    // ---------- quêtes secondaires (?) : création automatique uniquement ----------

    private fun spawnSideQuest(): SideQuest {
        val kind = listOf("ami", "objet").random()
        val quest = SideQuest(nextQuestId, kind, "ouverte")
        nextQuestId += 1
        sideQuests.add(quest)
        return quest
    }

    fun openSideQuests(): List<SideQuest> = sideQuests.filter { it.status == "ouverte" }

    /**
     * Pool de tirage du dé du destin : symboles cochés (allowedFateKeys), moins
     * "question" si une quête secondaire est déjà ouverte — sauf si ça viderait
     * complètement le pool, auquel cas "question" reste exceptionnellement permis.
     */
    private fun fatePool(): List<FateFace> {
        val allowed = allowedFateKeys.ifEmpty { FATE_FACES.map { it.key } }
        val pool = FATE_FACES.filter { it.key in allowed }
        if (openSideQuests().isNotEmpty()) {
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
        val sideQuest = if (face.key == "question") spawnSideQuest() else null
        val record = RollRecord(
            id = nextId, type = "fate", success = null, fate = face.key, note = note,
            sideQuest = sideQuest
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
        val sideQuest = if (face.key == "question") spawnSideQuest() else null
        val record = RollRecord(
            id = nextId, type = "both", success = value, fate = face.key, note = note,
            pipChoice = pipChoice, pipMode = pipMode,
            threatTriggered = threatTriggered, sideQuest = sideQuest
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
            parts.add("Dé du destin : ${face.emoji} ${face.label} - ${face.desc}")
        }
        if (record.threatTriggered == true) {
            parts.add("\u26A0\uFE0F La jauge de menace explose : une complication secondaire inattendue survient !")
        }
        record.sideQuest?.let { sq ->
            val kindTxt = if (sq.kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou totem)"
            parts.add("\uD83D\uDCDC Nouvelle quête secondaire disponible : $kindTxt.")
        }
        if (record.type == "both" && record.success != null && record.fate != null) {
            COMBO_NOTES[record.success to record.fate]?.let { combo ->
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

    // ------------------------------------------------------------------
    // TODO ÉTAPE 2 — pas encore portées ici, à ajouter sur cette même classe :
    //   toggleEnabledSymbol, setPipSymbol, setPipMode,
    //   addCustomTotem, removeCustomTotem, allSymbols(),
    //   toggleAllowedValue/resetAllowedValues, toggleAllowedFate/resetAllowedFate,
    //   spendTotemEnergy,
    //   addStoryEntry/storyLogText,
    //   addAiMessage, resetAiConversation, pendingDigestMessages, applyStoryDigest,
    //   markLastRollAsSent, pendingRoll, aiMessagesToSend,
    //   importProgress.
    // ------------------------------------------------------------------
}
