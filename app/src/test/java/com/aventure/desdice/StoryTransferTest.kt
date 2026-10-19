package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests directs de StoryTransfer : exports (journal, identité, progression) et analyse d'un
 * import.
 *
 * PromptAndExportTest les couvre déjà à travers GameEngine, avec l'histoire courante choisie
 * par GameEngine. Ici on passe soi-même la session, l'histoire et le slug, ce qui permet de
 * tester les cas limites (aucune session, slug absent, JSON d'import abîmé ou incomplet) sans
 * monter tout le moteur.
 *
 * Limite connue : le PDF du journal n'est pas testé (il utilise Paint/PdfDocument, API Android
 * absentes de la JVM de test) ; seul le repli en texte, utilisé quand le journal est vide, l'est.
 */
class StoryTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var sess: DiceSession
    private lateinit var transfer: StoryTransfer

    @Before
    fun setUp() {
        dir = tmp.newFolder("data")
        sess = DiceSession(File(dir, "dice_state.json"))
        File(dir, "custom_stories.json").writeText(JSONArray(listOf(meta().toJson())).toString())
        // Les images de totems sont simulées : "chat.png" -> "B64:chat.png", pas d'image -> "".
        transfer = StoryTransfer(StoryRegistry(dir)) { filename -> if (filename == null) "" else "B64:$filename" }
    }

    // ---- aides ----

    private fun meta() = StoryMeta(
        slug = "aventure",
        title = "L'aventure",
        subtitle = "Un sous-titre",
        loreText = "Il était une fois",
        bgImageFile = null,
        totemLabel = "Patte magique",
        totemImageFilename = null,
        totemPowers = "vol, force",
        totemSpecial = "Souffle de feu",
        protagonistNames = listOf("Léa", "Tom"),
        saveFile = "dice_state_aventure.json",
        storyLength = "long"
    )

    private fun story() = StoryEntry(
        slug = "aventure",
        title = "L'aventure",
        headerTitle = "",
        subtitle = "Un sous-titre",
        saveFile = "dice_state_aventure.json",
        bgImageB64 = "",
        isCustom = true,
        loreParagraphs = emptyList(),
        defaultTotem = null
    )

    private fun strings(arr: JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    private fun json(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))

    // ======================================================================
    // Journal
    // ======================================================================

    @Test
    fun journal_withoutSession_isEmpty() {
        val (bytes, name, mime) = transfer.exportJournal(null, story(), "aventure")
        assertEquals(0, bytes.size)
        assertEquals("", name)
        assertEquals("", mime)
    }

    @Test
    fun journal_emptyLog_fallsBackToATextFile() {
        val (bytes, name, mime) = transfer.exportJournal(sess, story(), "aventure")
        assertEquals("journal_aventure.txt", name)
        assertEquals("text/plain", mime)
        assertEquals("(aucun chapitre enregistré pour l'instant)", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun journal_withoutSlug_usesAGenericFileName() {
        assertEquals("journal_histoire.txt", transfer.exportJournal(sess, null, null).second)
    }

    // ======================================================================
    // Progression
    // ======================================================================

    @Test
    fun progress_containsTitleSummaryLogAndQuests() {
        sess.storySummary = "PERSONNAGES : Alix"
        sess.storyLog.add("Premier chapitre")
        sess.storyLog.add("Deuxième chapitre")
        sess.sideQuests.add(SideQuest(1, "ami", "ouverte"))

        val (bytes, name, mime) = transfer.exportProgress(sess, story(), "aventure")
        assertEquals("progression_aventure.json", name)
        assertEquals("application/json", mime)

        val data = json(bytes)
        assertEquals("histoires_multiples_progression", data.getString("format"))
        assertEquals(1, data.getInt("version"))
        assertEquals("L'aventure", data.getString("story_title"))
        assertEquals("PERSONNAGES : Alix", data.getString("story_summary"))
        assertEquals(listOf("Premier chapitre", "Deuxième chapitre"), strings(data.getJSONArray("story_log")))
        assertEquals(1, data.getJSONArray("side_quests").length())
        assertEquals("ami", data.getJSONArray("side_quests").getJSONObject(0).getString("kind"))
        assertEquals(sess.nextQuestId, data.getInt("next_quest_id"))
    }

    @Test
    fun progress_withoutStoryOrSlug_usesGenericValues() {
        val (bytes, name, _) = transfer.exportProgress(sess, null, null)
        assertEquals("progression_histoire.json", name)
        assertEquals("", json(bytes).getString("story_title"))
    }

    @Test
    fun progress_canBeReadBackByImportIdentity() {
        sess.storySummary = "Un résumé"
        sess.storyLog.add("Premier chapitre")
        sess.sideQuests.add(SideQuest(4, "objet", "ouverte"))

        val parsed = transfer.importIdentity(String(transfer.exportProgress(sess, story(), "aventure").first, Charsets.UTF_8))
        assertEquals("Un résumé", parsed.getString("story_summary"))
        assertEquals(listOf("Premier chapitre"), strings(parsed.getJSONArray("story_log")))
        assertEquals(1, parsed.getJSONArray("side_quests").length())
        assertEquals(sess.nextQuestId, parsed.getInt("next_quest_id"))
    }

    // ======================================================================
    // Identité
    // ======================================================================

    @Test
    fun identity_unknownStory_isEmpty() {
        val (bytes, name, mime) = transfer.exportIdentity("inconnue", sess)
        assertEquals(0, bytes.size)
        assertEquals("", name)
        assertEquals("", mime)
    }

    @Test
    fun identity_withoutSession_exportsOnlyTheStory() {
        val (bytes, name, mime) = transfer.exportIdentity("aventure", null)
        assertEquals("histoire_aventure.json", name)
        assertEquals("application/json", mime)

        val data = json(bytes)
        assertEquals("des-aventure-identite-v1", data.getString("format"))
        assertEquals("L'aventure", data.getString("title"))
        assertEquals("Patte magique", data.getString("totem_label"))
        assertEquals(listOf("Léa", "Tom"), strings(data.getJSONArray("protagonist_names")))
        assertEquals(0, data.getJSONArray("extra_totems").length())
        assertEquals(0, data.getJSONArray("side_quests").length())
        assertEquals(1, data.getInt("next_quest_id"))
        assertEquals(0, data.getJSONArray("story_log").length())
        assertEquals("", data.getString("story_summary"))
    }

    @Test
    fun identity_doesNotRepeatTheStartingTotem_onlyTheOnesAddedAfterwards() {
        // Le premier totem de la session est celui de départ : il est déjà dans l'identité.
        sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu")
        sess.addCustomTotem("Chat", "miaule, ronronne", "Neuf vies", "", "chat.png")

        val extras = json(transfer.exportIdentity("aventure", sess).first).getJSONArray("extra_totems")
        assertEquals(1, extras.length())
        val chat = extras.getJSONObject(0)
        assertEquals("Chat", chat.getString("label"))
        assertEquals(listOf("miaule", "ronronne"), strings(chat.getJSONArray("powers")))
        assertEquals("Neuf vies", chat.getString("special"))
        // L'image est relue par la fonction passée au constructeur.
        assertEquals("B64:chat.png", chat.getString("image_b64"))
    }

    @Test
    fun identity_carriesTheProgressOfTheSession() {
        sess.storyLog.add("Chapitre un")
        sess.storySummary = "Un résumé"
        sess.sideQuests.add(SideQuest(3, "ami", "ouverte"))

        val data = json(transfer.exportIdentity("aventure", sess).first)
        assertEquals(listOf("Chapitre un"), strings(data.getJSONArray("story_log")))
        assertEquals("Un résumé", data.getString("story_summary"))
        assertEquals(1, data.getJSONArray("side_quests").length())
        assertEquals(sess.nextQuestId, data.getInt("next_quest_id"))
    }

    @Test
    fun identity_roundTripsThroughImportIdentity() {
        sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu")
        sess.addCustomTotem("Chat", "miaule, ronronne", "Neuf vies")
        sess.storyLog.add("Chapitre un")

        val raw = String(transfer.exportIdentity("aventure", sess).first, Charsets.UTF_8)
        val parsed = transfer.importIdentity(raw)
        assertEquals("L'aventure", parsed.getString("title"))
        assertEquals("Un sous-titre", parsed.getString("subtitle"))
        assertEquals("Il était une fois", parsed.getString("lore_text"))
        assertEquals("Patte magique", parsed.getString("totem_label"))
        assertEquals("vol, force", parsed.getString("totem_powers"))
        assertEquals("Souffle de feu", parsed.getString("totem_special"))
        assertEquals(listOf("Léa", "Tom"), strings(parsed.getJSONArray("protagonist_names")))
        assertEquals("long", parsed.getString("story_length"))
        assertEquals(listOf("Chapitre un"), strings(parsed.getJSONArray("story_log")))
        val chat = parsed.getJSONArray("extra_totems").getJSONObject(0)
        assertEquals("Chat", chat.getString("label"))
        assertEquals(listOf("miaule", "ronronne"), strings(chat.getJSONArray("powers")))
    }

    // ======================================================================
    // Import : JSON abîmé ou incomplet
    // ======================================================================

    @Test
    fun import_invalidText_givesEmptyDefaultsInsteadOfAnError() {
        val parsed = transfer.importIdentity("ceci n'est pas du JSON")
        assertEquals("", parsed.getString("title"))
        assertEquals("", parsed.getString("totem_label"))
        assertEquals("long", parsed.getString("story_length"))
        assertEquals("", parsed.getString("story_summary"))
        assertEquals(0, parsed.getJSONArray("extra_totems").length())
        assertEquals(0, parsed.getJSONArray("side_quests").length())
        assertEquals(0, parsed.getJSONArray("story_log").length())
        assertEquals(1, parsed.getInt("next_quest_id"))
    }

    @Test
    fun import_trimsTextAndDropsIncompleteEntries() {
        val raw = """
            {
              "title": "  Titre  ",
              "extra_totems": [
                {"label": "Chat", "powers": ["miaule", "  "]},
                {"emoji": "x"}
              ],
              "side_quests": [
                {"id": 4, "kind": "ami", "status": "ouverte"},
                {"id": 5, "kind": "ami"}
              ],
              "story_log": ["Un", "   ", "Deux", 3]
            }
        """.trimIndent()

        val parsed = transfer.importIdentity(raw)
        assertEquals("Titre", parsed.getString("title"))

        // Totem sans nom écarté ; pouvoir vide écarté.
        val extras = parsed.getJSONArray("extra_totems")
        assertEquals(1, extras.length())
        assertEquals("Chat", extras.getJSONObject(0).getString("label"))
        assertEquals(listOf("miaule"), strings(extras.getJSONObject(0).getJSONArray("powers")))

        // Quête sans statut écartée ; sans compteur fourni, il repart après la plus grande.
        val quests = parsed.getJSONArray("side_quests")
        assertEquals(1, quests.length())
        assertEquals(4, quests.getJSONObject(0).getInt("id"))
        assertEquals(5, parsed.getInt("next_quest_id"))

        // Chapitres vides ou qui ne sont pas du texte écartés.
        assertEquals(listOf("Un", "Deux"), strings(parsed.getJSONArray("story_log")))
    }

    @Test
    fun import_explicitQuestCounter_winsOverTheComputedOne() {
        val raw = """{"next_quest_id": 9, "side_quests": [{"id": 4, "kind": "ami", "status": "ouverte"}]}"""
        assertEquals(9, transfer.importIdentity(raw).getInt("next_quest_id"))
    }

    @Test
    fun import_doesNotTouchTheStoredStories() {
        val before = File(dir, "custom_stories.json").readText()
        transfer.importIdentity("""{"title": "Autre histoire", "totem_label": "Chat"}""")
        assertEquals(before, File(dir, "custom_stories.json").readText())
    }
}
