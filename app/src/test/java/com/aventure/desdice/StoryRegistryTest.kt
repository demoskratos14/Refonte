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
 * Tests de StoryRegistry (histoires personnalisées) sans appareil : on écrit directement
 * custom_stories.json dans un dossier temporaire. createCustomStory() n'est pas couverte ici :
 * elle réencode l'image de fond avec Bitmap (API Android) et demande un appareil/émulateur.
 * Les histoires de test n'ont pas d'image de fond (bgImageFile = null), ce qui évite aussi
 * android.util.Base64.
 */
class StoryRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var registry: StoryRegistry

    @Before
    fun setUp() {
        dir = tmp.newFolder("data")
        registry = StoryRegistry(dir)
    }

    private fun meta(
        slug: String,
        title: String = "Histoire $slug",
        bgImageFile: String? = null,
        totemImageFilename: String? = null,
        saveFile: String = "dice_state_$slug.json"
    ) = StoryMeta(
        slug = slug,
        title = title,
        subtitle = "Sous-titre $slug",
        loreText = "Il était une fois $slug",
        bgImageFile = bgImageFile,
        totemLabel = "Totem $slug",
        totemImageFilename = totemImageFilename,
        totemPowers = "vol, force",
        totemSpecial = "Souffle",
        protagonistNames = listOf("Léa", "Tom"),
        saveFile = saveFile,
        storyLength = "short",
        moralGoal = "Le courage"
    )

    private fun writeMetas(vararg metas: StoryMeta) {
        File(dir, "custom_stories.json").writeText(JSONArray(metas.map { it.toJson() }).toString())
    }

    private fun strings(arr: JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    // ---- registre vide / fichier abîmé ----

    @Test
    fun emptyRegistry_hasNoStories() {
        assertTrue(registry.allStories().isEmpty())
        assertTrue(registry.allStoryOrder().isEmpty())
        assertFalse(registry.isCustomStory("x"))
        assertFalse(registry.deleteCustomStory("x"))
        assertNull(registry.exportStoryIdentity("x"))
    }

    @Test
    fun corruptedStoriesFile_isTreatedAsEmpty() {
        File(dir, "custom_stories.json").writeText("{ceci n'est pas du json")
        assertTrue(registry.allStories().isEmpty())
        assertTrue(registry.allStoryOrder().isEmpty())
    }

    @Test
    fun entriesWithoutSlug_areIgnored() {
        val arr = JSONArray().apply {
            put(JSONObject().put("title", "sans slug"))
            put(meta("ok").toJson())
            put("pas un objet")
        }
        File(dir, "custom_stories.json").writeText(arr.toString())
        assertEquals(listOf("ok"), registry.allStoryOrder())
    }

    // ---- StoryMeta ----

    @Test
    fun storyMeta_roundTripsThroughJson() {
        val original = meta("aventure", bgImageFile = "aventure.jpg", totemImageFilename = "t.png")
        assertEquals(original, StoryMeta.fromJson(original.toJson()))
    }

    @Test
    fun storyMeta_nullImagesStayNull() {
        val restored = StoryMeta.fromJson(meta("a").toJson())!!
        assertNull(restored.bgImageFile)
        assertNull(restored.totemImageFilename)
    }

    @Test
    fun storyMeta_withoutSlug_isRejected() {
        assertNull(StoryMeta.fromJson(JSONObject()))
        assertNull(StoryMeta.fromJson(JSONObject().put("slug", "")))
    }

    @Test
    fun storyMeta_missingFields_getDefaults() {
        val m = StoryMeta.fromJson(JSONObject().put("slug", "vieux"))!!
        assertEquals("dice_state_vieux.json", m.saveFile)
        assertEquals("long", m.storyLength)
        assertEquals("", m.title)
        assertEquals("", m.moralGoal)
        assertTrue(m.protagonistNames.isEmpty())
    }

    @Test
    fun storyMeta_legacySingleProtagonistName_isStillRead() {
        val m = StoryMeta.fromJson(JSONObject().put("slug", "a").put("protagonist_name", "Zoé"))!!
        assertEquals(listOf("Zoé"), m.protagonistNames)
    }

    @Test
    fun storyMeta_blankProtagonistNames_areDropped() {
        val names = JSONArray().put("  ").put("Max").put("")
        val m = StoryMeta.fromJson(JSONObject().put("slug", "a").put("protagonist_names", names))!!
        assertEquals(listOf("Max"), m.protagonistNames)
    }

    @Test
    fun storyMeta_toJson_keepsFirstNameForOlderVersions() {
        val json = meta("a").toJson()
        assertEquals("Léa", json.getString("protagonist_name"))
        assertEquals(listOf("Léa", "Tom"), strings(json.getJSONArray("protagonist_names")))
    }

    // ---- lecture ----

    @Test
    fun allStories_keepsCreationOrder_andMarksThemCustom() {
        writeMetas(meta("b"), meta("a"), meta("c"))
        assertEquals(listOf("b", "a", "c"), registry.allStoryOrder())
        assertEquals(listOf("b", "a", "c"), registry.allStories().keys.toList())
        assertTrue(registry.allStories().values.all { it.isCustom })
        assertTrue(registry.isCustomStory("a"))
        assertFalse(registry.isCustomStory("z"))
    }

    @Test
    fun buildStoryEntry_copiesTheMetadata() {
        val entry = registry.buildStoryEntry(meta("a"))
        assertEquals("a", entry.slug)
        assertEquals("Histoire a", entry.title)
        assertEquals("Histoire a", entry.headerTitle)
        assertEquals("Sous-titre a", entry.subtitle)
        assertEquals("dice_state_a.json", entry.saveFile)
        assertEquals("", entry.bgImageB64)
        assertEquals(listOf("Il était une fois a"), entry.loreParagraphs)
        assertEquals(listOf("Léa", "Tom"), entry.protagonistNames)
        assertEquals("short", entry.storyLength)
        assertEquals("Le courage", entry.moralGoal)
        assertTrue(entry.isCustom)
        assertTrue(entry.pipSymbols.isEmpty())
    }

    @Test
    fun buildStoryEntry_startingTotem_comesFromTheMetadata() {
        val totem = registry.buildStoryEntry(meta("a")).defaultTotem!!
        assertEquals("Totem a", totem.label)
        assertEquals("vol, force", totem.powersText)
        assertEquals("Souffle", totem.special)
        assertNull(totem.imageFilename)
    }

    @Test
    fun buildStoryEntry_usesFallbacksForBlankFields() {
        val blank = meta("a", title = "", saveFile = "").copy(totemLabel = "", loreText = "")
        val entry = registry.buildStoryEntry(blank)
        assertEquals("a", entry.title)
        assertEquals("dice_state_a.json", entry.saveFile)
        assertEquals("Totem de départ", entry.defaultTotem!!.label)
        assertTrue(entry.loreParagraphs.isEmpty())
    }

    // ---- suppression ----

    @Test
    fun deleteCustomStory_removesEntryAndItsFiles_butNotTheOthers() {
        val bgDir = File(dir, "custom_story_bg").apply { mkdirs() }
        val totemDir = File(dir, "totem_images").apply { mkdirs() }
        val bgA = File(bgDir, "a.jpg").apply { writeText("x") }
        val totemA = File(totemDir, "a.png").apply { writeText("x") }
        val saveA = File(dir, "dice_state_a.json").apply { writeText("{}") }
        val bgB = File(bgDir, "b.jpg").apply { writeText("x") }
        val saveB = File(dir, "dice_state_b.json").apply { writeText("{}") }
        writeMetas(
            meta("a", bgImageFile = "a.jpg", totemImageFilename = "a.png"),
            meta("b", bgImageFile = "b.jpg")
        )

        assertTrue(registry.deleteCustomStory("a"))

        assertEquals(listOf("b"), registry.allStoryOrder())
        assertFalse(bgA.exists())
        assertFalse(totemA.exists())
        assertFalse(saveA.exists())
        assertTrue(bgB.exists())
        assertTrue(saveB.exists())
    }

    @Test
    fun deleteCustomStory_twice_secondCallReturnsFalse() {
        writeMetas(meta("a"))
        assertTrue(registry.deleteCustomStory("a"))
        assertFalse(registry.deleteCustomStory("a"))
        assertTrue(registry.allStories().isEmpty())
    }

    @Test
    fun deleteCustomStory_toleratesMissingFiles() {
        writeMetas(meta("a", bgImageFile = "absent.jpg", totemImageFilename = "absent.png"))
        assertTrue(registry.deleteCustomStory("a"))
    }

    // ---- export / import d'identité ----

    @Test
    fun exportStoryIdentity_containsTheStoryDescription() {
        writeMetas(meta("a"))
        val json = registry.exportStoryIdentity("a")!!
        assertEquals("des-aventure-identite-v1", json.getString("format"))
        assertEquals("Histoire a", json.getString("title"))
        assertEquals("Sous-titre a", json.getString("subtitle"))
        assertEquals("Totem a", json.getString("totem_label"))
        assertEquals("short", json.getString("story_length"))
        assertEquals("Le courage", json.getString("moral_goal"))
        assertEquals(1, json.getInt("next_quest_id"))
        assertEquals(0, json.getJSONArray("extra_totems").length())
        assertEquals(0, json.getJSONArray("story_log").length())
        assertEquals("", json.getString("bg_image_b64"))
    }

    @Test
    fun exportThenParse_roundTrips() {
        writeMetas(meta("a"))
        val extra = JSONArray().put(
            JSONObject().apply {
                put("label", "Loup")
                put("emoji", "🐺")
                put("powers", JSONArray().put("hurle").put("court"))
                put("special", "Meute")
                put("image_b64", "AAAA")
            }
        )
        val quests = JSONArray().put(JSONObject().put("id", 2).put("kind", "ami").put("status", "ouverte"))
        val log = JSONArray().put("Chapitre un").put("Chapitre deux")

        val exported = registry.exportStoryIdentity(
            "a", extraTotems = extra, sideQuests = quests, nextQuestId = 5, storyLog = log, storySummary = "Le résumé"
        )!!
        val parsed = registry.parseIdentityImport(exported.toString())

        assertEquals("Histoire a", parsed.title)
        assertEquals("Sous-titre a", parsed.subtitle)
        assertEquals("Il était une fois a", parsed.loreText)
        assertEquals("Totem a", parsed.totemLabel)
        assertEquals("vol, force", parsed.totemPowers)
        assertEquals("Souffle", parsed.totemSpecial)
        assertEquals(listOf("Léa", "Tom"), parsed.protagonistNames)
        assertEquals("short", parsed.storyLength)
        assertEquals("Le courage", parsed.moralGoal)
        assertEquals(1, parsed.extraTotems.size)
        assertEquals("Loup", parsed.extraTotems[0].label)
        assertEquals(listOf("hurle", "court"), parsed.extraTotems[0].powers)
        assertEquals("AAAA", parsed.extraTotems[0].imageB64)
        assertEquals(1, parsed.sideQuests.length())
        assertEquals(5, parsed.nextQuestId)
        assertEquals(listOf("Chapitre un", "Chapitre deux"), strings(parsed.storyLog))
        assertEquals("Le résumé", parsed.storySummary)
    }

    @Test
    fun parseIdentityImport_invalidJson_givesEmptyResultWithoutThrowing() {
        for (raw in listOf("", "pas du json", "[1, 2]", "null", "{\"title\": ")) {
            val parsed = registry.parseIdentityImport(raw)
            assertEquals("", parsed.title)
            assertEquals("", parsed.totemLabel)
            assertTrue(parsed.protagonistNames.isEmpty())
            assertTrue(parsed.extraTotems.isEmpty())
            assertEquals(0, parsed.sideQuests.length())
            assertEquals(0, parsed.storyLog.length())
            assertEquals(1, parsed.nextQuestId)
            assertEquals("long", parsed.storyLength)
        }
    }

    @Test
    fun parseIdentityImport_trimsTextFields() {
        val raw = JSONObject().put("title", "  Mon titre ").put("story_summary", "\n résumé \n").toString()
        val parsed = registry.parseIdentityImport(raw)
        assertEquals("Mon titre", parsed.title)
        assertEquals("résumé", parsed.storySummary)
    }

    @Test
    fun parseIdentityImport_extraTotems_skipBlankLabelsAndBadPowers() {
        val extra = JSONArray().apply {
            put(JSONObject().put("label", "   "))
            put("pas un objet")
            put(
                JSONObject().apply {
                    put("label", " Loup ")
                    put("emoji", " 🐺 ")
                    put("special", " Meute ")
                    put("powers", JSONArray().put("  hurle ").put(3).put("").put("   "))
                }
            )
        }
        val parsed = registry.parseIdentityImport(JSONObject().put("extra_totems", extra).toString())
        assertEquals(1, parsed.extraTotems.size)
        val t = parsed.extraTotems[0]
        assertEquals("Loup", t.label)
        assertEquals("🐺", t.emoji)
        assertEquals("Meute", t.special)
        assertEquals(listOf("hurle"), t.powers)
        assertEquals("", t.imageB64)
    }

    @Test
    fun parseIdentityImport_sideQuests_keepOnlyCompleteOnes_andDeriveNextId() {
        val quests = JSONArray().apply {
            put(JSONObject().put("id", 1).put("kind", "ami").put("status", "ouverte"))
            put(JSONObject().put("id", 2).put("kind", "objet"))
            put("texte")
            put(JSONObject().put("id", 7).put("kind", "ami").put("status", "terminee"))
        }
        val parsed = registry.parseIdentityImport(JSONObject().put("side_quests", quests).toString())
        assertEquals(2, parsed.sideQuests.length())
        assertEquals(8, parsed.nextQuestId)
    }

    @Test
    fun parseIdentityImport_explicitNextQuestId_isUsed_unlessItIsNotANumber() {
        val quests = JSONArray().put(JSONObject().put("id", 4).put("kind", "ami").put("status", "ouverte"))
        val withId = JSONObject().put("side_quests", quests).put("next_quest_id", 3).toString()
        assertEquals(3, registry.parseIdentityImport(withId).nextQuestId)

        val broken = JSONObject().put("side_quests", quests).put("next_quest_id", "abc").toString()
        assertEquals(5, registry.parseIdentityImport(broken).nextQuestId)
    }

    @Test
    fun parseIdentityImport_storyLog_keepsOnlyNonBlankStrings() {
        val log = JSONArray().put("Un").put("").put("   ").put(5).put("Deux")
        val parsed = registry.parseIdentityImport(JSONObject().put("story_log", log).toString())
        assertEquals(listOf("Un", "Deux"), strings(parsed.storyLog))
    }

    @Test
    fun parseIdentityImport_legacyProtagonistName_isRead() {
        val parsed = registry.parseIdentityImport(JSONObject().put("protagonist_name", "Zoé").toString())
        assertEquals(listOf("Zoé"), parsed.protagonistNames)
    }

    @Test
    fun exportedStory_isNotNull_forEveryStoredStory() {
        writeMetas(meta("a"), meta("b"))
        for (slug in registry.allStoryOrder()) assertNotNull(registry.exportStoryIdentity(slug))
    }
}
