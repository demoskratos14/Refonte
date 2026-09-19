package com.aventure.desdice.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Export du journal (PDF si fpdf2 est disponible, sinon TXT -- voir
 * journal_export.py/game_api.export_journal) et de l'identite d'une
 * histoire personnalisee (JSON, game_api.export_identity), vers un
 * emplacement choisi par le joueur (Storage Access Framework).
 *
 * IMPORTANT -- pourquoi ni l'un ni l'autre ne passe par call_json :
 * export_journal()/export_identity() renvoient un tuple Python
 * (bytes, nom_fichier, type_mime). `bytes` n'est PAS serialisable en
 * JSON (json.dumps leverait une TypeError des le premier appel) : on
 * appelle donc ces deux fonctions DIRECTEMENT via Chaquopy
 * (callAttr("export_journal")), et on lit le tuple renvoye avec
 * PyObject.asList()/toJava(ByteArray::class.java), jamais via
 * call_json.
 *
 * export_identity() renvoie (b"", "", "") quand l'histoire courante
 * n'est pas personnalisee (ou que stories.export_story_identity n'a
 * rien a exporter) -- gere ici comme "rien a exporter", pas une erreur.
 */
private class PendingExport(val bytes: ByteArray, val filename: String)

@Composable
private fun rememberFileExporter(): (ByteArray, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    // "*/*" plutot qu'un mimeType fixe : le fichier propose peut etre un
    // .pdf OU un .txt selon la disponibilite de fpdf2 (voir plus haut),
    // determine seulement APRES l'appel a game_api -- un seul lanceur
    // generique evite d'avoir a en enregistrer un par type possible.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val export = pending
        pending = null
        if (uri != null && export != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(export.bytes)
            }
        }
    }

    return { bytes, filename ->
        pending = PendingExport(bytes, filename)
        launcher.launch(filename)
    }
}

/**
 * Boutons d'export a placer sur l'ecran de jeu (ou un menu) : un pour le
 * journal (toujours propose), un pour l'identite de l'histoire (affiche
 * seulement pour une histoire personnalisee -- isCustomStory vient de
 * CURRENT_STORY_CONFIG["is_custom"], deja present dans session_to_dict
 * via story_title/all_symbols... a exposer aussi is_custom cote
 * session_to_dict si ce n'est pas deja fait, sinon passe simplement
 * `false` ici pour masquer ce bouton).
 */
@Composable
fun ExportButtons(
    isCustomStory: Boolean,
    modifier: Modifier = Modifier
) {
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val exportFile = rememberFileExporter()

    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runExport(funcName: String, emptyMessage: String) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { exporting = true; error = null }
            try {
                val result = python.getModule("game_api").callAttr(funcName)
                val items = result.asList()
                val bytes = items[0].toJava(ByteArray::class.java)
                val filename = items[1].toString()
                if (bytes.isEmpty() || filename.isEmpty()) {
                    mutex.withLock { error = emptyMessage }
                } else {
                    exportFile(bytes, filename)
                }
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur : ${e.message}" }
            } finally {
                mutex.withLock { exporting = false }
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                runExport("export_journal", "Aucun chapitre enregistré pour l'instant.")
            },
            enabled = !exporting
        ) {
            Text("📄 Exporter le journal")
        }
        if (isCustomStory) {
            OutlinedButton(
                onClick = {
                    runExport(
                        "export_identity",
                        "Rien à exporter pour cette histoire."
                    )
                },
                enabled = !exporting
            ) {
                Text("Exporter l'identité de l'histoire")
            }
        }
        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}
