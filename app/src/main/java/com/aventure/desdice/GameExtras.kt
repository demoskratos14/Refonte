package com.aventure.desdice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/*
 * Blocs de l'ancienne page de jeu (dice_web.py, index()) qui n'etaient pas encore
 * branches dans la version Compose :
 *   - Narration automatique (render_ai_panel_html)         -> NarrationSection
 *   - Note "a copier pour signaler a l'IA"                  -> NarratorNoteBox
 *   - Demarrer / relancer un chapitre, ou copier le prompt  -> ContinueSection
 *   - Journal de l'histoire (ajout, lecture, export)        -> JournalSection
 *   - Ajout / suppression de totems en cours de partie      -> TotemManagerDialog
 *
 * Meme habillage que MainGameScreen.kt (dont on reutilise les briques : Section,
 * ComicButton, styles de texte...).
 */

// =====================================================================
// Narration automatique
// =====================================================================

@Composable
fun NarrationSection(
    viewModel: GameViewModel,
    hasKey: Boolean,
    onKeyChanged: () -> Unit,
    speechManager: SpeechManager?,
    onConfigureKey: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFullThread by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // Conversation sans le premier message "system" (contexte de mecaniques,
    // jamais montre au joueur).
    val messages = buildList<Pair<String, String>> {
        sessionState?.optJSONArray("ai_conversation")?.let { conv ->
            for (i in 0 until conv.length()) {
                val entry = conv.getJSONObject(i)
                val role = entry.optString("role")
                if (role != "system") add(role to entry.optString("content"))
            }
        }
    }
    // Comme l'ancienne page : on n'affiche par defaut que la DERNIERE reponse.
    val lastAssistant: String? = messages.lastOrNull { it.first == "assistant" }?.second
    val pending: String? = pendingRollDescription(sessionState)

    fun callAi(func: String, vararg args: Any, onOk: () -> Unit = {}) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                isSending = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", func, *args)
                    .toString()
                val aiError = JSONObject(result).str("ai_error")
                if (aiError.isNotEmpty()) error = aiError
                viewModel.loadSessionState(result)
                onOk()
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { isSending = false }
            }
        }
    }

    fun clearKey() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                python.getModule("game_api").callAttr("call_json", "clear_mistral_key")
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            }
            onKeyChanged()
        }
    }

    Section(modifier) {
        SectionTitle("Narration automatique", fonts)

        if (!hasKey) {
            Text(
                text = "Aucune clé API Mistral configurée pour l'instant — l'histoire ne " +
                    "s'écrit donc pas ici automatiquement (le prompt complet reste " +
                    "copiable plus bas, en mode manuel).",
                style = bodyStyle(fonts, 14.sp)
            )
            if (onConfigureKey != null) {
                Spacer(modifier = Modifier.height(10.dp))
                ComicButton(
                    text = "🔑 Configurer la clé API",
                    onClick = onConfigureKey,
                    fonts = fonts,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(text = "✅ Narration automatique active (Mistral)", style = bodyStyle(fonts, 14.sp))
            Spacer(modifier = Modifier.height(8.dp))

            error?.let {
                Text(text = "⚠️ $it", style = bodyStyle(fonts, 14.sp, ErrorOnPhoto))
                Spacer(modifier = Modifier.height(8.dp))
            }

            val shape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Ink.copy(alpha = 0.5f))
                    .border(2.dp, Color.White.copy(alpha = 0.35f), shape)
                    .padding(12.dp)
            ) {
                if (showFullThread) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        messages.forEach { (role, content) ->
                            Text(
                                text = if (role == "assistant") "📖 Narrateur" else "✍️ Toi",
                                style = boldStyle(fonts, 13.sp)
                            )
                            Text(text = content, style = bodyStyle(fonts, 15.sp))
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                } else if (lastAssistant != null) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = lastAssistant, style = bodyStyle(fonts, 15.sp))
                    }
                } else {
                    Text(
                        text = "(rien pour l'instant — lance un dé, utilise « Envoyer le prompt à " +
                            "l'IA » plus bas, ou écris un message ci-dessous pour planter le " +
                            "décor et démarrer l'aventure)",
                        style = bodyStyle(fonts, 14.sp, Color.White.copy(alpha = 0.85f))
                    )
                }
            }

            if (messages.size > 1) {
                Text(
                    text = if (showFullThread) "Masquer la conversation" else "Voir toute la conversation",
                    style = bodyStyle(fonts, 13.sp).copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier
                        .clickable { showFullThread = !showFullThread }
                        .padding(vertical = 8.dp)
                )
            }

            if (speechManager != null && lastAssistant != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ComicButton(
                        text = "🔊 Écouter",
                        onClick = { speechManager.speak(lastAssistant) },
                        fonts = fonts,
                        kind = ButtonKind.Secondary,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                    ComicButton(
                        text = "⏹ Stop",
                        onClick = { speechManager.stopSpeaking() },
                        fonts = fonts,
                        kind = ButtonKind.Secondary,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            pending?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "🎲 En attente d'envoi : ${it.replace("\n", " — ")}",
                    style = bodyStyle(fonts, 13.sp, Gold)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            ComicTextField(
                label = "Message libre à l'IA (démarrer l'aventure, décrire une action...)",
                value = inputText,
                onValueChange = { inputText = it },
                fonts = fonts,
                enabled = !isSending,
                minHeight = 90.dp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (isSending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "⌛ Le narrateur écrit...", style = bodyStyle(fonts, 14.sp))
                }
            }

            ComicButton(
                text = "✉️ Envoyer à l'IA",
                onClick = { callAi("do_send_ai_message", inputText.trim()) { inputText = "" } },
                fonts = fonts,
                enabled = !isSending && (inputText.isNotBlank() || pending != null),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ComicButton(
                    text = "↻ Réinitialiser la partie",
                    onClick = { showResetConfirm = true },
                    fonts = fonts,
                    kind = ButtonKind.Secondary,
                    compact = true,
                    enabled = !isSending,
                    modifier = Modifier.weight(1f)
                )
                ComicButton(
                    text = "❌ Retirer la clé",
                    onClick = { clearKey() },
                    fonts = fonts,
                    kind = ButtonKind.Secondary,
                    textColor = ErrorOnPhoto,
                    compact = true,
                    enabled = !isSending,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Paper,
            title = {
                Text(
                    "Réinitialiser l'histoire ?",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 22.sp, color = Ink)
                )
            },
            text = {
                Text(
                    "Cette action efface la conversation IA, mais aussi l'historique des " +
                        "dés, les jauges totémiques, la menace, les quêtes secondaires et " +
                        "les totems ajoutés en cours de partie — l'histoire repart comme au " +
                        "tout premier lancement. Cette action est irréversible.",
                    style = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = Ink)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    callAi("do_reset_ai_conversation")
                }) {
                    Text(
                        "Réinitialiser",
                        style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = DangerText)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(
                        "Annuler",
                        style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = Ink)
                    )
                }
            }
        )
    }
}

