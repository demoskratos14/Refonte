// DiceSessionModels.kt
// Constantes, tables de référence et classes de données de la session de dés.
// Extrait de DiceSession.kt (déplacement pur : même package, mêmes noms,
// mêmes clés JSON). La classe DiceSession reste dans DiceSession.kt.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject

/** Valeur texte d'une clé JSON, ou null si la clé est absente ou vaut null. */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

// ------------------------------------------------------------------------
// Constantes
// ------------------------------------------------------------------------

const val TOTEM_ENERGY_THRESHOLD = 15
const val THREAT_THRESHOLD = 10
const val AI_HISTORY_WINDOW = 24

val SUCCESS_LABELS: Map<Int, String> = mapOf(
    1 to "Échec critique / conséquence importante (sa gravité suit la section ENJEUX ET ÉCHECS du contexte)",
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
// roll_both et les valeurs extrêmes (1 et 6)..
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
 * via clear()+putAll()) : peuplée par GameEngine à partir de
 * StoryRegistry quand on change d'histoire, pour
 * que toute référence existante voie toujours le contenu à jour.
 */
data class PipSymbolInfo(val emoji: String, val label: String)
val PIP_SYMBOLS: MutableMap<String, PipSymbolInfo> = mutableMapOf()
const val DEFAULT_PIP_SYMBOL = ""

/** Symbole fusionné (base ou totem ajouté), tel que renvoyé par allSymbols(). */
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
            val kind = o.optStringOrNull("kind") ?: return null
            val status = o.optStringOrNull("status") ?: return null
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
            val image = o.optStringOrNull("image")
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

    /** Sauvegarde locale : inclut visible/display_content, propres à l'appli. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role); put("content", content)
        put("visible", visible)
        if (displayContent.isNotEmpty()) put("display_content", displayContent)
    }

    /**
     * Requête envoyée à l'API Mistral (MistralClient.chat) : rôle + contenu seulement.
     * L'API rejette (422 "Extra inputs are not permitted") tout champ qu'elle ne connaît
     * pas -- visible/display_content ne concernent que l'affichage côté appli et ne
     * doivent jamais partir dans cette requête.
     */
    fun toApiJson(): JSONObject = JSONObject().apply {
        put("role", role); put("content", content)
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
