package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests directs de SessionJson, l'état de session envoyé à Compose.
 *
 * GameEngineTest le lit déjà à travers GameEngine.currentSessionJson(). Ici on teste SessionJson
 * tout seul : toutes les clés lues par GameViewModel sont là, le titre et l'en-tête de l'histoire
 * sont repris, et chaque champ reflète bien la session passée en paramètre. Le contenu de
 * PIP_SYMBOLS (variable globale remplie par StoryRegistry) n'est volontairement pas supposé.
 */
class SessionJsonTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sess: DiceSession

    @Before
    fun setUp() {
        sess = DiceSession(File(tmp.newFolder("save"), "dice_state.json"))
    }

    // ---- aides ----

    private fun story(title: String = "L'aventure", headerTitle: String = "") = StoryEntry(
        slug = "aventure",
        title = title,
        headerTitle = headerTitle,
        subtitle = "Un sous-titre",
        saveFile = "dice_state_aventure.json",
        bgImageB64 = "",
        isCustom = true,
        loreParagraphs = emptyList(),
        defaultTotem = null
    )

    private fun strings(arr: JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    private val expectedKeys = listOf(
        "history", "next_id", "pip_symbol", "pip_mode", "enabled_symbols", "custom_totems",
        "allowed_success_values", "allowed_fate_keys", "totem_energy", "threat_level",
        "side_quests", "next_quest_id", "story_log", "ai_conversation", "story_summary",
        "last_journal_index", "last_ai_sent_id", "all_symbols", "story_title", "header_title",
        "totem_info", "last_result"
    )

    // ======================================================================
    // Forme générale
    // ======================================================================

    @Test
    fun freshSession_hasEveryKeyTheScreensRead() {
        val json = SessionJson.build(sess, null)
        for (key in expectedKeys) assertTrue("Clé absente : $key", json.has(key))
    }

    @Test
    fun freshSession_hasTheInitialValues() {
        val json = SessionJson.build(sess, null)
        assertEquals(0, json.getJSONArray("history").length())
        assertEquals(1, json.getInt("next_id"))
        assertEquals(0, json.getInt("threat_level"))
        assertEquals(1, json.getInt("next_quest_id"))
        assertEquals("single", json.getString("pip_mode"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), (0 until 6).map { json.getJSONArray("allowed_success_values").getInt(it) })
        assertEquals(FATE_FACES.map { it.key }, strings(json.getJSONArray("allowed_fate_keys")))
        assertEquals(0, json.getJSONArray("custom_totems").length())
        assertEquals(0, json.getJSONArray("story_log").length())
        assertEquals(0, json.getJSONArray("ai_conversation").length())
        assertEquals("", json.getString("story_summary"))
    }

    @Test
    fun freshSession_hasNoLastResult() {
        assertTrue(SessionJson.build(sess, null).isNull("last_result"))
    }

    @Test
    fun building_doesNotChangeTheSession() {
        sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu", "\uD83E\uDD8A")
        sess.storyLog.add("Premier chapitre")
        val first = SessionJson.build(sess, story()).toString()
        val second = SessionJson.build(sess, story()).toString()
        assertEquals(first, second)
        assertEquals(1, sess.customTotems.size)
        assertEquals(1, sess.storyLog.size)
    }

    // ======================================================================
    // Titre et en-tête de l'histoire
    // ======================================================================

    @Test
    fun withoutStory_titleIsEmpty_andHeaderUsesTheDefault() {
        val json = SessionJson.build(sess, null)
        assertEquals("", json.getString("story_title"))
        assertEquals("Les Dés de l'Aventure", json.getString("header_title"))
    }

    @Test
    fun storyTitle_isTheStoryTitle() {
        assertEquals("Le dragon", SessionJson.build(sess, story(title = "Le dragon")).getString("story_title"))
    }

    @Test
    fun headerTitle_fallsBackToTheDefaultWhenEmpty_otherwiseIsKept() {
        assertEquals("Les Dés de l'Aventure", SessionJson.build(sess, story(headerTitle = "")).getString("header_title"))
        assertEquals("Mon en-tête", SessionJson.build(sess, story(headerTitle = "Mon en-tête")).getString("header_title"))
    }

    // ======================================================================
    // Totems
    // ======================================================================

    @Test
    fun customTotem_appearsInEveryPlaceTheScreensLookForIt() {
        val key = sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu", "\uD83E\uDD8A")!!
        val json = SessionJson.build(sess, story())

        // Liste brute des totems de la session.
        val totems = json.getJSONArray("custom_totems")
        assertEquals(1, totems.length())
        assertEquals(key, totems.getJSONObject(0).getString("key"))
        assertEquals("Patte magique", totems.getJSONObject(0).getString("label"))

        // Jauge d'énergie et symbole actif.
        assertEquals(0, json.getJSONObject("totem_energy").getInt(key))
        assertTrue(strings(json.getJSONArray("enabled_symbols")).contains(key))
    }

    @Test
    fun totemInfo_carriesTheIconLabelPowersAndSpecial() {
        val key = sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu", "\uD83E\uDD8A")!!
        val info = SessionJson.build(sess, story()).getJSONObject("totem_info").getJSONObject(key)
        assertEquals("\uD83E\uDD8A", info.getString("icon"))
        assertEquals("Patte magique", info.getString("label"))
        assertEquals(listOf("vol", "force"), strings(info.getJSONArray("powers")))
        assertEquals("Souffle de feu", info.getString("special"))
    }

    @Test
    fun allSymbols_marksTheAddedTotemAsCustom() {
        val key = sess.addCustomTotem("Patte magique", "vol", "", "\uD83E\uDD8A")!!
        val all = SessionJson.build(sess, story()).getJSONArray("all_symbols")
        val entry = (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("key") == key }
        assertEquals("Patte magique", entry.getString("label"))
        assertEquals("\uD83E\uDD8A", entry.getString("emoji"))
        assertTrue(entry.getBoolean("is_custom"))
        assertEquals(listOf("vol"), strings(entry.getJSONArray("powers")))
    }

    @Test
    fun totemEnergy_isCopiedAsIs() {
        val key = sess.addCustomTotem("Patte magique")!!
        sess.totemEnergy[key] = 7
        assertEquals(7, SessionJson.build(sess, story()).getJSONObject("totem_energy").getInt(key))
    }

    // ======================================================================
    // Dernier lancer
    // ======================================================================

    @Test
    fun afterARoll_historyAndLastResultDescribeIt() {
        val record = sess.rollSuccess()
        val json = SessionJson.build(sess, story())

        assertEquals(1, json.getJSONArray("history").length())
        assertEquals(record.id + 1, json.getInt("next_id"))

        val last: JSONObject = json.getJSONObject("last_result")
        assertEquals(record.id, last.getInt("id"))
        assertEquals(record.type, last.getString("type"))
        assertEquals(sess.describeRecord(record), last.getString("description"))
    }

    @Test
    fun lastResult_isTheMostRecentRoll() {
        sess.rollSuccess()
        val second = sess.rollFate()
        val last = SessionJson.build(sess, story()).getJSONObject("last_result")
        assertEquals(second.id, last.getInt("id"))
        assertEquals("fate", last.getString("type"))
    }

    // ======================================================================
    // Journal, résumé, conversation IA, quêtes
    // ======================================================================

    @Test
    fun journalSummaryAndAiConversation_reflectTheSession() {
        sess.storyLog.add("Premier chapitre")
        sess.storyLog.add("Deuxième chapitre")
        sess.storySummary = "PERSONNAGES : Alix"
        sess.addAiMessage("user", "Bonjour")
        sess.addAiMessage("assistant", "Il était une fois")

        val json = SessionJson.build(sess, story())
        assertEquals(listOf("Premier chapitre", "Deuxième chapitre"), strings(json.getJSONArray("story_log")))
        assertEquals("PERSONNAGES : Alix", json.getString("story_summary"))

        val conversation = json.getJSONArray("ai_conversation")
        assertEquals(2, conversation.length())
        assertEquals("user", conversation.getJSONObject(0).getString("role"))
        assertEquals("Bonjour", conversation.getJSONObject(0).getString("content"))
        assertEquals("assistant", conversation.getJSONObject(1).getString("role"))
    }

    @Test
    fun sideQuests_areListedWithTheirStatus() {
        sess.sideQuests.add(SideQuest(1, "ami", "en_attente", QUEST_MODE_END_OF_CHAPTER))
        sess.sideQuests.add(SideQuest(2, "objet", "ouverte", QUEST_MODE_PARALLEL))

        val quests = SessionJson.build(sess, story()).getJSONArray("side_quests")
        assertEquals(2, quests.length())
        assertEquals("en_attente", quests.getJSONObject(0).getString("status"))
        assertEquals("objet", quests.getJSONObject(1).getString("kind"))
        assertNotNull(quests.getJSONObject(1).getString("mode"))
    }

    @Test
    fun aiMessages_keepTheirVisibilityFlag() {
        sess.addAiMessage("system", "Mécaniques", visible = false)
        sess.addAiMessage("user", "Bonjour")
        val conversation = SessionJson.build(sess, story()).getJSONArray("ai_conversation")
        assertFalse(conversation.getJSONObject(0).getBoolean("visible"))
        assertTrue(conversation.getJSONObject(1).getBoolean("visible"))
    }
}