/** Description du dernier lancer s'il n'a pas encore ete transmis a l'IA (cf. DiceSession.pending_roll). */
private fun pendingRollDescription(state: JSONObject?): String? {
    val s = state ?: return null
    val history = s.optJSONArray("history") ?: return null
    if (history.length() == 0) return null
    val last = history.getJSONObject(history.length() - 1)
    if (last.optInt("id") <= s.optInt("last_ai_sent_id", 0)) return null
    val description = s.optJSONObject("last_result")?.str("description").orEmpty()
    return description.ifEmpty { "dernier lancer" }
}

// =====================================================================
// Note "a copier pour me le signaler" (evenements ? / !)
// =====================================================================

/** Meme logique que narrator_note_for_record() (game_api.py). */
internal fun narratorNoteFor(record: JSONObject?, fateFaces: List<JSONObject>): String {
    if (record == null) return ""
    val fate = record.str("fate")
    if (fate != "question" && fate != "exclamation") return ""
    val face = fateFaces.firstOrNull { it.optString("key") == fate } ?: return ""
    var text = "${face.optString("emoji")} ${face.optString("label")} : ${face.optString("desc")}"
    val sideQuest = record.optJSONObject("side_quest")
    if (sideQuest != null) {
        val kindText = if (sideQuest.optString("kind") == "ami") {
            "se faire un nouvel ami"
        } else {
            "trouver un nouvel objet (ou un nouveau totem)"
        }
        text += "\n(Quête secondaire débloquée : $kindText.)"
    }
    return text
}

