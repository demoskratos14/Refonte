package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests des règles du jeu principal (DiceSession) : lancers, réglages, menace, sauvegarde. */
class DiceSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var saveFile: File
    private lateinit var session: DiceSession

    @Before
    fun setUp() {
        saveFile = File(tmp.newFolder("save"), "dice_state.json")
        session = DiceSession(saveFile)
    }

    // ---- données de base ----

    @Test
    fun fateFaces_areSixWithUniqueKeysAndEmojis() {
        assertEquals(6, FATE_FACES.size)
        assertEquals(6, FATE_FACES.map { it.key }.toSet().size)
        assertTrue(FATE_FACES.all { it.emoji.isNotEmpty() && it.label.isNotEmpty() })
    }

    @Test
    fun comboNotes_onlyUseKnownFateKeysAndExtremeValues() {
        for ((combo, note) in COMBO_NOTES) {
            assertTrue("valeur ${combo.first}", combo.first == 1 || combo.first == 6)
            assertTrue("clé ${combo.second}", combo.second in FATE_BY_KEY)
            assertTrue(note.isNotBlank())
        }
    }

    @Test
    fun normalizeStoryLength_acceptsVariants() {
        assertEquals(LENGTH_SHORT, normalizeStoryLength("courte"))
        assertEquals(LENGTH_SHORT, normalizeStoryLength("  Short "))
        assertEquals(LENGTH_LONG, normalizeStoryLength("LONGUE"))
        assertEquals(LENGTH_LONG, normalizeStoryLength("long"))
        assertEquals(LENGTH_MEDIUM, normalizeStoryLength("n'importe quoi"))
        assertEquals(LENGTH_MEDIUM, normalizeStoryLength(null))
    }

    // ---- réglages des dés ----

    @Test
    fun toggleAllowedValue_alwaysKeepsAtLeastOne() {
        for (v in 1..5) assertTrue(session.toggleAllowedValue(v))
        assertEquals(listOf(6), session.allowedSuccessValues)
        session.toggleAllowedValue(6) // dernière valeur : ne doit pas se décocher
        assertEquals(listOf(6), session.allowedSuccessValues)
    }

    @Test
    fun toggleAllowedValue_rejectsOutOfRange() {
        assertFalse(session.toggleAllowedValue(0))
        assertFalse(session.toggleAllowedValue(7))
    }

    @Test
    fun toggleAllowedFate_alwaysKeepsAtLeastOne_andRejectsUnknownKey() {
        assertFalse(session.toggleAllowedFate("inconnu"))
        for (face in FATE_FACES.drop(1)) session.toggleAllowedFate(face.key)
        assertEquals(listOf(FATE_FACES.first().key), session.allowedFateKeys)
        session.toggleAllowedFate(FATE_FACES.first().key)
        assertEquals(1, session.allowedFateKeys.size)
    }

    // ---- lancers ----

    @Test
    fun rollSuccess_respectsAllowedValues() {
        for (v in listOf(1, 2, 4, 5, 6)) session.toggleAllowedValue(v)
        repeat(30) { assertEquals(3, session.rollSuccess().success) }
    }

    @Test
    fun rollFate_respectsAllowedSymbols() {
        session.setStoryLength("courte") // évite les quêtes secondaires du ❓
        for (face in FATE_FACES.filter { it.key != "coeur" }) session.toggleAllowedFate(face.key)
        repeat(30) { assertEquals("coeur", session.rollFate().fate) }
    }

    @Test
    fun rollBoth_givesBothValues() {
        session.setStoryLength("courte")
        val r = session.rollBoth()
        assertEquals("both", r.type)
        assertTrue(requireNotNull(r.success) in 1..6)
        assertTrue(requireNotNull(r.fate) in FATE_BY_KEY)
    }

    @Test
    fun historyIds_increase_andUndoRemovesTheLast() {
        val a = session.rollSuccess()
        val b = session.rollSuccess()
        assertEquals(1, a.id)
        assertEquals(2, b.id)
        assertEquals(b.id, session.undoLast()?.id)
        assertEquals(1, session.history.size)
    }

    @Test
    fun undoLast_onEmptyHistory_returnsNull() {
        assertNull(session.undoLast())
    }

    @Test
    fun clearHistory_resetsIds() {
        session.rollSuccess()
        session.rollSuccess()
        session.clearHistory()
        assertEquals(0, session.history.size)
        assertEquals(1, session.nextId)
    }

    // ---- jauge de menace ----

    @Test
    fun threat_triggersAfterRepeatedFailures() {
        for (v in 2..6) session.toggleAllowedValue(v) // seul le 1 reste
        val triggered = (1..10).map { session.rollSuccess().threatTriggered == true }
        // +3 par échec, seuil à THREAT_THRESHOLD : la menace doit se déclencher au 4e échec.
        assertTrue(triggered.any { it })
        assertFalse(triggered.take(3).any { it })
    }

    @Test
    fun missedThreatEvents_countsAnExplosionThatWasNeverSentToTheAi() {
        for (v in 2..6) session.toggleAllowedValue(v) // seul le 1 reste
        // 4 échecs de suite : le 4e fait exploser la jauge. Un 5e lancer ensuite, sans rien envoyer.
        repeat(4) { session.rollSuccess() }
        assertEquals(0, session.missedThreatEvents()) // l'explosion est le dernier lancer : elle part avec lui
        session.rollSuccess()
        assertEquals(1, session.missedThreatEvents())
        session.markLastRollAsSent()
        assertEquals(0, session.missedThreatEvents())
    }

    @Test
    fun threat_lowerThresholdExplodesSooner_andIsClampedBelowTheNewThreshold() {
        for (v in 2..6) session.toggleAllowedValue(v) // seul le 1 reste
        session.applyThreatThreshold(6)
        assertFalse(session.rollSuccess().threatTriggered == true) // 3/6
        assertTrue(session.rollSuccess().threatTriggered == true)  // 6/6 : explose dès le 2e échec

        session.rollSuccess() // 3
        session.rollSuccess() // 6 -> explose, retombe à 0
        session.rollSuccess() // 3
        session.applyThreatThreshold(3) // seuil sous le niveau actuel : jauge ramenée à 2
        assertEquals(2, session.threatLevel)
    }

    @Test
    fun threat_staysAtZeroOnGreatSuccesses() {
        for (v in 1..5) session.toggleAllowedValue(v) // seul le 6 reste
        repeat(20) { session.rollSuccess() }
        assertEquals(0, session.threatLevel)
    }

    // ---- sauvegarde ----

    @Test
    fun load_withoutFile_returnsFalse() {
        assertFalse(DiceSession(File(tmp.root, "absent.json")).load())
    }

    @Test
    fun saveAndLoad_roundTrip() {
        session.toggleAllowedValue(1)
        session.rollSuccess()
        session.rollSuccess()
        session.rollSuccess()

        val reloaded = DiceSession(saveFile)
        assertTrue(reloaded.load())
        assertEquals(3, reloaded.history.size)
        assertEquals(4, reloaded.nextId)
        assertEquals(session.allowedSuccessValues, reloaded.allowedSuccessValues)
        assertFalse(1 in reloaded.allowedSuccessValues)
    }
    // ---- sauvegarde abîmée (coupure pendant l'écriture) ----

    private val backupFile get() = File(saveFile.path + ".bak")
    private val corruptFile get() = File(saveFile.path + ".corrupt")

    @Test
    fun save_leavesNoTemporaryFile() {
        session.rollSuccess()

        assertTrue(saveFile.exists())
        assertFalse(File(saveFile.path + ".tmp").exists())
    }

    @Test
    fun save_keepsPreviousVersionAsBackup() {
        session.rollSuccess()
        session.rollSuccess()

        assertTrue(backupFile.exists())
        val previous = DiceSession(backupFile)
        assertTrue(previous.load())
        assertEquals(1, previous.history.size)
    }

    @Test
    fun load_withCorruptedFile_fallsBackToBackup() {
        session.rollSuccess()
        session.rollSuccess()
        session.rollSuccess()                 // fichier = 3 lancers, copie = 2 lancers
        saveFile.writeText("{\"history\": [")  // fichier tronqué

        val reloaded = DiceSession(saveFile)

        assertTrue(reloaded.load())
        assertEquals(2, reloaded.history.size)
    }

    @Test
    fun load_withTruncatedFileAndNoBackup_doesNotThrow_andStartsFresh() {
        session.rollSuccess()
        val full = saveFile.readText()
        saveFile.writeText(full.take(full.length / 2))

        val reloaded = DiceSession(saveFile)

        assertFalse(reloaded.load())
        assertTrue(reloaded.history.isEmpty())
        assertEquals(1, reloaded.nextId)
    }

    @Test
    fun load_withCorruptedFileAndNoBackup_keepsTheCorruptFileAside() {
        session.rollSuccess()
        saveFile.writeText("pas du json")

        assertFalse(DiceSession(saveFile).load())

        assertFalse(saveFile.exists())
        assertEquals("pas du json", corruptFile.readText())
    }

    @Test
    fun load_whenBothFileAndBackupAreCorrupted_startsFresh() {
        session.rollSuccess()
        session.rollSuccess()
        saveFile.writeText("abîmé 1")
        backupFile.writeText("abîmé 2")

        val reloaded = DiceSession(saveFile)

        assertFalse(reloaded.load())
        assertTrue(reloaded.history.isEmpty())
    }

    @Test
    fun load_afterFallback_nextSaveWritesAHealthyFile() {
        session.rollSuccess()
        session.rollSuccess()
        saveFile.writeText("abîmé")

        val reloaded = DiceSession(saveFile)
        assertTrue(reloaded.load())
        reloaded.rollSuccess()

        val again = DiceSession(saveFile)
        assertTrue(again.load())
        assertEquals(reloaded.history.size, again.history.size)
    }

    @Test
    fun load_withDeletedFile_doesNotResurrectFromBackup() {
        session.rollSuccess()
        session.rollSuccess()
        saveFile.delete() // suppression volontaire (réinitialisation) : la copie .bak reste

        assertFalse(DiceSession(saveFile).load())
    }

    // ---- envoi à l'IA qui échoue : la conversation et le lancer retrouvent leur état d'avant ----

    @Test
    fun truncateAiConversation_removesTheMessagesAddedAfterTheGivenSize() {
        session.addAiMessage("system", "règles", visible = false)
        session.addAiMessage("user", "Je lance le dé")
        session.addAiMessage("assistant", "Le dé roule...")

        session.truncateAiConversation(1)

        assertEquals(1, session.aiConversation.size)
        assertEquals("system", session.aiConversation[0].role)
    }

    @Test
    fun truncateAiConversation_toZero_emptiesTheConversation() {
        session.addAiMessage("system", "règles", visible = false)
        session.addAiMessage("user", "Je lance le dé")

        session.truncateAiConversation(0)

        assertTrue(session.aiConversation.isEmpty())
    }

    @Test
    fun truncateAiConversation_isANoOpWhenNothingWasAddedAfterTheSize() {
        session.addAiMessage("system", "règles", visible = false)

        session.truncateAiConversation(1)
        session.truncateAiConversation(5)
        session.truncateAiConversation(-1)

        assertEquals(1, session.aiConversation.size)
    }

    @Test
    fun truncateAiConversation_isSaved() {
        session.addAiMessage("system", "règles", visible = false)
        session.addAiMessage("user", "Je lance le dé")
        session.truncateAiConversation(1)

        val reloaded = DiceSession(saveFile)
        assertTrue(reloaded.load())
        assertEquals(1, reloaded.aiConversation.size)
    }

    @Test
    fun pendingRoll_staysPendingUntilTheAiHasReceivedIt() {
        session.rollSuccess()
        // Envoi échoué : rien n'est marqué comme transmis, le lancer repartira au réessai.
        assertTrue(session.pendingRoll() != null)

        session.markLastRollAsSent()
        assertNull(session.pendingRoll())
    }

    @Test
    fun rearmQuestAnnouncement_putsBackTheAnnouncementOfAFailedSend() {
        assertEquals(0, session.pendingQuestAnnouncementId())

        session.rearmQuestAnnouncement(7)
        assertEquals(7, session.pendingQuestAnnouncementId())

        // Quête inconnue : rien à annoncer, mais l'attente est bien levée.
        assertNull(session.consumeQuestAnnouncement())
        assertEquals(0, session.pendingQuestAnnouncementId())
    }

    @Test
    fun rearmQuestAnnouncement_ignoresZeroAndNeverOverwritesAPendingOne() {
        session.rearmQuestAnnouncement(0)
        assertEquals(0, session.pendingQuestAnnouncementId())

        session.rearmQuestAnnouncement(3)
        session.rearmQuestAnnouncement(9)
        assertEquals(3, session.pendingQuestAnnouncementId())
    }

    // ---- bilan de fin d'histoire ----

    @Test
    fun totalRolls_countsEveryRollKind() {
        session.rollSuccess()
        session.rollFate()
        session.rollBoth()
        assertEquals(3, session.totalRolls)
    }

    @Test
    fun totalRolls_survivesClearHistory_butNotUndo() {
        session.rollSuccess()
        session.rollSuccess()
        session.undoLast()
        assertEquals(1, session.totalRolls)

        session.clearHistory() // effacer l'historique ne change pas le bilan de la partie
        assertEquals(1, session.totalRolls)
    }

    @Test
    fun totalRolls_secondSouffleReplacesTheRollInsteadOfAddingOne() {
        session.rollSuccess()
        session.secondSouffle()
        assertEquals(1, session.totalRolls)
    }

    @Test
    fun totalRolls_andTotemsUsed_areSavedAndReloaded() {
        session.rollSuccess()
        session.rollFate()

        val reloaded = DiceSession(saveFile)
        assertTrue(reloaded.load())
        assertEquals(2, reloaded.totalRolls)
        assertEquals(0, reloaded.totemsUsed)
    }

    @Test
    fun spendTotemEnergy_withoutEnoughEnergy_isNotCounted() {
        assertFalse(session.spendTotemEnergy("patte"))
        assertEquals(0, session.totemsUsed)
    }

    @Test
    fun resetAll_clearsTheBilanCounters() {
        session.rollSuccess()
        session.markStoryEnded()
        session.resetAll()
        assertEquals(0, session.totalRolls)
        assertEquals(0, session.totemsUsed)
        assertFalse(session.storyEnded)
    }

    @Test
    fun bilan_isAvailableOnlyOnceTheStoryHasEnded() {
        session.setStoryLength("moyenne")
        assertFalse(session.bilanAvailable())
        session.markStoryEnded()
        assertTrue(session.bilanAvailable())
    }

    @Test
    fun bilan_isNeverOfferedForShortStories() {
        session.setStoryLength("courte")
        session.markStoryEnded()
        assertTrue(session.storyEnded)
        assertFalse(session.bilanAvailable())
    }

    @Test
    fun bilan_isOfferedForALongStoryOnlyOnceItHasEnded() {
        session.setStoryLength("longue")
        assertFalse(session.bilanAvailable())
        session.markStoryEnded() // défaite définitive : la seule façon de terminer une histoire longue
        assertTrue(session.bilanAvailable())
    }

    @Test
    fun storyEnded_isSaved() {
        session.markStoryEnded()
        val reloaded = DiceSession(saveFile)
        assertTrue(reloaded.load())
        assertTrue(reloaded.storyEnded)
    }

    @Test
    fun completedQuestCount_countsOnlyFinishedQuests() {
        assertEquals(0, session.completedQuestCount())
    }
}
