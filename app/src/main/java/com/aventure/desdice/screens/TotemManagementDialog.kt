package com.aventure.desdice.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class CustomTotem(
    val key: String,
    val label: String,
    val emoji: String
)

/**
 * Equivalent Compose du formulaire "totems ajoutes en cours de partie"
 * de dice_web.py (do_add_custom_totem/do_remove_custom_totem).
 *
 * Reconnecte au moteur Kotlin (GameEngine, via GameViewModel) : ce fichier
 * n'appelle plus Python/Chaquopy. GameViewModel.addCustomTotem() et
 * .removeCustomTotem() mettent a jour sessionState elles-memes ; ce
 * composable se contente de lire sessionState et de reagir a ses
 * changements (voir refreshFromSessionState()).
 */
@Composable
fun TotemManagementDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var totems by remember { mutableStateOf<List<CustomTotem>>(emptyList()) }
    var label by remember { mutableStateOf("") }
    var powers by remember { mutableStateOf("") }
    var special by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) imageUri = uri }

    val sessionState by viewModel.sessionState.collectAsState()

    // Se redeclenche a chaque mise a jour de sessionState -- y compris
    // celles provoquees par addCustomTotem()/removeCustomTotem() plus bas,
    // qui l'ecrivent elles-memes une fois l'action terminee cote moteur.
    // C'est ce qui fait retomber "saving" a false : il n'y a plus de
    // resultat synchrone a attendre comme avec l'ancien pont Chaquopy.
    LaunchedEffect(sessionState) {
        val custom = sessionState?.optJSONArray("custom_totems")
        val list = mutableListOf<CustomTotem>()
        if (custom != null) {
            for (i in 0 until custom.length()) {
                val t = custom.getJSONObject(i)
                list.add(
                    CustomTotem(
                        key = t.optString("key"),
                        label = t.optString("label"),
                        emoji = t.optString("emoji", "⭐")
                    )
                )
            }
        }
        totems = list
        saving = false
    }

    fun addTotem() {
        if (label.isBlank()) {
            error = "Le nom du totem est obligatoire."
            return
        }
        error = null
        saving = true

        // On capture les valeurs des champs avant de les vider (le
        // formulaire se remet a zero tout de suite, l'ajout se termine en
        // tache de fond).
        val labelSnapshot = label
        val powersSnapshot = powers
        val specialSnapshot = special
        val emojiSnapshot = emoji
        val uriSnapshot = imageUri
        label = ""; powers = ""; special = ""; emoji = ""; imageUri = null

        scope.launch(Dispatchers.IO) {
            val bytes = uriSnapshot?.let {
                context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
            } ?: ByteArray(0)
            val filename = if (uriSnapshot != null) "totem.jpg" else ""
            viewModel.addCustomTotem(labelSnapshot, powersSnapshot, specialSnapshot, emojiSnapshot, bytes, filename)
        }
    }

    fun removeTotem(key: String) {
        saving = true
        viewModel.removeCustomTotem(key)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Totems") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (totems.isEmpty()) {
                    Text(
                        text = "Aucun totem ajouté pour l'instant.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height((totems.size * 48).coerceAtMost(240).dp)
                    ) {
                        items(totems) { totem ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "${totem.emoji} ${totem.label}")
                                IconButton(
                                    onClick = { removeTotem(totem.key) },
                                    enabled = !saving
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                                }
                            }
                        }
                    }
                    Divider()
                }

                Text(
                    text = "Ajouter un totem",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                )
                OutlinedTextField(
                    value = powers,
                    onValueChange = { powers = it },
                    label = { Text("Pouvoirs (séparés par des virgules)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                )
                OutlinedTextField(
                    value = special,
                    onValueChange = { special = it },
                    label = { Text("Capacité spéciale (optionnel)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text("Emoji (si pas d'image)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                )
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            pickImage.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !saving
                    ) {
                        Text(if (imageUri == null) "Image (optionnel)" else "Changer l'image")
                    }
                    imageUri?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }

                if (saving) {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { addTotem() }, enabled = !saving) {
                Text("Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer")
            }
        }
    )
}