@Composable
internal fun NarratorNoteBox(text: String, fonts: AppFonts, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.copy(alpha = 0.5f))
            .border(2.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(12.dp)
    ) {
        Text(text = "À copier pour me le signaler :", style = boldStyle(fonts, 13.sp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = text, style = bodyStyle(fonts, 14.sp))
        Spacer(modifier = Modifier.height(8.dp))
        ComicButton(
            text = if (copied) "Copié ✓" else "Copier",
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =====================================================================
// Demarrer / relancer un chapitre (ou copier le prompt complet)
// =====================================================================

@Composable
fun ContinueSection(
    viewModel: GameViewModel,
    hasKey: Boolean,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val fonts = rememberAppFonts()
    val clipboard = LocalClipboardManager.current

    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val chapters = sessionState?.optJSONArray("story_log")?.length() ?: 0

    fun sendFullPrompt() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            busy = true
            error = null
            info = null
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", "do_send_full_prompt")
                    .toString()
                val aiError = JSONObject(result).str("ai_error")
                if (aiError.isNotEmpty()) error = aiError
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun copyFullPrompt() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            busy = true
            error = null
            info = null
            try {
                val raw = python.getModule("game_api")
                    .callAttr("call_json", "build_full_prompt")
                    .toString()
                // call_json renvoie une chaine JSON (avec guillemets et echappements).
                val prompt = JSONTokener(raw).nextValue() as? String ?: raw
                withContext(Dispatchers.Main) {
                    clipboard.setText(AnnotatedString(prompt))
                }
                info = "Prompt copié dans le presse-papiers."
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Section(modifier) {
        SectionTitle(
            if (hasKey) "Démarrer ou relancer un chapitre" else "Continuer l'aventure ailleurs",
            fonts
        )
        Text(
            text = if (hasKey) {
                "Envoie les mécaniques du jeu et l'histoire déjà vécue directement à l'IA, " +
                    "avec une instruction de démarrer le prochain chapitre — utile avant le " +
                    "tout premier lancer, ou pour relancer le fil de l'histoire après avoir " +
                    "réinitialisé la conversation IA."
            } else {
                "Un seul bouton pour tout transmettre (mécaniques + histoire déjà vécue) à " +
                    "une IA narratrice, ici ou ailleurs, sans tout ré-expliquer."
            },
            style = bodyStyle(fonts, 14.sp)
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (busy) {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        ComicButton(
            text = if (hasKey) "🚀 Envoyer le prompt à l'IA" else "📋 Copier le prompt complet pour une IA",
            onClick = { if (hasKey) sendFullPrompt() else copyFullPrompt() },
            fonts = fonts,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        info?.let {
            Text(text = it, style = bodyStyle(fonts, 14.sp), modifier = Modifier.padding(top = 8.dp))
        }
        error?.let {
            Text(text = "⚠️ $it", style = bodyStyle(fonts, 14.sp, ErrorOnPhoto), modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            text = "$chapters chapitre(s) enregistré(s) dans le journal.",
            style = bodyStyle(fonts, 12.sp, Color.White.copy(alpha = 0.85f)),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

// =====================================================================
// Journal de l'histoire (ajout, lecture, export)
// =====================================================================

@Composable
fun JournalSection(
    viewModel: GameViewModel,
    isCustomStory: Boolean,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()
    val saveFile = rememberFileSaver()

    var entryText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    val chapters = buildList<String> {
        sessionState?.optJSONArray("story_log")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.optString(i))
        }
    }

    fun addEntry() {
        val text = entryText.trim()
        if (text.isEmpty()) return
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                busy = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", "do_add_story_entry", text)
                    .toString()
                viewModel.loadSessionState(result)
                entryText = ""
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { busy = false }
            }
        }
    }

    // export_journal()/export_identity() renvoient un tuple Python (bytes, nom,
    // type mime) : pas serialisable en JSON, donc appel direct (pas call_json).
    fun runExport(func: String, emptyMessage: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                busy = true
                error = null
            }
            try {
                val items = python.getModule("game_api").callAttr(func).asList()
                val bytes = items[0].toJava(ByteArray::class.java)
                val filename = items[1].toString()
                if (bytes.isEmpty() || filename.isEmpty()) {
                    error = emptyMessage
                } else {
                    withContext(Dispatchers.Main) { saveFile(bytes, filename) }
                }
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { busy = false }
            }
        }
    }

    Section(modifier) {
        SectionTitle("Journal de l'histoire", fonts)

        ComicTextField(
            label = "Coller ici le résumé de chapitre reçu de l'IA narratrice",
            value = entryText,
            onValueChange = { entryText = it },
            fonts = fonts,
            enabled = !busy,
            minHeight = 90.dp
        )
        Spacer(modifier = Modifier.height(10.dp))
        ComicButton(
            text = "📚 Ajouter au journal de l'histoire",
            onClick = { addEntry() },
            fonts = fonts,
            enabled = !busy && entryText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        ComicButton(
            text = "Voir le journal complet (${chapters.size})",
            onClick = { showLog = true },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        ComicButton(
            text = "📄 Exporter le journal",
            onClick = { runExport("export_journal", "Aucun chapitre enregistré pour l'instant.") },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (isCustomStory) {
            Spacer(modifier = Modifier.height(10.dp))
            ComicButton(
                text = "Exporter l'identité de l'histoire",
                onClick = { runExport("export_identity", "Rien à exporter pour cette histoire.") },
                fonts = fonts,
                kind = ButtonKind.Secondary,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Text(text = it, style = bodyStyle(fonts, 14.sp, ErrorOnPhoto), modifier = Modifier.padding(top = 8.dp))
        }
    }

    if (showLog) {
        StoryLogDialog(chapters = chapters, fonts = fonts, onDismiss = { showLog = false })
    }
}

@Composable
private fun StoryLogDialog(chapters: List<String>, fonts: AppFonts, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clip(shape)
                .background(Paper)
                .border(3.dp, Ink, shape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "Journal de l'histoire",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (chapters.isEmpty()) {
                    Text(
                        text = "(aucun chapitre enregistré pour l'instant)",
                        style = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = Ink)
                    )
                } else {
                    chapters.forEachIndexed { index, entry ->
                        Text(
                            text = "Chapitre ${index + 1}",
                            style = TextStyle(fontFamily = fonts.display, fontSize = 20.sp, color = Ink)
                        )
                        Text(
                            text = entry,
                            style = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = Ink),
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                        )
                    }
                }
                ComicButton(
                    text = "Fermer",
                    onClick = onDismiss,
                    fonts = fonts,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private class PendingSave(val bytes: ByteArray, val filename: String)

/** Enregistrement vers un emplacement choisi par le joueur (Storage Access Framework). */
@Composable
private fun rememberFileSaver(): (ByteArray, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingSave?>(null) }

    // "*/*" : le fichier peut etre un .pdf OU un .txt selon la disponibilite de fpdf2.
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
        pending = PendingSave(bytes, filename)
        launcher.launch(filename)
    }
}

// =====================================================================
// Totems ajoutes en cours de partie (fenetre)
// =====================================================================

@Composable
fun TotemManagerDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

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

    val totems = buildList<Triple<String, String, String>> {
        sessionState?.optJSONArray("custom_totems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                add(Triple(t.optString("key"), t.optString("label"), t.str("emoji").ifEmpty { "⭐" }))
            }
        }
    }

    fun addTotem() {
        if (label.isBlank()) {
            error = "Le nom du totem est obligatoire."
            return
        }
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                saving = true
                error = null
            }
            try {
                val uri = imageUri
                val bytes = uri?.let {
                    context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                } ?: ByteArray(0)
                val filename = if (uri != null) "totem.jpg" else ""
                val result = python.getModule("game_api").callAttr(
                    "call_json", "do_add_custom_totem",
                    label, powers, special, emoji, bytes, filename
                ).toString()
                viewModel.loadSessionState(result)
                label = ""
                powers = ""
                special = ""
                emoji = ""
                imageUri = null
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { saving = false }
            }
        }
    }

    fun removeTotem(key: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                saving = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", "do_remove_custom_totem", key)
                    .toString()
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { saving = false }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(shape)
                .background(Paper)
                .border(3.dp, Ink, shape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Totems",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink)
                )

                if (totems.isEmpty()) {
                    Text(
                        text = "Aucun totem ajouté pour l'instant.",
                        style = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = Ink)
                    )
                } else {
                    totems.forEach { (key, totemLabel, totemEmoji) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$totemEmoji $totemLabel",
                                style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                                modifier = Modifier.weight(1f)
                            )
                            ComicButton(
                                text = "🗑",
                                onClick = { removeTotem(key) },
                                fonts = fonts,
                                kind = ButtonKind.Paper,
                                compact = true,
                                enabled = !saving
                            )
                        }
                    }
                }

                Text(
                    text = "Ajouter un totem (rare, à utiliser quand l'histoire l'introduit)",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = Ink),
                    modifier = Modifier.padding(top = 6.dp)
                )
                ComicTextField("Nom", label, { label = it }, fonts, enabled = !saving, singleLine = true, onPaper = true)
                ComicTextField(
                    "Pouvoirs (séparés par des virgules)", powers, { powers = it }, fonts,
                    enabled = !saving, minHeight = 60.dp, onPaper = true
                )
                ComicTextField(
                    "Capacité spéciale (optionnel)", special, { special = it }, fonts,
                    enabled = !saving, minHeight = 60.dp, onPaper = true
                )
                ComicTextField(
                    "Emoji (si pas d'image)", emoji, { emoji = it }, fonts,
                    enabled = !saving, singleLine = true, onPaper = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ComicButton(
                        text = if (imageUri == null) "Image (optionnel)" else "Changer l'image",
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        fonts = fonts,
                        kind = ButtonKind.Paper,
                        compact = true,
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                    imageUri?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(2.dp, Ink, RoundedCornerShape(8.dp))
                        )
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontFamily = fonts.body, fontSize = 14.sp, color = DangerText)
                    )
                }
                if (saving) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ink)
                    }
                }

                ComicButton(
                    text = "➕ Ajouter ce totem",
                    onClick = { addTotem() },
                    fonts = fonts,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
                ComicButton(
                    text = "Fermer",
                    onClick = onDismiss,
                    fonts = fonts,
                    kind = ButtonKind.Paper,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// =====================================================================
// Champ de saisie "BD" (fond transparent, texte blanc ; ou blanc/noir sur creme)
// =====================================================================

@Composable
private fun ComicTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minHeight: Dp = 0.dp,
    onPaper: Boolean = false
) {
    val contentColor = if (onPaper) Ink else Color.White
    val fill = if (onPaper) Color.White else Color.Transparent
    val shadow = if (onPaper) null else TextShadow
    val shape = RoundedCornerShape(8.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 14.sp, color = contentColor, shadow = shadow),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            enabled = enabled,
            textStyle = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = contentColor, shadow = shadow),
            cursorBrush = SolidColor(contentColor),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.6f)
                .heightIn(min = minHeight)
                .clip(shape)
                .background(fill)
                .border(2.dp, Ink, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
