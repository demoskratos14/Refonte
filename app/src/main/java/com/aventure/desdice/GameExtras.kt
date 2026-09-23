package com.aventure.desdice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Blocs de l'ancienne page de jeu (dice_web.py, index()), portes vers le moteur
 * Kotlin (GameEngine, via GameViewModel) :
 *   - Note "a copier pour signaler a l'IA"                  -> NarratorNoteBox
 *   - Demarrer / relancer un chapitre, ou copier le prompt  -> ContinueSection
 *   - Journal de l'histoire (ajout, lecture, export)        -> JournalSection
 *
 * La narration automatique (render_ai_panel_html -> NarrationSection) et la
 * gestion des totems ajoutes en cours de partie (TotemManagerDialog) ont ete
 * deplacees dans leurs propres fichiers lors du portage : voir AiPanel.kt et
 * TotemManagementDialog.kt (com.aventure.desdice.screens). MainGameScreen.kt
 * appelle desormais ceux-la, pas les composables de ce fichier.
 *
 * Meme habillage que MainGameScreen.kt (dont on reutilise les briques : Section,
 * ComicButton, styles de texte...).
 */


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
    val fonts = rememberAppFonts()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val chapters = sessionState?.optJSONArray("story_log")?.length() ?: 0

    // sendFullPrompt() (GameViewModel) est "fire-and-forget" : elle publie
    // elle-meme le resultat dans sessionState (y compris ai_error). On
    // n'a donc qu'a relire ai_error et faire retomber "busy" a chaque
    // changement d'etat -- meme schema que AiPanel.kt.
    LaunchedEffect(sessionState) {
        error = sessionState?.optString("ai_error", "")?.ifEmpty { null }
        busy = false
    }

    fun sendFullPrompt() {
        busy = true
        error = null
        info = null
        viewModel.sendFullPrompt()
    }

    fun copyFullPrompt() {
        scope.launch {
            busy = true
            error = null
            info = null
            val prompt = viewModel.buildFullPromptText()
            clipboard.setText(AnnotatedString(prompt))
            info = "Prompt copié dans le presse-papiers."
            busy = false
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
    hasKey: Boolean,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()
    val saveFile = rememberFileSaver()
    val scope = rememberCoroutineScope()

    var entryText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    val chapters = buildList<String> {
        sessionState?.optJSONArray("story_log")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.optString(i))
        }
    }

    // addStoryEntry() (GameViewModel) est "fire-and-forget" : elle publie
    // elle-meme le resultat dans sessionState. On fait donc retomber "busy"
    // a chaque changement d'etat -- meme schema que ContinueSection/AiPanel.
    LaunchedEffect(sessionState) { busy = false }

    fun addEntry() {
        val text = entryText.trim()
        if (text.isEmpty()) return
        busy = true
        error = null
        viewModel.addStoryEntry(text)
        entryText = ""
    }

    /** exporter est exportJournal()/exportIdentity() (GameViewModel) : suspend, resultat direct. */
    fun runExport(exporter: suspend () -> Triple<ByteArray, String, String>, emptyMessage: String) {
        scope.launch {
            busy = true
            error = null
            val (bytes, filename, _) = exporter()
            if (bytes.isEmpty() || filename.isEmpty()) {
                error = emptyMessage
            } else {
                saveFile(bytes, filename)
            }
            busy = false
        }
    }

    Section(modifier) {
        SectionTitle("Journal de l'histoire", fonts)

        // Saisie manuelle : masquee quand l'API (clé Mistral) est active,
        // puisque c'est alors elle qui ecrit le journal automatiquement
        // (voir game_api.maybe_update_story_digest). Lecture et export
        // restent toujours utiles, donc toujours affiches plus bas.
        if (!hasKey) {
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
        }
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
            onClick = { runExport(viewModel::exportJournal, "Aucun chapitre enregistré pour l'instant.") },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (isCustomStory) {
            Spacer(modifier = Modifier.height(10.dp))
            ComicButton(
                text = "Exporter l'identité de l'histoire",
                onClick = { runExport(viewModel::exportIdentity, "Rien à exporter pour cette histoire.") },
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
