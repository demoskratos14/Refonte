package com.aventure.desdice.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Equivalent Compose de render_configure_key_page (dice_web.py) : cle
 * Mistral (jamais affichee en clair une fois enregistree, seul un
 * indicateur "deja enregistree" est connu) + choix du modele parmi
 * mistral_client.MODEL_CHOICES.
 *
 * Repose sur game_api.get_config_screen_state (ajoutee a l'etape 6, voir
 * game_api.py) et sur set_mistral_key/clear_mistral_key/set_mistral_model,
 * deja presentes dans game_api.py -- appelees ici SANS prefixe "do_" (ce
 * sont les noms reels du fichier, le plan de migration en donnait une
 * version legerement differente).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureKeyScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var modelChoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshState() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val result = python.getModule("game_api")
                        .callAttr("call_json", "get_config_screen_state")
                        .toString()
                    val parsed = JSONObject(result)
                    hasKey = parsed.optBoolean("has_key", false)
                    selectedModel = parsed.optString("model", "")
                    val choicesArray = parsed.optJSONArray("model_choices")
                    val choices = mutableListOf<Pair<String, String>>()
                    if (choicesArray != null) {
                        for (i in 0 until choicesArray.length()) {
                            val pair = choicesArray.getJSONArray(i)
                            choices.add(pair.getString(0) to pair.getString(1))
                        }
                    }
                    modelChoices = choices
                } catch (e: Exception) {
                    error = "Erreur : ${e.message}"
                } finally {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    fun callGameApi(funcName: String, vararg args: Any, after: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { saving = true; error = null }
            try {
                python.getModule("game_api").callAttr("call_json", funcName, *args)
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur : ${e.message}" }
            } finally {
                mutex.withLock { saving = false }
            }
            after()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Narration automatique",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Colle ta clé API Mistral (console.mistral.ai) pour activer la " +
                "narration automatique à chaque lancer. Laisse vide et passe cette " +
                "étape pour rester en mode manuel.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (loading) {
            CircularProgressIndicator()
        } else {
            if (hasKey) {
                Text(
                    text = "✅ Une clé est déjà enregistrée.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (hasKey) "Remplacer la clé" else "Clé API Mistral") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving
            )

            if (modelChoices.isNotEmpty()) {
                Text(text = "Modèle", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    modelChoices.forEach { (value, label) ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (value == selectedModel),
                                    onClick = {
                                        selectedModel = value
                                        callGameApi("set_mistral_model", value)
                                    }
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = (value == selectedModel),
                                onClick = null,
                                enabled = !saving
                            )
                            Spacer(Modifier.height(0.dp))
                            Text(text = label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        callGameApi("set_mistral_key", keyInput) {
                            keyInput = ""
                            refreshState()
                        }
                    },
                    enabled = !saving && keyInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enregistrer la clé")
                }
                if (hasKey) {
                    TextButton(
                        onClick = { callGameApi("clear_mistral_key") { refreshState() } },
                        enabled = !saving
                    ) {
                        Text("Retirer la clé", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            TextButton(onClick = onDone) {
                Text(if (hasKey) "Continuer" else "Passer cette étape (mode manuel)")
            }
        }
    }
}
