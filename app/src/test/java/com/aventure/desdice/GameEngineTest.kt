package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du moteur de jeu (GameEngine) sans appareil : il ne dépend que d'un dossier.
 * Les histoires sont fabriquées en écrivant custom_stories.json (sans image de fond), car
 * createStory() passe par ImageUtils (Bitmap, API Android). Aucune clé Mistral n'est
 * configurée : la narration IA est donc toujours court-circuitée, sans réseau.
 */
class GameEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var engine: GameEngine

    private val starterKey = "patte_magique"

    @Before
    fun setUp() {
        dir = tmp.newFolder("data")
        engine = GameEngine(dir)
    }

    // ---- aides ----

    private fun meta(
        slug: String = "aventure",
        title: String = "L'aventure",
        length: String = "long"
    ) = StoryMeta(
        slug = slug,
        title = title,
        subtitle = "Un sous-titre",
        loreText = "Il était une fois",
        bgImageFile = null,
        totemLabel = "Patte magique",
        totemImageFilename = null,
        totemPowers = "vol, force",
        totemSpecial = "Souffle de feu",
        protagonistNames = listOf("Léa", "Tom"),
        saveFile = "dice_state_$slug.json",
        storyLength = length
    )

    private fun writeStories(vararg metas: StoryMeta) {
        File(dir, "custom_stories.json").writeText(JSONArray(metas.map { it.toJson() }).toString())
    }

    /** Écrit l'histoire "aventure" et la sélectionne ; renvoie l'état initial. */
    private fun startStory(length: String = "long"): JSONObject {
        writeStories(meta(length = length))
        return engine.selectStory("aventure")
    }

    private fun strings(arr: JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    private fun ints(arr: JSONArray) = (0 until arr.length()).map { arr.getInt(it) }

    private fun JSONObject.history(): JSONArray = getJSONArray("history")

    private fun JSONObject.totemKeys(): List<String> =
        (0 until getJSONArray("custom_totems").length()).map { getJSONArray("custom_totems").getJSONObject(it).getString("key") }

    // ---- avant toute sélection d'histoire ----

    @Test
    fun beforeAnyStory_stateIsEmptyAndActionsAreHarmless() {
        val state = engine.currentSessionJson()
        assertEquals(0, state.history().length())
        assertEquals("", state.getString("story_title"))
        assertEquals("Les Dés de l'Aventure", state.getString("header_title"))
        assertTrue(state.isNull("last_result"))

        assertNull(engine.session)
        assertEquals(0, engine.roll("success").history().length())
        assertEquals(0, engine.undo().history().length())
        assertEquals(0, engine.clear().history().length())
        assertEquals(0, engine.addStoryEntry("texte").getJSONArray("story_log").length())
        val spent = engine.useTotemEnergy("patte")
        assertFalse(spent.getBoolean("spent"))
        assertEquals("", spent.getString("effect"))
    }

    // ---- navigation entre histoires ----

    @Test
    fun listStories_withoutStories_isEmpty() {
        val list = engine.listStories()
        assertEquals(0, list.getJSONArray("stories").length())
        assertEquals("", list.getString("current_story"))
    }

    @Test
    fun listStories_listsCustomStoriesInOrder_andTracksTheCurrentOne() {
        writeStories(meta("b", "Deuxième"), meta("a", "Première"))
        val stories = engine.listStories().getJSONArray("stories")
        assertEquals(2, stories.length())
        assertEquals("b", stories.getJSONObject(0).getString("slug"))
        assertEquals("Deuxième", stories.getJSONObject(0).getString("title"))
        assertTrue(stories.getJSONObject(0).getBoolean("is_custom"))
        assertEquals("a", stories.getJSONObject(1).getString("slug"))

        engine.selectStory("a")
        assertEquals("a", engine.listStories().getString("current_story"))
        assertEquals("a", engine.currentStorySlug)
    }

    @Test
    fun switchStory_unknownSlug_changesNothing() {
        writeStories(meta())
        assertFalse(engine.switchStory("inconnue"))
        assertNull(engine.currentStorySlug)
        assertNull(engine.session)
        assertEquals("", engine.selectStory("inconnue").getString("story_title"))
        assertNull(engine.currentStorySlug)
    }

    @Test
    fun firstStart_createsTheStarterTotemAndSelectsIt() {
        val state = startStory()
        assertEquals("L'aventure", state.getString("story_title"))
        assertEquals("L'aventure", state.getString("header_title"))
        assertEquals(listOf(starterKey), state.totemKeys())
        val totem = state.getJSONArray("custom_totems").getJSONObject(0)
        assertEquals("Patte magique", totem.getString("label"))
        assertEquals(listOf("vol", "force"), strings(totem.getJSONArray("powers")))
        assertEquals("Souffle de feu", totem.getString("special"))
        assertEquals(starterKey, state.getString("pip_symbol"))
        assertEquals(0, state.getJSONObject("totem_energy").getInt(starterKey))
        assertTrue(File(dir, "dice_state_aventure.json").exists())
    }

    @Test
    fun totemInfo_isExposedForTheStarterTotem() {
        val info = startStory().getJSONObject("totem_info").getJSONObject(starterKey)
        assertEquals("Patte magique", info.getString("label"))
        assertEquals("Souffle de feu", info.getString("special"))
    }

    @Test
    fun storyLength_followsTheStory() {
        startStory(length = "short")
        assertEquals(LENGTH_SHORT, engine.session!!.storyLength)
    }

    @Test
    fun restart_reloadsTheSavedGame_withoutDuplicatingTheStarterTotem() {
        startStory()
        repeat(3) { engine.roll("success") }

        val second = GameEngine(dir)
        val state = second.selectStory("aventure")
        assertEquals(3, state.history().length())
        assertEquals(4, state.getInt("next_id"))
        assertEquals(listOf(starterKey), state.totemKeys())
    }

    @Test
    fun twoStories_haveIndependentSaves() {
        writeStories(meta("a"), meta("b"))
        engine.selectStory("a")
        engine.roll("success")
        engine.roll("fate")

        val inB = engine.selectStory("b")
        assertEquals(0, inB.history().length())
        assertEquals(2, engine.selectStory("a").history().length())
    }

    @Test
    fun deleteStory_removesItFromTheList_andResetsTheCurrentOne() {
        startStory()
        engine.roll("success")
        val state = engine.deleteStory("aventure")

        assertEquals(0, engine.listStories().getJSONArray("stories").length())
        assertEquals("", engine.listStories().getString("current_story"))
        assertNull(engine.session)
        assertEquals("", state.getString("story_title"))
        assertFalse(File(dir, "dice_state_aventure.json").exists())
    }

    @Test
    fun deleteStory_ofAnotherStory_keepsTheCurrentOne() {
        writeStories(meta("a"), meta("b"))
        engine.selectStory("a")
        engine.deleteStory("b")
        assertEquals("a", engine.currentStorySlug)
        assertNotNull(engine.session)
        assertEquals(listOf("a"), engine.storySlugs())
    }

    private fun GameEngine.storySlugs(): List<String> {
        val arr = listStories().getJSONArray("stories")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("slug") }
    }

    // ---- lancers ----

    @Test
    fun roll_success_addsOneSuccessRecord() {
        startStory()
        val state = engine.roll("success")
        assertEquals(1, state.history().length())
        val rec = state.history().getJSONObject(0)
        assertEquals("success", rec.getString("type"))
        assertTrue(rec.getInt("success") in 1..6)
        assertTrue(rec.isNull("fate"))
        assertEquals(2, state.getInt("next_id"))
        assertFalse(state.isNull("last_result"))
        assertTrue(state.getJSONObject("last_result").getString("description").isNotEmpty())
    }

    @Test
    fun roll_fate_addsOneFateRecord() {
        startStory()
        val rec = engine.roll("fate").history().getJSONObject(0)
        assertEquals("fate", rec.getString("type"))
        assertTrue(rec.getString("fate") in FATE_BY_KEY)
        assertTrue(rec.isNull("success"))
    }

    @Test
    fun roll_both_addsBothValues() {
        startStory()
        val rec = engine.roll("both").history().getJSONObject(0)
        assertEquals("both", rec.getString("type"))
        assertTrue(rec.getInt("success") in 1..6)
        assertTrue(rec.getString("fate") in FATE_BY_KEY)
    }

    @Test
    fun roll_unknownAction_changesNothing() {
        startStory()
        assertEquals(0, engine.roll("n'importe quoi").history().length())
    }

    @Test
    fun rolls_haveIncreasingIds() {
        startStory()
        repeat(4) { engine.roll("success") }
        val history = engine.currentSessionJson().history()
        assertEquals(listOf(1, 2, 3, 4), (0 until history.length()).map { history.getJSONObject(it).getInt("id") })
    }

    @Test
    fun successRoll_chargesTheSelectedTotem() {
        startStory()
        val state = engine.roll("success")
        val value = state.history().getJSONObject(0).getInt("success")
        assertEquals(value, state.getJSONObject("totem_energy").getInt(starterKey))
    }

    @Test
    fun allowedValues_constrainTheSuccessDie() {
        startStory()
        for (v in 1..5) engine.toggleAllowedValue(v)
        repeat(20) { engine.roll("success") }
        val history = engine.currentSessionJson().history()
        assertTrue((0 until history.length()).all { history.getJSONObject(it).getInt("success") == 6 })
    }

    @Test
    fun undo_removesTheLastRoll_andClearResetsEverything() {
        startStory()
        repeat(3) { engine.roll("success") }
        assertEquals(2, engine.undo().history().length())

        val cleared = engine.clear()
        assertEquals(0, cleared.history().length())
        assertEquals(1, cleared.getInt("next_id"))
    }

    @Test
    fun undo_onEmptyHistory_isHarmless() {
        startStory()
        assertEquals(0, engine.undo().history().length())
    }

    // ---- réglages de tirage ----

    @Test
    fun allowedSuccessValues_toggleAndReset() {
        startStory()
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ints(engine.currentSessionJson().getJSONArray("allowed_success_values")))
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            ints(engine.toggleAllowedValue(6).getJSONArray("allowed_success_values"))
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6),
            ints(engine.resetAllowedValues().getJSONArray("allowed_success_values"))
        )
    }

    @Test
    fun allowedFateKeys_toggleAndReset() {
        startStory()
        val full = FATE_FACES.map { it.key }
        assertEquals(full, strings(engine.currentSessionJson().getJSONArray("allowed_fate_keys")))
        assertEquals(full - "coeur", strings(engine.toggleAllowedFate("coeur").getJSONArray("allowed_fate_keys")))
        assertEquals(full - "coeur", strings(engine.toggleAllowedFate("inconnue").getJSONArray("allowed_fate_keys")))
        assertEquals(full.toSet(), strings(engine.resetAllowedFate().getJSONArray("allowed_fate_keys")).toSet())
    }

    @Test
    fun pipMode_togglesBetweenModes() {
        startStory()
        assertEquals("mixed", engine.setPipMode("mixed").getString("pip_mode"))
        assertEquals("single", engine.setPipMode("mixed").getString("pip_mode"))
        assertEquals("random", engine.setPipMode("random").getString("pip_mode"))
        assertEquals("random", engine.setPipMode("bizarre").getString("pip_mode"))
    }

    @Test
    fun pipSymbol_unknownKeyIsIgnored_knownKeyIsApplied() {
        startStory()
        engine.addCustomTotem("Dragon")
        assertEquals("dragon", engine.setPipSymbol("dragon").getString("pip_symbol"))
        assertEquals("dragon", engine.setPipSymbol("fantome").getString("pip_symbol"))
    }

    @Test
    fun enabledSymbols_alwaysKeepAtLeastOne() {
        startStory()
        val state = engine.toggleEnabledSymbol(starterKey)
        assertEquals(listOf(starterKey), strings(state.getJSONArray("enabled_symbols")))
    }

    // ---- totems ----

    @Test
    fun addCustomTotem_addsAFreshTotemWithEmptyGauge() {
        startStory()
        val state = engine.addCustomTotem("Dragon", powers = "feu, vol", special = "Souffle", emoji = "🐉")
        assertEquals(listOf(starterKey, "dragon"), state.totemKeys())
        val dragon = state.getJSONArray("custom_totems").getJSONObject(1)
        assertEquals("Dragon", dragon.getString("label"))
        assertEquals(listOf("feu", "vol"), strings(dragon.getJSONArray("powers")))
        assertEquals("🐉", dragon.getString("emoji"))
        assertEquals(0, state.getJSONObject("totem_energy").getInt("dragon"))
        assertTrue(state.getJSONObject("totem_info").has("dragon"))
    }

    @Test
    fun addCustomTotem_blankName_isIgnored() {
        startStory()
        assertEquals(listOf(starterKey), engine.addCustomTotem("   ").totemKeys())
    }

    @Test
    fun addCustomTotem_sameName_getsAUniqueKey() {
        startStory()
        engine.addCustomTotem("Dragon")
        val keys = engine.addCustomTotem("Dragon").totemKeys()
        assertEquals(listOf(starterKey, "dragon", "dragon_2"), keys)
    }

    @Test
    fun addCustomTotem_isSavedAcrossRestarts() {
        startStory()
        engine.addCustomTotem("Dragon", powers = "feu")
        val state = GameEngine(dir).selectStory("aventure")
        assertEquals(listOf(starterKey, "dragon"), state.totemKeys())
    }

    @Test
    fun removeCustomTotem_removesItAndItsGauge() {
        startStory()
        engine.addCustomTotem("Dragon")
        val state = engine.removeCustomTotem("dragon")
        assertEquals(listOf(starterKey), state.totemKeys())
        assertFalse(state.getJSONObject("totem_energy").has("dragon"))
    }

    @Test
    fun removeCustomTotem_unknownKey_changesNothing() {
        startStory()
        assertEquals(listOf(starterKey), engine.removeCustomTotem("fantome").totemKeys())
    }

    @Test
    fun removeCustomTotem_deletesItsImageFile() {
        startStory()
        engine.session!!.addCustomTotem("Loup", imageFilename = "loup.png")
        val image = File(File(dir, "totem_images").apply { mkdirs() }, "loup.png").apply { writeText("x") }
        assertTrue(image.exists())

        engine.removeCustomTotem("loup")
        assertFalse(image.exists())
    }

    @Test
    fun updateCustomTotem_changesPowersAndSpecial() {
        startStory()
        engine.addCustomTotem("Dragon", powers = "feu", special = "avant")
        val state = engine.updateCustomTotem("dragon", listOf(" feu ", "glace", ""), " après ")
        val dragon = state.getJSONArray("custom_totems").getJSONObject(1)
        assertEquals(listOf("feu", "glace"), strings(dragon.getJSONArray("powers")))
        assertEquals("après", dragon.getString("special"))
    }

    @Test
    fun updateCustomTotem_unknownKey_changesNothing() {
        startStory()
        val state = engine.updateCustomTotem("fantome", listOf("x"), "y")
        assertEquals(listOf(starterKey), state.totemKeys())
        assertEquals(listOf("vol", "force"), strings(state.getJSONArray("custom_totems").getJSONObject(0).getJSONArray("powers")))
    }

    // ---- énergie totémique ----

    private fun fillGauge(key: String) {
        engine.session!!.totemEnergy[key] = TOTEM_ENERGY_THRESHOLD
    }

    @Test
    fun useTotemEnergy_gaugeNotFull_doesNothing() {
        startStory()
        val result = engine.useTotemEnergy(starterKey)
        assertFalse(result.getBoolean("spent"))
        assertEquals("", result.getString("effect"))
    }

    @Test
    fun useTotemEnergy_fullGauge_spendsItAndReturnsTheSpecialPower() {
        startStory()
        fillGauge(starterKey)
        val result = engine.useTotemEnergy(starterKey)
        assertTrue(result.getBoolean("spent"))
        assertEquals("Souffle de feu", result.getString("effect"))
        assertEquals(0, result.getJSONObject("totem_energy").getInt(starterKey))
        assertFalse(result.has("ai_error"))
    }

    @Test
    fun useTotemEnergy_twiceInARow_secondCallFails() {
        startStory()
        fillGauge(starterKey)
        assertTrue(engine.useTotemEnergy(starterKey).getBoolean("spent"))
        assertFalse(engine.useTotemEnergy(starterKey).getBoolean("spent"))
    }

    @Test
    fun useTotemEnergy_unknownKey_doesNothing() {
        startStory()
        assertFalse(engine.useTotemEnergy("fantome").getBoolean("spent"))
    }

    @Test
    fun useTotemEnergy_withoutSpecial_listsThePowers() {
        startStory()
        engine.addCustomTotem("Chat", powers = "miaule, griffe")
        fillGauge("chat")
        assertEquals("Le pouvoir de Chat se manifeste : miaule, griffe.", engine.useTotemEnergy("chat").getString("effect"))
    }

    @Test
    fun useTotemEnergy_withoutSpecialNorPowers_fallsBackToAGenericLine() {
        startStory()
        engine.addCustomTotem("Hibou")
        fillGauge("hibou")
        assertEquals("Hibou intervient pour aider !", engine.useTotemEnergy("hibou").getString("effect"))
    }

    @Test
    fun useTotemEnergy_secondBreathTotem_rerollsTheLastSuccess() {
        startStory()
        engine.addCustomTotem("Patte")
        engine.roll("success")
        engine.roll("success")
        fillGauge("patte")

        val result = engine.useTotemEnergy("patte")

        assertTrue(result.getBoolean("spent"))
        assertTrue(result.getString("effect").startsWith("🔄 patte !"))
        val history = result.history()
        assertEquals(2, history.length()) // un lancer retiré, un lancer ajouté
        assertEquals("Relance (patte)", history.getJSONObject(1).getString("note"))
    }

    // ---- quêtes et journal ----

    @Test
    fun addStoryEntry_trimsText_andIgnoresBlankOnes() {
        startStory()
        assertEquals(listOf("Chapitre un"), strings(engine.addStoryEntry("  Chapitre un \n").getJSONArray("story_log")))
        assertEquals(1, engine.addStoryEntry("   ").getJSONArray("story_log").length())
    }

    @Test
    fun applyImportedProgress_restoresQuestsLogAndSummary() {
        startStory()
        val quests = JSONArray().put(JSONObject().put("id", 3).put("kind", "ami").put("status", "ouverte"))
        val log = JSONArray().put("Un").put("").put("Deux")
        val state = engine.applyImportedProgress(quests, 9, log, "  Le résumé ")

        assertEquals(1, state.getJSONArray("side_quests").length())
        assertEquals(9, state.getInt("next_quest_id"))
        assertEquals(listOf("Un", "Deux"), strings(state.getJSONArray("story_log")))
        assertEquals("Le résumé", state.getString("story_summary"))
    }

    @Test
    fun completeSideQuest_marksItDone_andUnknownIdsAreIgnored() {
        startStory()
        val quests = JSONArray().put(JSONObject().put("id", 1).put("kind", "objet").put("status", "ouverte"))
        engine.applyImportedProgress(quests, 2, JSONArray(), "")

        val ignored = engine.completeSideQuest(42)
        assertEquals("ouverte", ignored.getJSONArray("side_quests").getJSONObject(0).getString("status"))

        val done = engine.completeSideQuest(1)
        assertEquals("terminee", done.getJSONArray("side_quests").getJSONObject(0).getString("status"))
    }

    // ---- configuration Mistral ----

    @Test
    fun config_defaultsWhenNothingIsSaved() {
        assertEquals("", engine.getMistralKey())
        assertFalse(engine.hasMistralKey())
        assertEquals(MistralClient.DEFAULT_MODEL, engine.getMistralModel())
    }

    @Test
    fun config_keyIsTrimmed_persisted_andCanBeCleared() {
        engine.setMistralKey("  abc123456  ")
        assertEquals("abc123456", engine.getMistralKey())
        assertTrue(engine.hasMistralKey())
        assertEquals("abc123456", GameEngine(dir).getMistralKey())

        engine.clearMistralKey()
        assertFalse(engine.hasMistralKey())
        assertEquals("", GameEngine(dir).getMistralKey())
    }

    @Test
    fun config_model_blankFallsBackToDefault_andChoiceIsPersisted() {
        engine.setMistralModel("mistral-large-latest")
        assertEquals("mistral-large-latest", GameEngine(dir).getMistralModel())
        engine.setMistralModel("   ")
        assertEquals(MistralClient.DEFAULT_MODEL, engine.getMistralModel())
    }

    @Test
    fun config_settingTheKey_keepsTheModel() {
        engine.setMistralModel("mistral-medium-latest")
        engine.setMistralKey("secret")
        assertEquals("mistral-medium-latest", engine.getMistralModel())
    }

    @Test
    fun config_corruptedFile_fallsBackToDefaults() {
        File(dir, "app_config.json").writeText("{pas du json")
        assertEquals("", engine.getMistralKey())
        assertEquals(MistralClient.DEFAULT_MODEL, engine.getMistralModel())
    }

    @Test
    fun configScreenState_masksTheKeyExceptItsLastFourCharacters() {
        assertEquals("", engine.getConfigScreenState().getString("masked_key"))
        assertFalse(engine.getConfigScreenState().getBoolean("has_key"))

        engine.setMistralKey("abcdef")
        val state = engine.getConfigScreenState()
        assertTrue(state.getBoolean("has_key"))
        assertEquals("**cdef", state.getString("masked_key"))

        engine.setMistralKey("abcd")
        assertEquals("abcd", engine.getConfigScreenState().getString("masked_key"))
    }

    @Test
    fun configScreenState_listsEveryModelChoice() {
        val choices = engine.getConfigScreenState().getJSONArray("model_choices")
        assertEquals(MistralClient.MODEL_CHOICES.size, choices.length())
        assertEquals(MistralClient.MODEL_CHOICES[0].first, choices.getJSONArray(0).getString(0))
        assertEquals(MistralClient.DEFAULT_MODEL, engine.getConfigScreenState().getString("model"))
    }

    // ---- import d'identité (analyse seule) ----

    @Test
    fun importIdentity_invalidJson_givesAnEmptyResult() {
        val result = engine.importIdentity("pas du json")
        assertEquals("", result.getString("title"))
        assertEquals(0, result.getJSONArray("extra_totems").length())
        assertEquals(0, result.getJSONArray("side_quests").length())
        assertEquals(1, result.getInt("next_quest_id"))
    }

    @Test
    fun importIdentity_readsAnExportedIdentity() {
        val raw = JSONObject().apply {
            put("title", " Mon histoire ")
            put("totem_label", "Loup")
            put("protagonist_names", JSONArray().put("Léa"))
            put("extra_totems", JSONArray().put(JSONObject().put("label", "Chat").put("powers", JSONArray().put("miaule"))))
            put("story_log", JSONArray().put("Un"))
        }.toString()

        val result = engine.importIdentity(raw)
        assertEquals("Mon histoire", result.getString("title"))
        assertEquals("Loup", result.getString("totem_label"))
        assertEquals(listOf("Léa"), strings(result.getJSONArray("protagonist_names")))
        assertEquals("Chat", result.getJSONArray("extra_totems").getJSONObject(0).getString("label"))
        assertEquals(listOf("Un"), strings(result.getJSONArray("story_log")))
    }

    // ---- changement de palier en cours d'histoire ----

    @Test
    fun setPlayerAge_remplaceLeCadreDansLaConversationEtEnregistreLAge() {
        writeStories(meta().copy(playerAge = 15))
        engine.switchStory("aventure")
        val sess = engine.session!!
        val story = StoryRegistry(dir).allStories()["aventure"]!!
        sess.addAiMessage("system", PromptBuilder.buildMechanicsContext(sess, story, autoMode = true), visible = false)
        sess.addAiMessage("user", "Je regarde autour de moi.")
        assertTrue(sess.aiConversation[0].content.contains("CADRE DE CONTENU — ADOLESCENT"))

        val json = engine.setPlayerAge(9)

        val head = sess.aiConversation[0].content
        assertTrue(head.contains("CADRE DE SÉCURITÉ"))
        assertFalse(head.contains("CADRE DE CONTENU — ADOLESCENT"))
        assertEquals(2, sess.aiConversation.size) // aucun message ajouté : le digest ne se décale pas
        assertEquals("Je regarde autour de moi.", sess.aiConversation[1].content)
        assertEquals(9, json.getInt("player_age"))
        assertEquals(9, StoryRegistry(dir).allStories()["aventure"]!!.playerAge)
    }

    @Test
    fun setPlayerAge_cadreNonReconnu_reconstruitLeMessageSystem() {
        writeStories(meta().copy(playerAge = 15))
        engine.switchStory("aventure")
        val sess = engine.session!!
        sess.addAiMessage("system", "Ancien texte sans cadre reconnaissable", visible = false)

        engine.setPlayerAge(20)

        val head = sess.aiConversation[0].content
        assertTrue(head.contains("CADRE DE CONTENU — ADULTE"))
        assertFalse(head.contains("Ancien texte"))
    }

    @Test
    fun setPlayerAge_memeAge_neChangeRien() {
        writeStories(meta().copy(playerAge = 15))
        engine.switchStory("aventure")
        val sess = engine.session!!
        sess.addAiMessage("system", "Message inchangé", visible = false)

        engine.setPlayerAge(15)

        assertEquals("Message inchangé", sess.aiConversation[0].content)
    }
}
