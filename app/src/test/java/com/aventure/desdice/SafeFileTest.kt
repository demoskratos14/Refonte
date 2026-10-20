package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests de l'écriture atomique avec copie de secours (SafeFile). */
class SafeFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("data"), "etat.json")
    }

    private val backup get() = File(file.path + ".bak")
    private val tmpFile get() = File(file.path + ".tmp")
    private val corrupt get() = File(file.path + ".corrupt")

    @Test
    fun firstWrite_createsFile_withoutBackupOrLeftover() {
        SafeFile.writeAtomic(file, "v1")

        assertEquals("v1", file.readText())
        assertFalse(backup.exists())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun secondWrite_keepsPreviousVersionAsBackup() {
        SafeFile.writeAtomic(file, "v1")
        SafeFile.writeAtomic(file, "v2")

        assertEquals("v2", file.readText())
        assertEquals("v1", backup.readText())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun invalidCurrentFile_isNotCopiedOverTheGoodBackup() {
        SafeFile.writeAtomic(file, "bon")
        SafeFile.writeAtomic(file, "bon2")          // backup = "bon"
        file.writeText("#abîmé#")                  // le fichier courant est maintenant illisible
        SafeFile.writeAtomic(file, "bon3") { it.startsWith("bon") }

        assertEquals("bon3", file.readText())
        assertEquals("bon", backup.readText())      // la bonne copie n'a pas été écrasée par du abîmé
    }

    @Test
    fun validatorThatThrows_countsAsInvalid() {
        SafeFile.writeAtomic(file, "v1")
        SafeFile.writeAtomic(file, "v2")            // backup = v1
        file.writeText("v-abime")
        SafeFile.writeAtomic(file, "v3") { error("illisible") }

        assertEquals("v3", file.readText())
        assertEquals("v1", backup.readText())
    }

    @Test
    fun candidates_listsFileThenBackup() {
        SafeFile.writeAtomic(file, "v1")
        SafeFile.writeAtomic(file, "v2")

        assertEquals(listOf(file, backup), SafeFile.candidates(file))
    }

    @Test
    fun candidates_isEmpty_whenFileIsMissing_evenIfBackupExists() {
        SafeFile.writeAtomic(file, "v1")
        SafeFile.writeAtomic(file, "v2")
        file.delete() // suppression volontaire : la copie ne doit pas ressusciter l'ancien état

        assertTrue(SafeFile.candidates(file).isEmpty())
    }

    @Test
    fun quarantine_movesFileAside_andKeepsContent() {
        file.writeText("contenu abîmé")

        SafeFile.quarantine(file)

        assertFalse(file.exists())
        assertEquals("contenu abîmé", corrupt.readText())
    }

    @Test
    fun quarantine_replacesAnOlderCorruptCopy() {
        corrupt.writeText("ancien")
        file.writeText("nouveau")

        SafeFile.quarantine(file)

        assertEquals("nouveau", corrupt.readText())
    }

    @Test
    fun quarantine_onMissingFile_doesNothing() {
        SafeFile.quarantine(file)

        assertFalse(corrupt.exists())
    }

    @Test
    fun deleteAll_removesFileBackupAndLeftover() {
        SafeFile.writeAtomic(file, "v1")
        SafeFile.writeAtomic(file, "v2")
        tmpFile.writeText("reste d'une écriture interrompue")

        SafeFile.deleteAll(file)

        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(tmpFile.exists())
    }

    @Test
    fun write_createsMissingParentFolders() {
        val deep = File(tmp.root, "a/b/c/etat.json")

        SafeFile.writeAtomic(deep, "v1")

        assertEquals("v1", deep.readText())
    }
}
