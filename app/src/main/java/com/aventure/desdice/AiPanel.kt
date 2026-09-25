package com.aventure.desdice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Panneau de narration IA (equivalent Compose de render_ai_panel_html dans
 * dice_web.py). Affiche la conversation (session.ai_conversation, sans le
 * premier message "system"), un champ de texte libre, un bouton pour ecouter
 * le dernier message de l'IA (SpeechManager), et un bouton de
 * reinitialisation (qui, cote moteur, remet TOUTE l'histoire a zero --
 * d'ou la confirmation avant de l'executer).
 *
 * Le bouton "Demarrer l'histoire" (envoi du prompt complet quand la
 * conversation est encore vide) ne vit plus ici : il est affiche par
 * MainGameScreen.kt, juste sous "Changer d'histoire", et disparait des
 * qu'un message existe deja.
 *
 * Les messages purement techniques envoyés à l'IA (prompt de mécaniques, description
 * d'un lancer de dés...) ne sont jamais montrés ici : GameEngine les marque `visible =
 * false` côté DiceSession.AiMessage. Quand le joueur a en plus tapé un texte libre au
 * moment du lancer, seul ce texte (`display_content`) est affiché, pas la description
 * technique qui l'accompagnait dans le message réellement envoyé à l'IA.
 *
 * Couleur et taille du texte des échanges (bulles + champ de saisie) suivent les
 * réglages de l'utilisateur (FontPrefs.replyTextColor / replyTextSizeSp, Réglages >
 * Écriture) ; la police suit fonts.body, comme le reste de l'application.
 *
 * Quand la réponse de l'IA se termine par des lignes "OPTION: ..." (voir la consigne
 * PROPOSITIONS D'ACTIONS CLIQUABLES de GameEngine.buildMechanicsContext), ces lignes
 * sont retirées du texte affiché et proposées sous forme de boutons juste en dessous
 * du dernier message ; le joueur garde toujours le champ de saisie pour répondre
 * librement au lieu d'en choisir une.
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
/** Un message affiché dans le panneau ; `choices` n'est renseigné que pour le dernier message assistant. */
private data class ChatEntry(val role: String, val content: String, val choices: List<String> = emptyList())

// Ligne "OPTION: <action>" ajoutée par l'IA en fin de réponse (voir la consigne
// PROPOSITIONS D'ACTIONS CLIQUABLES de GameEngine.buildMechanicsContext) : tiret ou
// puce éventuels tolérés devant, tout le reste de la ligne pris comme intitulé du bouton.
private val OPTION_LINE = Regex("""^\s*(?:[-•*]\s*)?OPTION\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Sépare le texte de narration des choix cliquables qu'il propose en fin de message.
 * Ne modifie rien (et renvoie une liste de choix vide) si le message ne se termine pas
 * par ce format ; les lignes vides entre le texte et les options, ou entre deux
 * options, sont tolérées.
 */
private fun extractChoices(content: String): Pair<String, List<String>> {
    val lines = content.split("\n")
    val choices = mutableListOf<String>()
    var cut = lines.size
    for (i in lines.indices.reversed()) {
        val line = lines[i]
        val match = OPTION_LINE.matchEntire(line)
        if (match != null) {
            choices.add(0, match.groupValues[1].trim())
            cut = i
        } else if (line.isBlank()) {
            continue
        } else {
            break
        }
    }
    if (choices.isEmpty()) return content to emptyList()
    return lines.subList(0, cut).joinToString("\n").trimEnd() to choices
}

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
    val context = LocalContext.current
    val fonts = rememberAppFonts()
    val fontPrefs = remember(context) { FontPrefs.get(context) }
    val replyStyle = TextStyle(
        fontFamily = fonts.body,
        fontSize = fontPrefs.replyTextSizeSp.sp,
        color = fontPrefs.replyTextColor ?: androidx.compose.ui.graphics.Color.Unspecified
    )

    var messages by remember { mutableStateOf<List<ChatEntry>>(emptyList()) }
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
        val list = mutableListOf<ChatEntry>()
        if (conv != null) {
            for (i in 0 until conv.length()) {
                val entry = conv.getJSONObject(i)
                val role = entry.optString("role")
                if (role == "system") continue
                // Les messages techniques (lancer de dés, prompt de mécaniques...) ne sont
                // jamais affichés ; display_content, s'il existe, remplace le contenu complet.
                if (!entry.optBoolean("visible", true)) continue
                val shown = entry.optString("display_content", "").ifEmpty { entry.optString("content") }
                if (role == "assistant") {
                    val (text, choices) = extractChoices(shown)
                    list.add(ChatEntry(role, text, choices))
                } else {
                    list.add(ChatEntry(role, shown))
                }
            }
        }
        messages = list
        // opt() (et non optString) : une valeur JSONObject.NULL explicite ne doit jamais
        // s'afficher comme le texte "null" (optString la confondrait avec une vraie erreur).
        val aiErrorValue = sessionState?.opt("ai_error")
        error = if (aiErrorValue == null || aiErrorValue == JSONObject.NULL) {
            null
        } else {
            aiErrorValue.toString().ifEmpty { null }
        }
        isLoading = false
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Un texte tapé et un choix cliqué suivent exactement le même chemin d'envoi.
    fun sendToAi(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isLoading) return
        isLoading = true
        error = null
        viewModel.sendAiMessage(trimmed)
    }

    // Choix cliquables du tout dernier message assistant (une histoire déjà relancée
    // par le joueur, ou plus ancienne, ne propose plus les siens).
    val pendingChoices = messages.lastOrNull()?.takeIf { it.role == "assistant" }?.choices ?: emptyList()

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
                style = MaterialTheme.typography.bodyMedium.merge(replyStyle)
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
            // Le bouton pour demarrer/relancer un chapitre (envoi du prompt
            // complet) vit desormais dans MainGameScreen.kt, juste sous
            // "Changer d'histoire" -- il n'est donc plus duplique ici.
            Text(
                text = "Aucun échange pour l'instant.",
                style = MaterialTheme.typography.bodyMedium.merge(replyStyle),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { entry ->
                    val role = entry.role
                    val content = entry.content
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
                            Text(text = content, style = MaterialTheme.typography.bodyMedium.merge(replyStyle))
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

        if (pendingChoices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            ChoiceButtonsRow(
                choices = pendingChoices,
                enabled = !isLoading,
                textStyle = replyStyle,
                onChoiceSelected = { sendToAi(it) }
            )
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
                textStyle = replyStyle,
                placeholder = { Text("Écrire à l'IA narratrice…", style = replyStyle) },
                enabled = !isLoading
            )
            IconButton(
                onClick = {
                    sendToAi(inputText)
                    inputText = ""
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

/**
 * Rangée de boutons pour les choix proposés par l'IA en fin de message (voir
 * extractChoices ci-dessus). Cliquer un choix l'envoie tel quel, exactement comme
 * s'il avait été tapé dans le champ de saisie ; ce champ reste toujours disponible
 * pour répondre librement à la place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceButtonsRow(
    choices: List<String>,
    enabled: Boolean,
    textStyle: TextStyle,
    onChoiceSelected: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        choices.forEach { choice ->
            AssistChip(
                onClick = { onChoiceSelected(choice) },
                enabled = enabled,
                label = { Text(text = choice, style = MaterialTheme.typography.labelLarge.merge(textStyle)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}
