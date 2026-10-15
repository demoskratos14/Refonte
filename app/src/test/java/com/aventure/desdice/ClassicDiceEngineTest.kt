package com.aventure.desdice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la page "Des classiques" côté moteur : lancer d'un plateau de dés au choix
 * (dé du destin facultatif), historique, anciennes sauvegardes, fichier abîmé.
 * Aucun appareil nécessaire : GameEngine ne dépend que d'un dossier.
 */
class ClassicDiceEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var engine: GameEngine

    @Before
    fun setUp() {
        dir = tmp.newFolder("data")
        engine = GameEngine(dir)
    }

    // ---- aides de lecture du JSON renvoyé par le moteur ----

    private fun history(state: JSONObject) = state.getJSONArray("history")

    private fun results(state: JSONObject, index: Int) =
        history(state).getJSONObject(index).getJSONArray("results")

    private fun types(state: JSONObject, index: Int): List<String> {
        val r = results(state, index)
        return (0 until r.length()).map { r.getJSONObject(it).getString("type") }
    }

    // ---- tests ----

    @Test
    fun oneDie_givesOneResultInRange() {
        val state = engine.classicDiceRollMany(listOf(20))
        assertEquals(1, history(state).length())
        assertEquals(1, results(state, 0).length())
        val r = results(state, 0).getJSONObject(0)
        assertEquals("success", r.getString("type"))
        assertEquals(20, r.getInt("faces"))
        assertTrue(r.getInt("value") in 1..20)
    }

    @Test
    fun fateDieIsOptional_noFateWhenNotRequested() {
        val state = engine.classicDiceRollMany(listOf(6, 6, 6))
        assertEquals(listOf("success", "success", "success"), types(state, 0))
    }

    @Test
    fun fateDieAlone_hasEmojiAndLabel() {
        val state = engine.classicDiceRollMany(listOf(0))
        assertEquals(listOf("fate"), types(state, 0))
        val r = results(state, 0).getJSONObject(0)
        assertTrue(r.getString("fate_emoji").isNotEmpty())
        assertTrue(r.getString("fate_label").isNotEmpty())
        assertTrue(r.getString("fate") in FATE_BY_KEY)
    }

    @Test
    fun mixedBoard_keepsRequestedOrderAndFaces() {
        val state = engine.classicDiceRollMany(listOf(0, 8, 0, 12))
        assertEquals(listOf("fate", "success", "fate", "success"), types(state, 0))
        assertEquals(8, results(state, 0).getJSONObject(1).getInt("faces"))
        assertEquals(12, results(state, 0).getJSONObject(3).getInt("faces"))
    }

    @Test
    fun values_alwaysStayInRange() {
        repeat(100) {
            val state = engine.classicDiceRollMany(listOf(4))
            val last = history(state).length() - 1
            val v = results(state, last).getJSONObject(0).getInt("value")
            assertTrue("valeur hors plage : $v", v in 1..4)
        }
    }

    @Test
    fun faces_areClampedBetween2And999() {
        val big = engine.classicDiceRollMany(listOf(5000))
        assertEquals(999, results(big, 0).getJSONObject(0).getInt("faces"))
        val small = engine.classicDiceRollMany(listOf(1))
        assertEquals(2, results(small, 1).getJSONObject(0).getInt("faces"))
    }

    @Test
    fun moreThanEightDice_areTruncatedToEight() {
        val state = engine.classicDiceRollMany(List(12) { 6 })
        assertEquals(8, results(state, 0).length())
    }

    @Test
    fun emptyBoard_addsNothing() {
        val before = engine.classicDiceState()
        val after = engine.classicDiceRollMany(emptyList())
        assertEquals(history(before).length(), history(after).length())
        assertEquals(before.getInt("next_id"), after.getInt("next_id"))
    }

    @Test
    fun history_keepsOnlyLast30_andIdsIncrease() {
        var state = engine.classicDiceState()
        repeat(35) { state = engine.classicDiceRollMany(listOf(6)) }
        assertEquals(30, history(state).length())
        assertEquals(6, history(state).getJSONObject(0).getInt("id"))
        assertEquals(35, history(state).getJSONObject(29).getInt("id"))
        assertEquals(36, state.getInt("next_id"))
    }

    @Test
    fun clear_resetsHistoryAndIds() {
        engine.classicDiceRollMany(listOf(6, 0))
        val cleared = engine.classicDiceClear()
        assertEquals(0, history(cleared).length())
        assertEquals(1, cleared.getInt("next_id"))
    }

    @Test
    fun history_survivesRestart() {
        engine.classicDiceRollMany(listOf(10, 0))
        val reopened = GameEngine(dir).classicDiceState()
        assertEquals(1, history(reopened).length())
        assertEquals(listOf("success", "fate"), types(reopened, 0))
    }

    @Test
    fun legacyApi_bothGivesSuccessThenFate() {
        val state = engine.classicDiceRoll("both", 20)
        assertEquals(listOf("success", "fate"), types(state, 0))
        assertEquals(20, results(state, 0).getJSONObject(0).getInt("faces"))
    }

    @Test
    fun oldSaveFormat_isConvertedOnTheFly() {
        File(dir, "classic_dice_state.json").writeText(
            """
            {"history":[
              {"id":1,"success":4,"faces":null,"fate":"coeur"},
              {"id":2,"success":null,"faces":null,"fate":"soleil"},
              {"id":3,"success":5,"faces":12,"fate":null}
            ],"next_id":4}
            """.trimIndent()
        )
        val state = GameEngine(dir).classicDiceState()
        assertEquals(3, history(state).length())

        // 1 : réussite (ancien d6 sans champ faces) puis destin
        assertEquals(listOf("success", "fate"), types(state, 0))
        val first = results(state, 0).getJSONObject(0)
        assertEquals(6, first.getInt("faces"))
        assertEquals(4, first.getInt("value"))
        assertEquals(FATE_BY_KEY.getValue("coeur").emoji, results(state, 0).getJSONObject(1).getString("fate_emoji"))

        // 2 : destin seul ; 3 : réussite seule sur un d12
        assertEquals(listOf("fate"), types(state, 1))
        assertEquals(listOf("success"), types(state, 2))
        assertEquals(12, results(state, 2).getJSONObject(0).getInt("faces"))

        // un nouveau lancer reprend la numérotation
        val next = GameEngine(dir).classicDiceRollMany(listOf(6))
        assertEquals(4, history(next).getJSONObject(3).getInt("id"))
    }

    @Test
    fun corruptedFile_fallsBackToEmptyHistory() {
        File(dir, "classic_dice_state.json").writeText("ceci n'est pas du JSON")
        val state = GameEngine(dir).classicDiceState()
        assertEquals(0, history(state).length())
        assertEquals(1, state.getInt("next_id"))
    }
}
