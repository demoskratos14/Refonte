// ClassicDiceEngine.kt
// Dés classiques (page « Des classiques ») : lancer d'un plateau de dés au choix
// (dé du destin facultatif), historique des 30 derniers lancers, lecture des
// anciennes sauvegardes. Page indépendante des histoires : un seul fichier
// d'état, classic_dice_state.json, dans le dossier de données.
//
// Extrait de GameEngine.kt ; GameEngine garde les mêmes méthodes publiques
// (classicDiceState/classicDiceRollMany/classicDiceRoll/classicDiceClear) et
// leur délègue simplement l'appel.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

class ClassicDiceEngine(baseDir: File) {

    private val MIN_CLASSIC_FACES = 2
    private val MAX_CLASSIC_FACES = 999
    private val MAX_CLASSIC_DICE = 8
    /** Valeur qui désigne le dé du destin dans la liste des dés demandée à classicDiceRollMany(). */
    private val CLASSIC_FATE_DIE = 0

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
     * Enrichit chaque résultat avec l'emoji/libellé du destin, et convertit à la volée les
     * anciennes entrées (champs success / faces / fate) au format actuel (liste `results`).
     */
    private fun classicDiceToJson(state: JSONObject): JSONObject {
        val rawHistory = state.optJSONArray("history") ?: JSONArray()
        val history = JSONArray()
        for (i in 0 until rawHistory.length()) {
            val entry = rawHistory.optJSONObject(i) ?: continue
            val results = JSONArray()
            val rawResults = entry.optJSONArray("results")
            if (rawResults != null) {
                for (j in 0 until rawResults.length()) {
                    val r = rawResults.optJSONObject(j) ?: continue
                    val item = JSONObject(r.toString())
                    if (r.optString("type") == "fate") {
                        FATE_BY_KEY[r.optString("fate", "")]?.let {
                            item.put("fate_emoji", it.emoji)
                            item.put("fate_label", it.label)
                        }
                    }
                    results.put(item)
                }
            } else {
                // Ancien format : un dé de réussite (facultatif) puis un dé du destin (facultatif).
                if (entry.has("success") && !entry.isNull("success")) {
                    results.put(JSONObject().apply {
                        put("type", "success")
                        put("faces", if (entry.isNull("faces")) 6 else entry.optInt("faces", 6))
                        put("value", entry.optInt("success"))
                    })
                }
                val fateKey = if (entry.isNull("fate")) "" else entry.optString("fate", "")
                val face = FATE_BY_KEY[fateKey]
                if (face != null) {
                    results.put(JSONObject().apply {
                        put("type", "fate")
                        put("fate", fateKey)
                        put("fate_emoji", face.emoji)
                        put("fate_label", face.label)
                    })
                }
            }
            history.put(JSONObject().apply {
                put("id", entry.optInt("id"))
                put("results", results)
            })
        }
        return JSONObject().apply {
            put("history", history)
            put("next_id", state.optInt("next_id", 1))
        }
    }

    fun classicDiceState(): JSONObject = classicDiceToJson(loadClassicDiceState())

    /**
     * Lance exactement les dés demandés, dans l'ordre.
     * @param dice un élément par dé : CLASSIC_FATE_DIE (0) = dé du destin, sinon le nombre de
     *   faces (6 = dé classique), borné à MIN_CLASSIC_FACES..MAX_CLASSIC_FACES. Chaque résultat
     *   mémorise ses faces pour s'afficher avec le bon dé ("d20 = 17") dans l'historique.
     */
    fun classicDiceRollMany(dice: List<Int>): JSONObject {
        val state = loadClassicDiceState()
        val history = state.optJSONArray("history") ?: JSONArray().also { state.put("history", it) }
        val nextId = state.optInt("next_id", 1)

        val results = JSONArray()
        for (d in dice.take(MAX_CLASSIC_DICE)) {
            if (d == CLASSIC_FATE_DIE) {
                results.put(JSONObject().apply {
                    put("type", "fate")
                    put("fate", classicFateKeys.random())
                })
            } else {
                val nFaces = d.coerceIn(MIN_CLASSIC_FACES, MAX_CLASSIC_FACES)
                results.put(JSONObject().apply {
                    put("type", "success")
                    put("faces", nFaces)
                    put("value", Random.nextInt(1, nFaces + 1))
                })
            }
        }
        if (results.length() == 0) return classicDiceToJson(state)

        history.put(JSONObject().apply {
            put("id", nextId)
            put("results", results)
        })
        state.put("next_id", nextId + 1)

        // Ne garde que les 30 derniers lancers.
        val trimmed = JSONArray()
        val start = maxOf(0, history.length() - 30)
        for (i in start until history.length()) trimmed.put(history.get(i))
        state.put("history", trimmed)

        saveClassicDiceState(state)
        return classicDiceToJson(state)
    }

    /** Ancienne API (kind = "success" / "fate" / "both") : conservée, traduite en liste de dés. */
    fun classicDiceRoll(kind: String, faces: Int = 6): JSONObject = classicDiceRollMany(
        when (kind) {
            "success" -> listOf(faces)
            "fate" -> listOf(CLASSIC_FATE_DIE)
            else -> listOf(faces, CLASSIC_FATE_DIE)
        }
    )

    fun classicDiceClear(): JSONObject {
        val state = JSONObject().apply { put("history", JSONArray()); put("next_id", 1) }
        saveClassicDiceState(state)
        return classicDiceToJson(state)
    }
}
