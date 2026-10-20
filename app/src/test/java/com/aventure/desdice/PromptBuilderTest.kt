package com.aventure.desdice

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
 * Tests directs de PromptBuilder, les textes envoyés à l'IA narratrice.
 *
 * PromptAndExportTest couvre déjà le prompt complet du mode MANUEL, mais seulement à travers
 * GameEngine. Ici on teste PromptBuilder tout seul, y compris ce qui n'était pas atteignable
 * avant (il fallait une clé Mistral et un appel réseau) : le message de démarrage du mode
 * automatique et les rappels ajoutés à chaque appel (progression, jauges, fin de chapitre,
 * quête en attente), ainsi que le texte d'événement d'un lancer.
 *
 * Chaque test construit lui-même sa session et son histoire : rien n'est tiré au hasard.
 */
class PromptBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sess: DiceSession

    @Before
    fun setUp() {
        sess = DiceSession(File(tmp.newFolder("save"), "dice_state.json"))
    }

    // ---- aides ----

    private fun story(
        length: String = "long",
        names: List<String> = emptyList(),
        lore: List<String> = emptyList(),
        moralGoal: String = "",
        fixedAlliesLine: String = "",
        playerAge: Int = 0
    ) = StoryEntry(
        slug = "aventure",
        title = "L'aventure",
        headerTitle = "",
        subtitle = "Un sous-titre",
        saveFile = "dice_state_aventure.json",
        bgImageB64 = "",
        protagonistNames = names,
        fixedAlliesLine = fixedAlliesLine,
        isCustom = true,
        loreParagraphs = lore,
        defaultTotem = null,
        storyLength = length,
        moralGoal = moralGoal,
        playerAge = playerAge
    )

    private fun assertHas(text: String, fragment: String) =
        assertTrue("Texte attendu absent : $fragment\n--- texte complet ---\n$text", text.contains(fragment))

    private fun assertLacks(text: String, fragment: String) =
        assertFalse("Texte inattendu présent : $fragment\n--- texte complet ---\n$text", text.contains(fragment))

    private fun narrate(exchanges: Int) {
        repeat(exchanges) {
            sess.addAiMessage("user", "Je fais quelque chose $it")
            sess.addAiMessage("assistant", "Le narrateur répond $it")
        }
    }

    // ======================================================================
    // Balises
    // ======================================================================

    @Test
    fun tags_areTheDocumentedMarkers() {
        assertEquals("[[QUETE_LANCEE]]", PromptBuilder.questLaunchedTag)
        assertEquals("[[HISTOIRE_TERMINEE]]", PromptBuilder.storyEndedTag)
        assertEquals("[[FIN_CHAPITRE]]", PromptBuilder.chapterEndedTag)
    }

    // ======================================================================
    // Contexte de mécaniques
    // ======================================================================

    @Test
    fun mechanicsContext_autoMode_asksForClickableOptionsAndTheEndTag() {
        val text = PromptBuilder.buildMechanicsContext(sess, story(), autoMode = true)
        assertTrue(text.startsWith("=== CONTEXTE IA NARRATRICE ==="))
        assertHas(text, "l'histoire se poursuit directement dans cette conversation")
        assertHas(text, "PROPOSITIONS D'ACTIONS CLIQUABLES")
        assertHas(text, "OPTION: <action très courte>")
        assertHas(text, "FIN DÉFINITIVE DE L'HISTOIRE")
        assertHas(text, PromptBuilder.storyEndedTag)
    }

    @Test
    fun mechanicsContext_manualMode_hasNeitherOptionsNorEndTag() {
        val text = PromptBuilder.buildMechanicsContext(sess, story(), autoMode = false)
        assertHas(text, "puis l'histoire déjà vécue")
        assertLacks(text, "PROPOSITIONS D'ACTIONS CLIQUABLES")
        assertLacks(text, "FIN DÉFINITIVE DE L'HISTOIRE")
        assertLacks(text, PromptBuilder.storyEndedTag)
    }

    @Test
    fun mechanicsContext_progressReminder_isOnlyInManualMode() {
        // Mode auto : le rappel est ajouté à part à chaque appel, jamais dans ce texte figé.
        val short = story(length = "short")
        assertLacks(PromptBuilder.buildMechanicsContext(sess, short, autoMode = true), "RAPPEL DE PROGRESSION")
        assertHas(PromptBuilder.buildMechanicsContext(sess, short, autoMode = false), "RAPPEL DE PROGRESSION")
    }

    @Test
    fun mechanicsContext_describesTheStoryLength() {
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("short"), true), "FORMAT DE L'HISTOIRE : COURT")
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("medium"), true), "FORMAT DE L'HISTOIRE : MOYEN")
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("long"), true), "FORMAT DE L'HISTOIRE : LONG, en CHAPITRES")
        // Sans histoire sélectionnée : format long par défaut.
        assertHas(PromptBuilder.buildMechanicsContext(sess, null, true), "FORMAT DE L'HISTOIRE : LONG, en CHAPITRES")
    }

    @Test
    fun mechanicsContext_questionRuleFollowsTheStoryLength() {
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("short"), true), "ÉVÉNEMENT SOUDAIN")
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("medium"), true), "en parallèle de l'histoire principale")
        assertHas(PromptBuilder.buildMechanicsContext(sess, story("long"), true), "se lance automatiquement à la fin du chapitre")
    }

    @Test
    fun mechanicsContext_addressesTheHeroes() {
        assertHas(PromptBuilder.buildMechanicsContext(sess, story(names = emptyList()), true), "tu t'adresses toujours au joueur en 'tu'.")
        assertHas(PromptBuilder.buildMechanicsContext(sess, story(names = listOf("Alix")), true), "tu t'adresses toujours à Alix en 'tu'.")
        val duo = PromptBuilder.buildMechanicsContext(sess, story(names = listOf("Alix", "Nao")), true)
        assertHas(duo, "adressée à Alix et Nao")
        assertHas(duo, "vouvoiement collectif")
        assertHas(duo, "OPTION: Alix ouvre la porte")
    }

    @Test
    fun mechanicsContext_withoutHeroNames_saysTheStoryIsForThePlayer() {
        for (auto in listOf(true, false)) {
            val text = PromptBuilder.buildMechanicsContext(sess, story(names = emptyList()), auto)
            assertHas(text, "adressée au joueur. Mécaniques")
            assertLacks(text, "à le joueur")
        }
        // Prénoms vides ou blancs : même résultat que sans prénom.
        assertHas(PromptBuilder.buildMechanicsContext(sess, story(names = listOf("", "  ")), true), "adressée au joueur.")
    }

    @Test
    fun mechanicsContext_listsTheTotemsOrSaysThereAreNone() {
        assertHas(PromptBuilder.buildMechanicsContext(sess, story(), true), "(aucun pour l'instant -- tout reste à découvrir en jouant)")
        sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu")
        val text = PromptBuilder.buildMechanicsContext(sess, story(), true)
        assertHas(text, "Patte magique: vol, force — spé: Souffle de feu")
        assertLacks(text, "(aucun pour l'instant")
    }

    @Test
    fun mechanicsContext_includesLoreMoralGoalAndFixedAllies() {
        val s = story(
            lore = listOf("Il était une fois un royaume.", "Le dragon dormait."),
            moralGoal = "Le courage",
            fixedAlliesLine = "Alliés fixes : un renard"
        )
        val text = PromptBuilder.buildMechanicsContext(sess, s, true)
        assertHas(text, "Il était une fois un royaume.")
        assertHas(text, "Le dragon dormait.")
        assertHas(text, "OBJECTIF MORAL DU RÉCIT")
        assertHas(text, "\"Le courage\"")
        assertHas(text, "Alliés fixes : un renard")
    }

    @Test
    fun mechanicsContext_blankMoralGoal_isLeftOut() {
        assertLacks(PromptBuilder.buildMechanicsContext(sess, story(moralGoal = "   "), true), "OBJECTIF MORAL DU RÉCIT")
    }

    // ======================================================================
    // Rappels recalculés à chaque appel
    // ======================================================================

    @Test
    fun progressNote_countsOnlyTheNarratorsAnswers() {
        narrate(2)
        sess.addAiMessage("user", "Un message de plus, sans réponse")
        assertEquals(
            "RAPPEL DE PROGRESSION (ne mentionne jamais ce rappel technique au joueur) : " +
                "2 échange(s) narré(s) jusqu'ici, sur une dizaine visée au total.",
            PromptBuilder.buildStoryProgressNote(sess, story("short"))
        )
    }

    @Test
    fun progressNote_mediumStory_aimsAtTwentyToThirty() {
        val note = PromptBuilder.buildStoryProgressNote(sess, story("medium"))
        assertNotNull(note)
        assertHas(note!!, "0 échange(s) narré(s) jusqu'ici, sur 20 à 30 visés au total.")
    }

    @Test
    fun progressNote_longStoryOrNoStory_hasNoCountdown() {
        assertNull(PromptBuilder.buildStoryProgressNote(sess, story("long")))
        assertNull(PromptBuilder.buildStoryProgressNote(sess, null))
    }

    @Test
    fun gaugeNote_withoutTotems_showsTheThreatAndSaysNoTotem() {
        val note = PromptBuilder.buildGaugeStateNote(sess)
        assertTrue(note.startsWith("ÉTAT ACTUEL DES JAUGES"))
        assertHas(note, "Jauge de menace : 0/$THREAT_THRESHOLD.")
        assertHas(note, "aucun totem/allié pour l'instant")
        assertLacks(note, "POUVOIRS ACTUELS DES TOTEMS")
    }

    @Test
    fun gaugeNote_showsEachTotemsEnergy_andFlagsAFullOne() {
        val key = sess.addCustomTotem("Patte magique")!!
        sess.addCustomTotem("Baguette")
        sess.totemEnergy[key] = TOTEM_ENERGY_THRESHOLD
        val note = PromptBuilder.buildGaugeStateNote(sess)
        assertHas(note, "Patte magique: $TOTEM_ENERGY_THRESHOLD/$TOTEM_ENERGY_THRESHOLD (PLEINE, utilisable)")
        assertHas(note, "Baguette: 0/$TOTEM_ENERGY_THRESHOLD")
        assertLacks(note, "Baguette: 0/$TOTEM_ENERGY_THRESHOLD (PLEINE")
    }

    @Test
    fun gaugeNote_repeatsTheCurrentPowersOfDescribedTotemsOnly() {
        sess.addCustomTotem("Patte magique", "vol, force", "Souffle de feu")
        sess.addCustomTotem("Baguette")
        val note = PromptBuilder.buildGaugeStateNote(sess)
        assertHas(note, "POUVOIRS ACTUELS DES TOTEMS")
        assertHas(note, "Patte magique -> vol, force ; capacité spéciale : Souffle de feu")
        assertLacks(note, "Baguette ->")
    }

    @Test
    fun chapterEndNote_isOnlyForLongStories() {
        val note = PromptBuilder.buildChapterEndNote(story("long"))
        assertNotNull(note)
        assertHas(note!!, PromptBuilder.chapterEndedTag)
        assertNull(PromptBuilder.buildChapterEndNote(story("short")))
        assertNull(PromptBuilder.buildChapterEndNote(story("medium")))
        // Sans histoire : format long par défaut.
        assertNotNull(PromptBuilder.buildChapterEndNote(null))
    }

    @Test
    fun pendingQuestNote_askForTheQuestAtTheEndOfTheChapter() {
        sess.sideQuests.add(SideQuest(1, "ami", "en_attente", QUEST_MODE_END_OF_CHAPTER))
        val note = PromptBuilder.buildPendingQuestNote(sess, story("long"))
        assertNotNull(note)
        assertHas(note!!, "se faire un nouvel ami")
        assertHas(note, PromptBuilder.questLaunchedTag)
    }

    @Test
    fun pendingQuestNote_objectQuest_usesItsOwnWording() {
        sess.sideQuests.add(SideQuest(1, "objet", "en_attente", QUEST_MODE_END_OF_CHAPTER))
        assertHas(PromptBuilder.buildPendingQuestNote(sess, story("long"))!!, "trouver un nouvel objet (ou un nouveau totem)")
    }

    @Test
    fun pendingQuestNote_isNullWithoutAPendingQuest() {
        assertNull(PromptBuilder.buildPendingQuestNote(sess, story("long")))
        sess.sideQuests.add(SideQuest(1, "ami", "ouverte", QUEST_MODE_END_OF_CHAPTER))
        assertNull(PromptBuilder.buildPendingQuestNote(sess, story("long")))
    }

    @Test
    fun pendingQuestNote_isNullOutsideLongStories() {
        sess.sideQuests.add(SideQuest(1, "ami", "en_attente", QUEST_MODE_END_OF_CHAPTER))
        assertNull(PromptBuilder.buildPendingQuestNote(sess, story("medium")))
        assertNull(PromptBuilder.buildPendingQuestNote(sess, story("short")))
    }

    // ======================================================================
    // Prompt complet (mode manuel) et message de démarrage (mode auto)
    // ======================================================================

    @Test
    fun fullPrompt_isMechanicsThenGaugesThenTheLivedStory() {
        val s = story()
        sess.storyLog.add("Premier chapitre")
        val prompt = PromptBuilder.buildFullPrompt(sess, s)
        assertTrue(prompt.startsWith(PromptBuilder.buildMechanicsContext(sess, s, autoMode = false)))
        assertHas(prompt, PromptBuilder.buildGaugeStateNote(sess))
        assertTrue(prompt.endsWith("--- HISTOIRE DÉJÀ VÉCUE ---\n--- Chapitre 1 ---\nPremier chapitre"))
    }

    @Test
    fun fullPrompt_withAnEmptyLog_saysTheAdventureIsNew() {
        val prompt = PromptBuilder.buildFullPrompt(sess, story())
        assertTrue(prompt.endsWith("(aucun chapitre enregistré pour l'instant, on commence une aventure toute neuve)"))
    }

    @Test
    fun kickoff_withoutChapters_startsTheFirstChapterForTheHeroes() {
        val text = PromptBuilder.buildAiKickoffMessage(sess, story(names = listOf("Alix", "Nao", "Théo")))
        assertTrue(text.startsWith("=== CONTEXTE IA NARRATRICE ==="))
        assertHas(text, "Aucun chapitre n'a encore été joué.")
        assertHas(text, "présente la situation de départ de Alix, Nao et Théo")
        assertLacks(text, "--- HISTOIRE DÉJÀ VÉCUE ---")
    }

    @Test
    fun kickoff_withoutHeroes_talksToThePlayer() {
        val text = PromptBuilder.buildAiKickoffMessage(sess, story())
        assertHas(text, "Aucun chapitre n'a encore été joué.")
        assertHas(text, "présente la situation de départ du joueur")
        assertLacks(text, "de le joueur")
    }

    @Test
    fun kickoff_withChapters_continuesTheStory() {
        sess.storyLog.add("Premier chapitre")
        sess.storyLog.add("Deuxième chapitre")
        val text = PromptBuilder.buildAiKickoffMessage(sess, story())
        assertHas(text, "--- HISTOIRE DÉJÀ VÉCUE ---")
        assertHas(text, "--- Chapitre 1 ---\nPremier chapitre")
        assertHas(text, "--- Chapitre 2 ---\nDeuxième chapitre")
        assertTrue(text.endsWith("Commence maintenant le prochain chapitre de l'histoire, dans la continuité directe de ce qui précède."))
        assertLacks(text, "Aucun chapitre n'a encore été joué.")
    }

    // ======================================================================
    // Texte d'événement d'un lancer
    // ======================================================================

    @Test
    fun eventText_forASuccessRoll_isTheRecordDescription() {
        val record = RollRecord(id = 1, type = "success", success = 6, fate = null)
        val text = PromptBuilder.buildAiEventText(sess, record)
        assertEquals(sess.describeRecord(record), text)
        assertTrue(text.startsWith("Dé de réussite : 6"))
    }

    @Test
    fun eventText_forTheExclamationFace_addsTheHelpNote() {
        val record = RollRecord(id = 1, type = "fate", success = null, fate = "exclamation")
        val face = FATE_BY_KEY.getValue("exclamation")
        val note = "${face.emoji} ${face.label} : ${face.desc}"
        assertEquals(sess.describeRecord(record) + "\n" + note, PromptBuilder.buildAiEventText(sess, record))
    }

    @Test
    fun eventText_forOtherFaces_addsNothing() {
        for (face in FATE_FACES.filter { it.key != "exclamation" }) {
            val record = RollRecord(id = 1, type = "fate", success = null, fate = face.key)
            assertEquals(sess.describeRecord(record), PromptBuilder.buildAiEventText(sess, record))
        }
    }

    @Test
    fun contentFrame_isTheSameInAutoAndManualMode_perTier() {
        for (age in listOf(0, 8, 14, 25)) {
            val auto = PromptBuilder.buildMechanicsContext(sess, story(playerAge = age), true)
            val manual = PromptBuilder.buildMechanicsContext(sess, story(playerAge = age), false)
            val frame = PromptBuilder.buildContentFrame(age).joinToString("\n")
            assertHas(auto, frame)
            assertHas(manual, frame)
        }
    }

    @Test
    fun contentFrame_framesDifferByTier() {
        val child = PromptBuilder.buildContentFrame(8).joinToString("\n")
        val teen = PromptBuilder.buildContentFrame(14).joinToString("\n")
        val adult = PromptBuilder.buildContentFrame(30).joinToString("\n")
        assertTrue(child != teen && teen != adult && child != adult)
        assertHas(child, "CADRE DE SÉCURITÉ")
        assertHas(teen, "CADRE DE CONTENU — ADOLESCENT")
        assertHas(adult, "CADRE DE CONTENU — ADULTE")
    }

    @Test
    fun contentFrame_outOfRangeAges_followTheNormalisedTier() {
        assertEquals(PromptBuilder.buildContentFrame(0), PromptBuilder.buildContentFrame(-7))
        assertEquals(PromptBuilder.buildContentFrame(110), PromptBuilder.buildContentFrame(900))
    }

    @Test
    fun contentFrame_everyTierHasFearStakesAndPuzzleSections() {
        for (age in listOf(0, 8, 14, 30)) {
            val frame = PromptBuilder.buildContentFrame(age).joinToString("\n")
            assertHas(frame, "PEUR ET MONSTRES")
            assertHas(frame, "ENJEUX ET ÉCHECS")
            assertHas(frame, "PROBLÈMES ET ÉNIGMES")
        }
    }

    @Test
    fun contentFrame_permanentDefeat_isForbiddenForChildren_rareForTeens_possibleForAdults() {
        val child = PromptBuilder.buildContentFrame(8).joinToString("\n")
        val teen = PromptBuilder.buildContentFrame(14).joinToString("\n")
        val adult = PromptBuilder.buildContentFrame(30).joinToString("\n")
        assertHas(child, "n'est JAMAIS définitif")
        assertHas(teen, "reste possible mais RARE")
        assertHas(adult, "est possible quand les mauvais choix et les mauvais jets s'accumulent")
        assertHas(teen, "jamais fatal")
        assertHas(adult, "jamais sur un seul échec critique")
    }

    @Test
    fun mechanicsContext_noLongerClaimsThatNoFailureIsEverFinal() {
        val prompt = PromptBuilder.buildMechanicsContext(sess, story(playerAge = 30), true)
        assertLacks(prompt, "jamais de fin d'histoire même sur un 1")
        assertLacks(prompt, "jamais la fin de l'histoire")
    }
}
