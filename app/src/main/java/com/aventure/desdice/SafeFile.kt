package com.aventure.desdice

import java.io.File
import java.io.FileOutputStream

/**
 * Écriture et lecture « à l'épreuve des coupures » des fichiers JSON de l'appli
 * (sauvegardes de parties dice_state_<slug>.json, liste custom_stories.json).
 *
 * Avant : un simple writeText() pouvait laisser un fichier tronqué si l'appli était tuée ou
 * le téléphone éteint en plein milieu de l'écriture, et la partie ne s'ouvrait plus.
 *
 * Maintenant, pour un fichier F :
 *   - on écrit d'abord dans F.tmp (puis fsync), puis on le renomme en F : le fichier F est
 *     donc toujours soit l'ancienne version complète, soit la nouvelle version complète ;
 *   - avant de remplacer F, si F est valide on en garde une copie dans F.bak (la version
 *     précédente) ;
 *   - à la lecture, si F est illisible, on essaie F.bak ; si les deux sont illisibles, F est
 *     mis de côté sous le nom F.corrupt (jamais écrasé ni perdu) et la lecture échoue
 *     proprement au lieu de planter.
 *
 * F.bak n'est consulté QUE si F existe mais est illisible : un F volontairement supprimé
 * (réinitialisation, suppression d'une histoire) ne ressuscite donc jamais depuis sa copie ;
 * [deleteAll] supprime les trois fichiers.
 */
object SafeFile {

    private const val BACKUP_SUFFIX = ".bak"
    private const val TMP_SUFFIX = ".tmp"
    private const val CORRUPT_SUFFIX = ".corrupt"

    fun backupOf(file: File): File = File(file.path + BACKUP_SUFFIX)

    /**
     * Écrit [text] dans [file] de façon atomique.
     * [isValid] décide si le contenu ACTUEL de [file] mérite d'être copié dans F.bak
     * (par exemple : « se lit comme un JSONObject ») ; une exception compte comme « non ».
     */
    fun writeAtomic(file: File, text: String, isValid: (String) -> Boolean = { true }) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (file.exists()) {
            val current = try { file.readText() } catch (e: Exception) { null }
            val worthKeeping = current != null && current.isNotEmpty() &&
                try { isValid(current) } catch (e: Exception) { false }
            if (worthKeeping) {
                try { file.copyTo(backupOf(file), overwrite = true) } catch (e: Exception) { /* copie de confort */ }
            }
        }
        // renameTo remplace atomiquement sur Android/Linux ; la copie n'est qu'un secours.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Fichiers à essayer pour relire [file], dans l'ordre : lui-même, puis sa copie F.bak.
     * Liste vide si [file] n'existe pas (rien à charger, et surtout pas de résurrection
     * depuis F.bak).
     */
    fun candidates(file: File): List<File> =
        if (!file.exists()) emptyList() else listOf(file, backupOf(file)).filter { it.exists() }

    /** Met de côté un fichier illisible sous le nom F.corrupt, sans jamais le détruire. */
    fun quarantine(file: File) {
        if (!file.exists()) return
        val corrupt = File(file.path + CORRUPT_SUFFIX)
        if (corrupt.exists()) corrupt.delete()
        if (!file.renameTo(corrupt)) {
            try { file.copyTo(corrupt, overwrite = true); file.delete() } catch (e: Exception) { /* tant pis */ }
        }
    }

    /** Supprime le fichier, sa copie et un éventuel reste d'écriture interrompue. */
    fun deleteAll(file: File) {
        file.delete()
        backupOf(file).delete()
        File(file.path + TMP_SUFFIX).delete()
    }
}
