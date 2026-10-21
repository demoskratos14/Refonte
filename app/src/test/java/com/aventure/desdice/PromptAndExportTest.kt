package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de caractérisation de GameEngine, ajoutés AVANT le découpage du fichier : ils figent ce
 * que l'appli produit aujourd'hui pour
 *   - le prompt complet du mode manuel (buildFullPromptText), c'est-à-dire tout le texte
 *     envoyé à l'IA : mécaniques, jauges, longueur d'histoire, héros, journal ;
 *   - les exports (journal, identité, progression), avec un aller-retour export -> import ;
 *   - l'envoi à l'IA sans clé Mistral (rien ne part, aucune erreur).
 *
 * Ces tests ne changent aucun comportement : si l'un d'eux casse après un déplacement de code,
 * c'est que le déplacement a modifié ce que reçoit l'IA ou ce que contient un export.
 *
 * Limites connues, à cause de l'environnement de test (JVM, sans appareil) :
 *   - le PDF du journal n'est pas testé (il utilise Paint/PdfDocument, API Android) ; seul le
 *     repli en texte, utilisé quand le journal est vide, l'est ;
 *   - le prompt du mode automatique (buildAiKickoffMessage, notes ajoutées à chaque appel) n'est
 *     atteignable qu'avec une clé Mistral et un appel réseau : il sera testable une fois ces
 *     fonctions sorties de GameEngine.
 */
class PromptAndExportTest {

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

    private fun writeStories(target: File, vararg metas: StoryMeta) {
        File(target, "custom_stories.json").writeText(JSONArray(metas.map { it.toJson() }).toString())
    }

    /** Écrit l'histoire, la sélectionne, et renvoie le prompt complet du mode manuel. */
    private fun promptFor(story: StoryMeta = meta()): String {
        writeStories(dir, story)
        engine.selectStory(story.slug)
        return engine.buildFullPromptText()
    }

