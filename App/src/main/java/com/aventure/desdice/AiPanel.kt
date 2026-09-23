package com.aventure.desdice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aventure.desdice.viewmodel.GameViewModel
import org.json.JSONArray

/**
 * Panneau de narration IA (equivalent Compose de render_ai_panel_html dans
 * dice_web.py). Affiche la conversation (session.ai_conversation, sans le
 * premier message "system"), un champ de texte libre, un bouton pour
 * amorcer/relancer un chapitre sans lancer de de, un bouton pour ecouter
 * le dernier message de l'IA (SpeechManager), et un bouton de
 * reinitialisation (qui, cote moteur, remet TOUTE l'histoire a zero --
 * d'ou la confirmation avant de l'executer).
 *
 * Reconnecte au moteur Kotlin (GameEngine, via GameViewModel) : ce fichier
 * n'appelle plus Python/Chaquopy. GameViewModel.sendFullPrompt(),
 * .sendAiMessage() et .resetAiConversation() ecrivent elles-memes le
 * resultat dans sessionState (y compris le champ "ai_error" en cas
 * d'echec cote IA) ; ce composable se contente de reagir aux changements
 * de sessionState (voir le LaunchedEffect ci-dessous), il n'y a plus de
 * resultat synchrone a intercepter.
 *
 * speechManager attend une methode speak(text: String) -- adapte le nom
 * si ta classe SpeechManager expose une signature differente. Peut etre
 * null (bouton "Ecouter" alors masque), comme dans MainGameScreen.kt.
 *
 * hasKey / onKeyChanged / onConfigureKey reprennent le role qu'ils avaient
 * dans l'ancienne NarrationSection (GameExtras.kt, desormais remplacee par
 * ce fichier) : sans cle Mistral enregistree, le panneau de conversation
 * est masque au profit d'un message + bouton vers l'ecran de configuration
 * (le mode manuel reste possible via ContinueSection, qui permet de copier
 * le prompt complet). onKeyChanged est appele apres le retrait de la cle,
 * pour que l'ecran appelant rafraichisse son propre etat (configScreenState).
 */
@Composable
fun AiPanel(
    viewModel: GameViewModel,
    hasKey: Boolean,
    onKeyChanged: () -> Unit = {},
    onConfigureKey: (() -> Unit)? = null,
    speechManager: SpeechManager? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val sessionState by viewModel.sessionState.collectAsState()

    // Reconstruit la liste affichable et relit ai_error a chaque
    // changement d'etat de session (le premier message, "system", n'est
    // jamais montre au joueur). C'est aussi ce qui fait retomber
    // isLoading a false une fois l'action terminee cote moteur.
    LaunchedEffect(sessionState) {
        val conv: JSONArray? = sessionState?.optJSONArray("ai_conversation")
        val list = mutableListOf<Pair<String, String>>()
        if (conv != null) {
            for (i in 0 until conv.length()) {
                val entry = conv.getJSONObject(i)
                val role = entry.optString("role")
                if (role == "system") continue
                list.add(role to entry.optString("content"))
            }
        }
        messages = list
        error = sessionState?.optString("ai_error", "")?.ifEmpty { null }
        isLoading = false
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Narration IA",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { showResetConfirm = true }) {
                Icon(Icons.Default.Refresh, contentDescription = "Réinitialiser la conversation IA")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasKey) {
            Text(
                text = "Aucune clé API Mistral configurée pour l'instant — l'histoire ne " +
                    "s'écrit donc pas ici automatiquement (le prompt complet reste copiable " +
                    "plus bas, en mode manuel).",
                style = MaterialTheme.typography.bodyMedium
            )
            if (onConfigureKey != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onConfigureKey, modifier = Modifier.fillMaxWidth()) {
                    Text("🔑 Configurer la clé API")
                }
            }
            return@Column
        }

        if (messages.isEmpty() && !isLoading) {
            Text(
                text = "Aucun échange pour l'instant.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(
                onClick = {
                    isLoading = true
                    error = null
                    viewModel.sendFullPrompt()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Envoyer le prompt à l'IA")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { (role, content) ->
                    val isAssistant = role == "assistant"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isAssistant) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.primaryContainer
                                )
                                .padding(10.dp)
                        ) {
                            Text(text = content, style = MaterialTheme.typography.bodyMedium)
                            if (isAssistant && speechManager != null) {
                                IconButton(
                                    onClick = { speechManager.speak(content) },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Écouter",
                                        modifier = Modifier.height(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Écrire à l'IA narratrice…") },
                enabled = !isLoading
            )
            IconButton(
                onClick = {
                    val text = inputText.trim()
                    if (text.isNotEmpty()) {
                        isLoading = true
                        error = null
                        viewModel.sendAiMessage(text)
                        inputText = ""
                    }
                },
                enabled = !isLoading && inputText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Envoyer")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = {
                viewModel.clearMistralKey()
                onKeyChanged()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("❌ Retirer la clé", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Réinitialiser l'histoire ?") },
            text = {
                Text(
                    "Cette action efface la conversation IA, mais aussi l'historique des " +
                        "dés, les jauges totémiques, la menace, les quêtes secondaires et les " +
                        "totems ajoutés en cours de partie — l'histoire repart comme au tout " +
                        "premier lancement. Cette action est irréversible."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    isLoading = true
                    error = null
                    viewModel.resetAiConversation()
                }) {
                    Text("Réinitialiser", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
