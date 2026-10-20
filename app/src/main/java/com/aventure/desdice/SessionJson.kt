// SessionJson.kt
// Construction de l'état de session envoyé à Compose : historique, symboles, totems
// (avec la fiche de chaque totem cliquable), quêtes, journal, conversation IA, jauge de
// menace, dernier résultat, titre de l'histoire. Les clés du JSON sont lues par
// GameViewModel.kt (voir aussi MainGameScreen.kt).
//
// Extrait de GameEngine.kt. Code pur : il lit la session et l'histoire qu'on lui passe,
// ne modifie rien. GameEngine.sessionToJson() / currentSessionJson() lui délèguent l'appel.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject

object SessionJson {

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

    fun build(sess: DiceSession, currentStory: StoryEntry?): JSONObject {
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
            put("player_age", currentStory?.playerAge ?: PlayerAge.UNKNOWN)
            put(
                "header_title",
                currentStory?.headerTitle?.ifEmpty { "Les Dés de l'Aventure" } ?: "Les Dés de l'Aventure"
            )
            put("totem_info", totemInfoJson(sess))
            put("last_result", lastResult ?: JSONObject.NULL)
        }
    }
}
