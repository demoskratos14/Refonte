package com.aventure.desdice.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aventure.desdice.viewmodel.GameViewModel
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Equivalent Compose de render_create_story_page (dice_web.py). Appelle
 * game_api.do_create_story directement (plutot que via
 * viewModel.createStory, qui est fire-and-forget : on a besoin ici
 * d'attendre la fin de l'appel avant de naviguer), puis rafraichit
 * viewModel.loadStories() pour que le selecteur voie la nouvelle
 * histoire.
 *
 * Inclut le bloc "importer une identite exportee" (do_import_identity) :
 * stories.parse_identity_import/export_story_identity ont ete ajoutees a
 * stories.py pour le rendre fonctionnel (voir le message qui accompagne
 * ce fichier). Une image importee (bg ou totem) est prioritaire tant que
 * l'utilisateur n'en choisit pas explicitement une autre via les
 * selecteurs -- re-choisir une image l'efface au profit de la nouvelle.
 */
@Composable
fun CreateStoryScreen(
    viewModel: GameViewModel,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var loreText by remember { mutableStateOf("") }
    var totemLabel by remember { mutableStateOf("") }
    var totemPowers by remember { mutableStateOf("") }
    var totemSpecial by remember { mutableStateOf("") }

    var bgUri by remember { mutableStateOf<Uri?>(null) }
    var totemUri by remember { mutableStateOf<Uri?>(null) }
    // Images issues d'un import d'identite (voir importIdentity ci-dessous) :
    // utilisees a la place d'un Uri tant que l'utilisateur n'a pas
    // explicitement choisi sa propre image via les selecteurs.
    var importedBgBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importedTotemBytes by remember { mutableStateOf<ByteArray?>(null) }

    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickBgImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) { bgUri = uri; importedBgBytes = null } }

    val pickTotemImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) { totemUri = uri; importedTotemBytes = null } }

    fun uriExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("gif") -> "gif"
            else -> "jpg"
        }
    }

    fun uriBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    fun importIdentity() {
        if (importText.isBlank()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock { importing = true; error = null }
            try {
                val result = Python.getInstance().getModule("game_api")
                    .callAttr("call_json", "do_import_identity", importText)
                    .toString()
                val parsed = JSONObject(result)
                mutex.withLock {
                    title = parsed.optString("title", "")
                    subtitle = parsed.optString("subtitle", "")
                    loreText = parsed.optString("lore_text", "")
                    totemLabel = parsed.optString("totem_label", "")
                    totemPowers = parsed.optString("totem_powers", "")
                    totemSpecial = parsed.optString("totem_special", "")

                    val bgB64 = parsed.optString("bg_image_b64", "")
                    if (bgB64.isNotEmpty()) {
                        importedBgBytes = Base64.decode(bgB64, Base64.DEFAULT)
                        bgUri = null
                    }
                    val totemB64 = parsed.optString("totem_image_b64", "")
                    if (totemB64.isNotEmpty()) {
                        importedTotemBytes = Base64.decode(totemB64, Base64.DEFAULT)
                        totemUri = null
                    }
                    if (bgB64.isEmpty() && title.isEmpty()) {
                        error = "Le texte collé ne ressemble pas à une identité exportée valide."
                    }
                }
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur d'import : ${e.message}" }
            } finally {
                mutex.withLock { importing = false }
            }
        }
    }

    fun submit() {
        if (title.isBlank()) {
            error = "Le titre est obligatoire."
            return
        }
        val bg = bgUri
        val importedBg = importedBgBytes
        if (bg == null && importedBg == null) {
            error = "Choisis une image de fond (ou importe une identité qui en contient une)."
            return
        }
        scope.launch(Dispatchers.IO) {
            mutex.withLock { saving = true; error = null }
            try {
                // L'image explicitement choisie (bgUri) est toujours
                // prioritaire sur celle d'un import -- coherent avec le
                // fait que la choisir efface deja importedBgBytes plus haut.
                val bgBytes = bg?.let { uriBytes(it) } ?: importedBg!!
                val bgExt = bg?.let { uriExtension(it) } ?: "jpg"
                val totemBytes = totemUri?.let { uriBytes(it) }
                    ?: importedTotemBytes ?: ByteArray(0)
                val totemFilename = when {
                    totemUri != null -> "totem.${uriExtension(totemUri!!)}"
                    importedTotemBytes != null -> "totem.png"
                    else -> ""
                }

                // Appel direct (plutot que viewModel.createStory, qui est
                // "fire-and-forget" -- lance sa propre coroutine sans
                // moyen d'attendre sa fin) : on a besoin de savoir que la
                // creation est terminee avant de naviguer (onCreated).
                Python.getInstance().getModule("game_api").callAttr(
                    "call_json", "do_create_story",
                    title, subtitle, loreText,
                    totemLabel, totemPowers, totemSpecial,
                    bgBytes, bgExt, totemBytes, totemFilename
                )
                viewModel.loadStories()
                onCreated()
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur : ${e.message}" }
            } finally {
                mutex.withLock { saving = false }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Nouvelle histoire",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(text = "Importer une identité exportée (optionnel)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = importText,
            onValueChange = { importText = it },
            label = { Text("Coller le JSON exporté depuis une autre histoire") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            enabled = !importing && !saving
        )
        OutlinedButton(
            onClick = { importIdentity() },
            enabled = !importing && !saving && importText.isNotBlank()
        ) {
            Text(if (importing) "Import en cours…" else "Importer")
        }

        Divider()

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Titre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        OutlinedTextField(
            value = subtitle,
            onValueChange = { subtitle = it },
            label = { Text("Sous-titre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        OutlinedTextField(
            value = loreText,
            onValueChange = { loreText = it },
            label = { Text("Description de l'univers (envoyée à l'IA narratrice)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )

        Text(text = "Image de fond", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                pickBgImage.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            enabled = !saving
        ) {
            Text(if (bgUri == null && importedBgBytes == null) "Choisir une image" else "Changer l'image")
        }
        bgUri?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }
        if (bgUri == null) {
            importedBgBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
            }
        }

        Text(text = "Totem de départ", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = totemLabel,
            onValueChange = { totemLabel = it },
            label = { Text("Nom du totem") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        OutlinedTextField(
            value = totemPowers,
            onValueChange = { totemPowers = it },
            label = { Text("Pouvoirs (séparés par des virgules)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        OutlinedTextField(
            value = totemSpecial,
            onValueChange = { totemSpecial = it },
            label = { Text("Capacité spéciale (optionnel)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pickTotemImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                enabled = !saving
            ) {
                Text(
                    if (totemUri == null && importedTotemBytes == null) "Image du totem (optionnel)"
                    else "Changer l'image"
                )
            }
            totemUri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp)
                )
            }
            if (totemUri == null) {
                importedTotemBytes?.let { bytes ->
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        if (saving) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Créer l'histoire")
            }
        }
    }
}