    private fun strings(arr: JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    private fun assertHas(text: String, fragment: String) =
        assertTrue("Texte attendu absent : $fragment\n--- texte complet ---\n$text", text.contains(fragment))

    private fun assertLacks(text: String, fragment: String) =
        assertFalse("Texte inattendu présent : $fragment\n--- texte complet ---\n$text", text.contains(fragment))

    // ======================================================================
    // Prompt complet (mode manuel)
    // ======================================================================

    @Test
    fun fullPrompt_withoutStory_isEmpty() {
        assertEquals("", engine.buildFullPromptText())
    }

    @Test
    fun fullPrompt_hasItsThreeSectionsInOrder() {
        val prompt = promptFor()
        assertTrue(prompt.startsWith("=== CONTEXTE IA NARRATRICE ==="))
        val mechanics = prompt.indexOf("=== CONTEXTE IA NARRATRICE ===")
        val gauges = prompt.indexOf("ÉTAT ACTUEL DES JAUGES")
        val history = prompt.indexOf("--- HISTOIRE DÉJÀ VÉCUE ---")
        assertTrue(mechanics in 0 until gauges)
        assertTrue(gauges in 0 until history)
    }

    @Test
    fun fullPrompt_describesTheTwoDiceAndTheRules() {
        val prompt = promptFor()
        assertHas(prompt, "DÉ DE RÉUSSITE (1-6")
        assertHas(prompt, "1=Échec critique (ses conséquences suivent la section ENJEUX ET ÉCHECS plus bas)")
        assertHas(prompt, "6=Réussite héroïque")
        assertHas(prompt, "DÉ DU DESTIN (6 symboles ; ")
        assertHas(prompt, "JAUGE DE MENACE : +3 sur un 1, -1 sur un 5 ou 6.")
        assertHas(prompt, "À $THREAT_THRESHOLD points, une complication secondaire inattendue éclate")
        assertHas(prompt, "pleine à $TOTEM_ENERGY_THRESHOLD points")
        assertHas(prompt, "RÈGLE ABSOLUE SUR LES JAUGES")
        assertHas(prompt, "TON DU RÉCIT")
    }

    @Test
    fun fullPrompt_containsTheChildSafetyFrame() {
        val prompt = promptFor()
        assertHas(prompt, "CADRE DE SÉCURITÉ")
        assertHas(prompt, "violence graphique")
        assertHas(prompt, "Ne demande jamais d'information personnelle")
        // Le cadre vient après le ton du récit, dans le même bloc de contexte.
        assertTrue(prompt.indexOf("TON DU RÉCIT") < prompt.indexOf("CADRE DE SÉCURITÉ"))
    }

    @Test
    fun fullPrompt_manualMode_hasNoAutomaticModeSections() {
        val prompt = promptFor()
        assertHas(prompt, "ATTENDU DE TOI : histoire collaborative et immersive intégrant mes lancers")
        assertLacks(prompt, "PROPOSITIONS D'ACTIONS CLIQUABLES")
        assertLacks(prompt, "FIN DÉFINITIVE DE L'HISTOIRE")
        // Les balises techniques ne doivent jamais apparaître dans le texte que le joueur copie.
        assertLacks(prompt, "[[QUETE_LANCEE]]")
        assertLacks(prompt, "[[FIN_CHAPITRE]]")
        assertLacks(prompt, "[[HISTOIRE_TERMINEE]]")
    }

    // ---- héros ----

    @Test
    fun fullPrompt_twoHeroes_usesCollectiveAddressing() {
        val prompt = promptFor()
        assertHas(prompt, "adressée à Léa et Tom.")
        assertHas(prompt, "les héros de cette histoire sont Léa et Tom : utilise le vouvoiement collectif")
        assertHas(prompt, "OPTION: Léa ouvre la porte")
    }

    @Test
    fun fullPrompt_threeHeroes_joinsNamesWithCommasAndEt() {
        val prompt = promptFor(meta().copy(protagonistNames = listOf("Alix", "Nao", "Théo")))
        assertHas(prompt, "adressée à Alix, Nao et Théo.")
    }

    @Test
    fun fullPrompt_oneHero_usesTu() {
        val prompt = promptFor(meta().copy(protagonistNames = listOf("Alix")))
        assertHas(prompt, "adressée à Alix.")
        assertHas(prompt, "tu t'adresses toujours à Alix en 'tu'.")
        assertLacks(prompt, "vouvoiement collectif")
    }

    @Test
    fun fullPrompt_noHero_fallsBackToThePlayer() {
        val prompt = promptFor(meta().copy(protagonistNames = emptyList()))
        assertHas(prompt, "tu t'adresses toujours au joueur en 'tu'.")
        assertLacks(prompt, "vouvoiement collectif")
    }

    // ---- totems et jauges ----

    @Test
    fun fullPrompt_listsTheStarterTotemWithPowersAndSpecial() {
        val prompt = promptFor()
        // Sans emoji choisi, la patte est l'icône par défaut.
        assertHas(prompt, "\uD83D\uDC3EPatte magique: vol, force — spé: Souffle de feu")
    }

    @Test
    fun fullPrompt_totemWithoutPowers_saysSo() {
        promptFor()
        engine.addCustomTotem("Chat")
        val prompt = engine.buildFullPromptText()
        assertHas(prompt, "Chat: (pouvoirs non précisés)")
    }

    @Test
    fun fullPrompt_gaugesStartAtZeroForEveryTotem() {
        promptFor()
        engine.addCustomTotem("Chat", powers = "miaule")
        val prompt = engine.buildFullPromptText()
        assertHas(prompt, "Jauge de menace : 0/$THREAT_THRESHOLD.")
        assertHas(prompt, "Jauges totémiques : Patte magique: 0/$TOTEM_ENERGY_THRESHOLD | Chat: 0/$TOTEM_ENERGY_THRESHOLD.")
    }

    @Test
    fun fullPrompt_followsTotemEdits() {
        promptFor()
        engine.updateCustomTotem(starterKey, listOf("vol", "nage"), "Rugissement")
        val prompt = engine.buildFullPromptText()
        assertHas(prompt, "POUVOIRS ACTUELS DES TOTEMS")
        assertHas(prompt, "Patte magique -> vol, nage ; capacité spéciale : Rugissement")
        assertLacks(prompt, "Souffle de feu")
    }

    // ---- longueur d'histoire ----

    @Test
    fun fullPrompt_longStory_isInChaptersWithoutProgressCount() {
        val prompt = promptFor(meta(length = "long"))
        assertHas(prompt, "FORMAT DE L'HISTOIRE : LONG, en CHAPITRES")
        assertHas(prompt, "cette quête, COURTE, se lance automatiquement à la fin du chapitre")
        assertLacks(prompt, "RAPPEL DE PROGRESSION")
    }

    @Test
    fun fullPrompt_shortStory_countsTheNarratedExchanges() {
        val first = promptFor(meta(length = "short"))
        assertHas(first, "FORMAT DE L'HISTOIRE : COURT")
        assertHas(first, "déclenche un ÉVÉNEMENT SOUDAIN inattendu")
        assertHas(first, "0 échange(s) narré(s) jusqu'ici, sur une dizaine visée au total.")

        engine.session!!.addAiMessage("assistant", "Le héros avance dans la forêt.")
        val second = engine.buildFullPromptText()
        assertHas(second, "1 échange(s) narré(s) jusqu'ici, sur une dizaine visée au total.")
    }

    @Test
    fun fullPrompt_mediumStory_hasItsOwnFormatAndProgressRange() {
        val prompt = promptFor(meta(length = "medium"))
        assertHas(prompt, "FORMAT DE L'HISTOIRE : MOYEN -- entre 20 et 30 échanges")
        assertHas(prompt, "sur 20 à 30 visés au total.")
        assertHas(prompt, "se construit en parallèle de l'histoire principale")
    }

    // ---- univers, objectif moral, journal ----

    @Test
    fun fullPrompt_includesTheStoryLore() {
        assertHas(promptFor(), "Il était une fois")
    }

    @Test
    fun fullPrompt_moralGoal_isIncludedOnlyWhenSet() {
        val without = promptFor(meta())
        assertLacks(without, "OBJECTIF MORAL DU RÉCIT")

        val with = promptFor(meta().copy(moralGoal = "Le courage"))
        assertHas(with, "OBJECTIF MORAL DU RÉCIT")
        assertHas(with, "\"Le courage\"")
    }

    @Test
    fun fullPrompt_emptyJournal_showsThePlaceholder() {
        val prompt = promptFor()
        assertHas(prompt, "(aucun chapitre enregistré pour l'instant, on commence une aventure toute neuve)")
    }

    @Test
    fun fullPrompt_journal_listsChaptersInOrderAndDropsThePlaceholder() {
        promptFor()
        engine.addStoryEntry("Le héros part.")
        engine.addStoryEntry("Le héros arrive.")
        val prompt = engine.buildFullPromptText()
        assertHas(prompt, "--- Chapitre 1 ---\nLe héros part.")
        assertHas(prompt, "--- Chapitre 2 ---\nLe héros arrive.")
        assertTrue(prompt.indexOf("--- Chapitre 1 ---") < prompt.indexOf("--- Chapitre 2 ---"))
        assertLacks(prompt, "(aucun chapitre enregistré")
    }

    // ======================================================================
    // Envoi à l'IA sans clé Mistral : rien ne part, aucune erreur
    // ======================================================================

    @Test
    fun sendAiMessage_withoutKey_sendsNothingAndReportsNoError() {
        promptFor()
        val state = engine.sendAiMessage("Bonjour")
        assertFalse(state.has("ai_error"))
        assertTrue(engine.session!!.aiConversation.isEmpty())
    }

    @Test
    fun sendFullPrompt_withoutKey_sendsNothingAndReportsNoError() {
        promptFor()
        val state = engine.sendFullPrompt()
        assertFalse(state.has("ai_error"))
        assertTrue(engine.session!!.aiConversation.isEmpty())
    }

    @Test
    fun aiSending_withoutStory_isHarmless() {
        assertFalse(engine.sendAiMessage("Bonjour").has("ai_error"))
        assertFalse(engine.sendFullPrompt().has("ai_error"))
    }

    // ======================================================================
    // Export du journal
    // ======================================================================

    @Test
    fun exportJournal_withoutStory_isEmpty() {
        val (bytes, name, mime) = engine.exportJournal()
        assertEquals(0, bytes.size)
        assertEquals("", name)
        assertEquals("", mime)
    }

    @Test
    fun exportJournal_emptyLog_fallsBackToATextFile() {
        promptFor()
        val (bytes, name, mime) = engine.exportJournal()
        assertEquals("journal_aventure.txt", name)
        assertEquals("text/plain", mime)
        assertEquals("(aucun chapitre enregistré pour l'instant)", String(bytes, Charsets.UTF_8))
    }

    // ======================================================================
    // Export de la progression
    // ======================================================================

    @Test
    fun exportProgress_withoutStory_isEmpty() {
        val (bytes, name, mime) = engine.exportProgress()
        assertEquals(0, bytes.size)
        assertEquals("", name)
        assertEquals("", mime)
    }

    @Test
    fun exportProgress_containsTitleLogSummaryAndQuestCounter() {
        promptFor()
        engine.addStoryEntry("Chapitre un")
        engine.session!!.storySummary = "Résumé long terme"

        val (bytes, name, mime) = engine.exportProgress()
        assertEquals("progression_aventure.json", name)
        assertEquals("application/json", mime)

        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("histoires_multiples_progression", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals("L'aventure", json.getString("story_title"))
        assertEquals("Résumé long terme", json.getString("story_summary"))
        assertEquals(listOf("Chapitre un"), strings(json.getJSONArray("story_log")))
        assertEquals(0, json.getJSONArray("side_quests").length())
        assertEquals(1, json.getInt("next_quest_id"))
    }

    @Test
    fun exportProgress_canBeReimportedOnAFreshInstall() {
        promptFor()
        engine.addStoryEntry("Chapitre un")
        engine.addStoryEntry("Chapitre deux")
        engine.session!!.storySummary = "Résumé long terme"
        val exported = JSONObject(String(engine.exportProgress().first, Charsets.UTF_8))

        // Autre téléphone / réinstallation : un dossier de données vierge, même histoire.
        val otherDir = tmp.newFolder("autre")
        writeStories(otherDir, meta())
        val fresh = GameEngine(otherDir)
        fresh.selectStory("aventure")
        assertTrue(fresh.session!!.storyLog.isEmpty())

        val state = fresh.applyImportedProgress(
            exported.getJSONArray("side_quests"),
            exported.getInt("next_quest_id"),
            exported.getJSONArray("story_log"),
            exported.getString("story_summary")
        )
        assertEquals(listOf("Chapitre un", "Chapitre deux"), strings(state.getJSONArray("story_log")))
        assertEquals("Résumé long terme", fresh.session!!.storySummary)
    }

    // ======================================================================
    // Export de l'identité
    // ======================================================================

    @Test
    fun exportIdentity_withoutStory_isEmpty() {
        val (bytes, name, mime) = engine.exportIdentity()
        assertEquals(0, bytes.size)
        assertEquals("", name)
        assertEquals("", mime)
    }

    @Test
    fun exportIdentity_containsTheStoryIdentity_andOnlyTheExtraTotems() {
        promptFor()
        engine.addCustomTotem("Chat", powers = "miaule, ronronne", special = "Neuf vies")
        engine.addStoryEntry("Chapitre un")

        val (bytes, name, mime) = engine.exportIdentity()
        assertEquals("histoire_aventure.json", name)
        assertEquals("application/json", mime)

        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("des-aventure-identite-v1", json.getString("format"))
        assertEquals("L'aventure", json.getString("title"))
        assertEquals("Un sous-titre", json.getString("subtitle"))
        assertEquals("Il était une fois", json.getString("lore_text"))
        assertEquals("Patte magique", json.getString("totem_label"))
        assertEquals("vol, force", json.getString("totem_powers"))
        assertEquals("Souffle de feu", json.getString("totem_special"))
        assertEquals(listOf("Léa", "Tom"), strings(json.getJSONArray("protagonist_names")))
        assertEquals("long", json.getString("story_length"))
        assertEquals(listOf("Chapitre un"), strings(json.getJSONArray("story_log")))

        // Le totem de départ est déjà dans l'identité : seuls ceux ajoutés ensuite sont "extra".
        val extras = json.getJSONArray("extra_totems")
        assertEquals(1, extras.length())
        val chat = extras.getJSONObject(0)
        assertEquals("Chat", chat.getString("label"))
        assertEquals(listOf("miaule", "ronronne"), strings(chat.getJSONArray("powers")))
        assertEquals("Neuf vies", chat.getString("special"))
    }

    @Test
    fun exportIdentity_roundTripsThroughImportIdentity() {
        promptFor()
        engine.addCustomTotem("Chat", powers = "miaule, ronronne", special = "Neuf vies")
        engine.addStoryEntry("Chapitre un")
        val raw = String(engine.exportIdentity().first, Charsets.UTF_8)

        val parsed = engine.importIdentity(raw)
        assertEquals("L'aventure", parsed.getString("title"))
        assertEquals("Un sous-titre", parsed.getString("subtitle"))
        assertEquals("Il était une fois", parsed.getString("lore_text"))
        assertEquals("Patte magique", parsed.getString("totem_label"))
        assertEquals("vol, force", parsed.getString("totem_powers"))
        assertEquals(listOf("Léa", "Tom"), strings(parsed.getJSONArray("protagonist_names")))
        assertEquals(listOf("Chapitre un"), strings(parsed.getJSONArray("story_log")))
        val chat = parsed.getJSONArray("extra_totems").getJSONObject(0)
        assertEquals("Chat", chat.getString("label"))
        assertEquals(listOf("miaule", "ronronne"), strings(chat.getJSONArray("powers")))
    }

    // ---- cadre de contenu selon l'âge ----

    @Test
    fun fullPrompt_withoutAge_usesTheChildFrame() {
        val prompt = promptFor()
        assertHas(prompt, "CADRE DE SÉCURITÉ")
        assertLacks(prompt, "CADRE DE CONTENU")
        assertLacks(prompt, "le plus jeune joueur a")
    }

    @Test
    fun fullPrompt_childAge_isMentionedAndAdaptsTheLanguage() {
        val prompt = promptFor(meta().copy(playerAge = 6))
        assertHas(prompt, "CADRE DE SÉCURITÉ")
        assertHas(prompt, "un enfant de 6 ans")
        assertHas(prompt, "phrases courtes et mots simples")
        assertHas(prompt, "emojis")
    }

    @Test
    fun fullPrompt_age11_isStillTheChildFrame_andAge12IsTheTeenFrame() {
        assertHas(promptFor(meta().copy(playerAge = 11)), "CADRE DE SÉCURITÉ")
        assertHas(promptFor(meta().copy(playerAge = 12)), "CADRE DE CONTENU — ADOLESCENT")
    }

    @Test
    fun fullPrompt_teenFrame_allowsFearButKeepsTheLimits() {
        val prompt = promptFor(meta().copy(playerAge = 14))
        assertHas(prompt, "TON DU RÉCIT")
        assertHas(prompt, "CADRE DE CONTENU — ADOLESCENT")
        assertHas(prompt, "le plus jeune joueur a 14 ans")
        assertHas(prompt, "installer du suspense, de la peur")
        assertHas(prompt, "contenu sexuel ou érotique")
        assertHas(prompt, "Ne demande jamais d'information personnelle")
        assertLacks(prompt, "CADRE DE SÉCURITÉ")
    }

    @Test
    fun fullPrompt_adultFrame_allowsDarkThemesButNoExplicitSexualContent() {
        val prompt = promptFor(meta().copy(playerAge = 30))
        assertHas(prompt, "TON DU RÉCIT")
        assertHas(prompt, "CADRE DE CONTENU — ADULTE")
        assertHas(prompt, "horreur")
        assertHas(prompt, "aucun contenu sexuel explicite")
        assertHas(prompt, "aucune sexualisation de mineurs")
        assertHas(prompt, "Ne demande jamais d'information personnelle")
        assertLacks(prompt, "CADRE DE SÉCURITÉ")
    }

    @Test
    fun fullPrompt_teenAndAdultFrames_tellTheAiToSoftenIfThePlayerSeemsYounger() {
        assertHas(promptFor(meta().copy(playerAge = 15)), "nettement plus jeune que l'âge déclaré")
        assertHas(promptFor(meta().copy(playerAge = 40)), "nettement plus jeune que l'âge déclaré")
        assertLacks(promptFor(meta().copy(playerAge = 8)), "nettement plus jeune que l'âge déclaré")
    }
}
