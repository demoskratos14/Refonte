package com.aventure.desdice

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.aventure.desdice.screens.DieBox
import com.aventure.desdice.screens.FateDieFace
import com.aventure.desdice.screens.FateFace
import com.aventure.desdice.screens.SpinDurationMs
import com.aventure.desdice.screens.SpinState
import com.aventure.desdice.screens.TumblingDie
import com.aventure.desdice.screens.newSpinSpec
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

// ---------------------------------------------------------------------
// Palette (celle de l'ancienne version web et des autres ecrans restyles)
// ---------------------------------------------------------------------
internal val Ink = Color(0xFF14161A)
internal val Paper = Color(0xFFFBF3E1)
internal val Red = Color(0xFFE0263C)
internal val Gold = Color(0xFFFFCD3C)
internal val DangerText = Color(0xFF8A1020)
internal val ErrorOnPhoto = Color(0xFFFFC9C9)
private val PageBg = Color(0xFF14161A)

// ---------------------------------------------------------------------
// Reglages de lisibilite (a ajuster si besoin)
// ---------------------------------------------------------------------
// Voile sombre par-dessus l'image de l'histoire (haut -> 30 % -> bas). Plus
// l'alpha (les 2 premiers chiffres apres 0x) est petit, plus l'image se voit.
private val ScrimTop = Color(0x4D000000)
private val ScrimMid = Color(0x59000000)
private val ScrimBottom = Color(0x8C000000)
internal val DividerColor = Color(0x4DFFFFFF)
// Ombre portee des textes poses directement sur l'image.
internal val TextShadow = Shadow(Color(0xCC000000), Offset(1.5f, 2f), 6f)
// Fond des boutons "secondaires" : transparent (comme l'ecran Nouvelle histoire).
// Mettre Color.White pour retrouver des boutons blancs.
private val SecondaryButtonFill = Color.Transparent

private const val TOTEM_THRESHOLD = 15
private const val THREAT_THRESHOLD = 10

private val FATE_OPTIONS = listOf(
    "coeur" to "❤️ Cœur",
    "question" to "❓ Question",
    "soleil" to "☀️ Soleil",
    "etoile" to "⭐ Étoile",
    "exclamation" to "❗ Exclamation",
    "spirale" to "🌀 Spirale"
)

// =====================================================================
// Ecran de jeu
// =====================================================================

/**
 * Page de jeu, habillee comme les autres ecrans : l'image de l'histoire en
 * fond plein ecran (la meme que dans le carrousel de choix), sections sans
 * carte, titres Bangers, boutons "BD".
 *
 * Le titre d'en-tete vient de session_to_dict()["header_title"] (a ajouter
 * dans game_api.py, voir la note qui accompagne ce fichier).
 */
@Composable
fun MainGameScreen(
    viewModel: GameViewModel,
    onChangeStory: () -> Unit = {},
    // Pour le bouton "Ecouter" du panneau de narration (absent : bouton masque).
    speechManager: SpeechManager? = null,
    // Ouvre l'ecran de configuration de la cle Mistral (absent : bouton masque).
    onConfigureKey: (() -> Unit)? = null
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val stories by viewModel.stories.collectAsState()
    val currentSlug by viewModel.currentStorySlug.collectAsState()
    val fonts = rememberAppFonts()

    var showAllowedValues by remember { mutableStateOf(false) }
    // Totem dont la fiche (pouvoirs / capacite speciale) est affichee.
    var infoTotemKey by remember { mutableStateOf<String?>(null) }

    // Cle Mistral enregistree ou non : determine si la narration automatique est
    // active (panneau de narration) et le libelle du bloc "Continuer l'aventure".
    val python = remember { Python.getInstance() }
    var hasKey by remember { mutableStateOf(false) }
    fun refreshKeyState() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = python.getModule("game_api")
                    .callAttr("call_json", "get_config_screen_state")
                    .toString()
                hasKey = JSONObject(r).optBoolean("has_key", false)
            } catch (e: Exception) {
                // On garde l'etat precedent.
            }
        }
    }
    LaunchedEffect(Unit) { refreshKeyState() }

    val bgB64 = stories.firstOrNull { it.slug == currentSlug }?.bgImageB64.orEmpty()
    val isCustomStory = stories.firstOrNull { it.slug == currentSlug }?.isCustom == true
    val headerTitle = sessionState?.optString("header_title").orEmpty()
        .ifEmpty { "Les Dés de l'Aventure" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        StoryBackdrop(bgB64)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GameHeader(headerTitle = headerTitle, fonts = fonts, onChangeStory = onChangeStory)
            TotemBadgesRow(state = sessionState, onTotemClick = { infoTotemKey = it })

            DiceResultCard(viewModel = viewModel)
            ThreatGauge(viewModel = viewModel)
            SymbolPicker(viewModel = viewModel)

            Section {
                ComicButton(
                    text = "Configurer les valeurs autorisées",
                    onClick = { showAllowedValues = true },
                    fonts = fonts,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            NarrationSection(
                viewModel = viewModel,
                hasKey = hasKey,
                onKeyChanged = { refreshKeyState() },
                speechManager = speechManager,
                onConfigureKey = onConfigureKey
            )

            TotemGaugesRow(viewModel = viewModel, onTotemClick = { infoTotemKey = it })
            SideQuestsList(viewModel = viewModel)
            ContinueSection(viewModel = viewModel, hasKey = hasKey)
            HistoryList(viewModel = viewModel)
            JournalSection(viewModel = viewModel, isCustomStory = isCustomStory)
        }
    }

    infoTotemKey?.let { key ->
        TotemInfoDialog(
            totemKey = key,
            state = sessionState,
            onDismiss = { infoTotemKey = null }
        )
    }

    if (showAllowedValues) {
        AllowedValuesDialog(
            viewModel = viewModel,
            onDismiss = { showAllowedValues = false }
        )
    }
}

@Composable
private fun GameHeader(headerTitle: String, fonts: AppFonts, onChangeStory: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = headerTitle,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 26.sp,
                letterSpacing = 1.sp,
                color = Color.White,
                shadow = Shadow(Color(0xB3000000), Offset(0f, 3f), 8f)
            )
        )
        Text(
            text = "Prêt pour l'aventure !",
            style = bodyStyle(fonts, 15.sp, Color.White.copy(alpha = 0.92f)),
            modifier = Modifier.padding(top = 2.dp)
        )
        // Equivalent du lien "Changer d'histoire" de l'ancienne page de jeu.
        Text(
            text = "\uD83D\uDD01 Changer d'histoire",
            style = bodyStyle(fonts, 14.sp).copy(textDecoration = TextDecoration.Underline),
            modifier = Modifier
                .clickable(onClick = onChangeStory)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** Image de l'histoire en fond plein ecran + voile sombre (le contenu defile par-dessus). */
@Composable
private fun StoryBackdrop(b64: String) {
    val bitmap by produceState<ImageBitmap?>(null, b64) {
        value = if (b64.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to ScrimTop,
                    0.3f to ScrimMid,
                    1f to ScrimBottom
                )
            )
    )
}

// =====================================================================
// Dernier resultat + boutons de lancer
// =====================================================================

@Composable
fun DiceResultCard(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fateFaces by viewModel.fateFaces.collectAsState()
    val python = remember { Python.getInstance() }
    val fonts = rememberAppFonts()
    val scope = rememberCoroutineScope()

    var rolling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Animation de lancer : memes des 3D que la page "Des classiques". Le resultat
    // est deja tire ; l'etat de session n'est publie qu'a la fin de l'animation
    // pour ne pas gacher le suspense.
    var spin by remember { mutableStateOf<SpinState?>(null) }
    val progress = remember { Animatable(0f) }

    fun roll(kind: String) {
        if (rolling) return
        scope.launch {
            rolling = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    python.getModule("game_api")
                        .callAttr("call_json", "do_roll", kind)
                        .toString()
                }
                val last = JSONObject(result).optJSONObject("last_result")
                val successValue =
                    if (last != null && !last.isNull("success")) last.optInt("success") else null
                val fateKey = last?.str("fate").orEmpty()

                val cubeFaces = fateFaces.take(6)
                val successSpec =
                    if (kind != "fate" && successValue != null && successValue in 1..6) {
                        newSpinSpec(faceIndex = successValue - 1, bounces = 3, timeScale = 1f)
                    } else null
                val fateIndex =
                    if (kind != "success" && fateKey.isNotEmpty() && cubeFaces.size == 6) {
                        cubeFaces.indexOfFirst { it.optString("key") == fateKey }
                    } else -1
                val fateSpec =
                    if (fateIndex >= 0) {
                        newSpinSpec(faceIndex = fateIndex, bounces = 2, timeScale = 0.88f)
                    } else null

                if (successSpec != null || fateSpec != null) {
                    spin = SpinState(successSpec, fateSpec)
                    progress.snapTo(0f)
                    progress.animateTo(
                        1f,
                        tween(durationMillis = SpinDurationMs, easing = LinearEasing)
                    )
                }
                spin = null
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                spin = null
                rolling = false
            }
        }
    }

    // Derniere valeur montree sur chaque de (comme last_of("success") / last_of("fate")).
    val history = sessionState?.optJSONArray("history")
    val lastSuccessRec = lastRecordWith(history, "success")
    val lastFateRec = lastRecordWith(history, "fate")
    val lastRec = history?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1) else null }
    val successValue = lastSuccessRec?.optInt("success")
    val pipKeys: List<String> = lastSuccessRec?.optJSONArray("pip_choice")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        ?.takeIf { it.isNotEmpty() }
        ?: List(successValue ?: 1) { sessionState?.optString("pip_symbol").orEmpty() }
    val fateFace: FateFace? = lastFateRec?.str("fate")?.let { key ->
        fateFaces.firstOrNull { it.optString("key") == key }
            ?.let { FateFace(it.optString("emoji"), it.optString("label")) }
    }
    // Comme l'ancienne page : le de qui n'a pas servi au dernier lancer est estompe.
    val successUsed = lastRec == null || !lastRec.isNull("success")
    val fateUsed = lastRec == null || lastRec.str("fate").isNotEmpty()
    val cubeFateFaces = fateFaces.take(6).map { FateFace(it.optString("emoji"), it.optString("label")) }
    val description = sessionState?.optJSONObject("last_result")?.str("description").orEmpty()

    Section(modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val successDie: @Composable () -> Unit = {
                DieColumn(
                    caption = "Dé de réussite",
                    buttonText = "🎲 Lancer",
                    primary = true,
                    enabled = !rolling,
                    dimmed = !successUsed,
                    onRoll = { roll("success") },
                    fonts = fonts
                ) {
                    val spec = spin?.success
                    if (spec != null) {
                        Box(Modifier.size(150.dp).graphicsLayer { rotationZ = -1f }) {
                            TumblingDie(spec, progress, null, fonts, Color.White)
                        }
                    } else {
                        DieBox(rotation = -1f, background = Color.White) {
                            SymbolSuccessDieFace(
                                value = successValue,
                                pipKeys = pipKeys,
                                state = sessionState,
                                placeholderKey = sessionState?.optString("pip_symbol").orEmpty()
                            )
                        }
                    }
                }
            }
            val fateDie: @Composable () -> Unit = {
                DieColumn(
                    caption = "Dé du destin",
                    buttonText = "🔮 Lancer",
                    primary = true,
                    enabled = !rolling,
                    dimmed = !fateUsed,
                    onRoll = { roll("fate") },
                    fonts = fonts
                ) {
                    val spec = spin?.fate
                    if (spec != null) {
                        Box(Modifier.size(150.dp).graphicsLayer { rotationZ = 1f }) {
                            TumblingDie(spec, progress, cubeFateFaces, fonts, FateDieBg)
                        }
                    } else {
                        DieBox(rotation = 1f, background = FateDieBg) {
                            FateDieFace(fateFace, fonts)
                        }
                    }
                }
            }

            if (maxWidth >= 316.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    successDie()
                    fateDie()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    successDie()
                    fateDie()
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        ComicButton(
            text = "⚡ Lancer les deux dés ensemble",
            onClick = { roll("both") },
            fonts = fonts,
            enabled = !rolling,
            modifier = Modifier.fillMaxWidth()
        )

        if (description.isNotEmpty() && !rolling) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle(fonts, 14.sp)
            )
        }

        // Evenements "?" / "!" : texte a signaler a l'IA narratrice (copiable).
        val narratorNote = if (rolling) "" else narratorNoteFor(
            sessionState?.optJSONObject("last_result"), fateFaces
        )
        if (narratorNote.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            NarratorNoteBox(text = narratorNote, fonts = fonts)
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, style = bodyStyle(fonts, 14.sp, ErrorOnPhoto))
        }
    }
}

/** Fond creme du de du destin (comme la page "Des classiques"). */
private val FateDieBg = Color(0xFFFFF7E0)

/** Dernier enregistrement de l'historique dont le champ `field` est renseigne (ni absent, ni null, ni vide). */
private fun lastRecordWith(history: org.json.JSONArray?, field: String): JSONObject? {
    if (history == null) return null
    for (i in history.length() - 1 downTo 0) {
        val record = history.getJSONObject(i)
        if (!record.isNull(field) && record.optString(field, "").isNotEmpty()) return record
    }
    return null
}

@Composable
private fun DieColumn(
    caption: String,
    buttonText: String,
    primary: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    onRoll: () -> Unit,
    fonts: AppFonts,
    die: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.alpha(if (dimmed) 0.45f else 1f)) { die() }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = caption,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 13.6.sp,
                color = Color.White,
                shadow = TextShadow
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ComicButton(
            text = buttonText,
            onClick = onRoll,
            fonts = fonts,
            kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary,
            enabled = enabled,
            compact = true,
            modifier = Modifier.width(150.dp)
        )
    }
}

// =====================================================================
// Jauge de menace
// =====================================================================

@Composable
fun ThreatGauge(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()
    val threatLevel = sessionState?.optInt("threat_level", 0) ?: 0
    val reached = threatLevel >= THREAT_THRESHOLD

    Section(modifier) {
        SectionTitle("Jauge de menace", fonts)
        Text(
            text = "Niveau actuel : $threatLevel/$THREAT_THRESHOLD",
            style = bodyStyle(fonts, 15.sp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        GaugeBar(
            fraction = threatLevel.toFloat() / THREAT_THRESHOLD,
            color = if (reached) Red else Gold,
            height = 12.dp
        )
        if (reached) {
            Text(
                text = "⚠️ Seuil atteint : complication secondaire !",
                style = bodyStyle(fonts, 14.sp, ErrorOnPhoto),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// =====================================================================
// Symbole actif (selection + mode de lancer)
// =====================================================================

/**
 * Symbole actif : un resume (mode + symboles utilises) et un bouton qui ouvre la
 * fenetre de choix -- pour ne pas surcharger la page de jeu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymbolPicker(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()
    var showDialog by remember { mutableStateOf(false) }

    val pipMode = sessionState?.optString("pip_mode").orEmpty().ifEmpty { "single" }
    val activeKeys: List<String> = if (pipMode == "single") {
        listOfNotNull(sessionState?.optString("pip_symbol")?.takeIf { it.isNotEmpty() })
    } else {
        sessionState?.optJSONArray("enabled_symbols")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: emptyList()
    }
    val summary = when (pipMode) {
        "random" -> "🎲 Aléatoire : ${activeKeys.size} symbole(s) dans le tirage"
        "mixed" -> "🔀 Mixte : ${activeKeys.size} symbole(s) mélangés sur la face"
        else -> "Symbole unique : " +
            (findSymbol(sessionState, activeKeys.firstOrNull().orEmpty())?.str("label").orEmpty())
    }

    Section(modifier) {
        SectionTitle("Symbole actif", fonts)
        Text(text = summary, style = bodyStyle(fonts, 15.sp))
        if (activeKeys.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                activeKeys.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        SymbolIcon(findSymbol(sessionState, key), 26.dp, 20.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        ComicButton(
            text = "Choisir le symbole",
            onClick = { showDialog = true },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showDialog) {
        SymbolPickerDialog(viewModel = viewModel, onDismiss = { showDialog = false })
    }
}

/** Fenetre de choix : mode de lancer (unique / aleatoire / mixte) + liste des symboles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPickerDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pipMode = sessionState?.optString("pip_mode").orEmpty().ifEmpty { "single" }
    val activeSingle = sessionState?.optString("pip_symbol").orEmpty()
    val enabled: Set<String> = sessionState?.optJSONArray("enabled_symbols")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
        ?: emptySet()
    val symbols = buildList<JSONObject> {
        sessionState?.optJSONArray("all_symbols")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
        }
    }

    fun callApi(func: String, arg: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                isLoading = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", func, arg)
                    .toString()
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { isLoading = false }
            }
        }
    }

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
                    text = "Symboles",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Mode de lancer",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Le moteur (DiceSession.set_pip_mode) fonctionne en bascule : il
                    // n'accepte que "random" / "mixed", et redemander le mode deja actif
                    // le desactive (retour au symbole unique).
                    ModeOption(
                        text = "Unique",
                        selected = pipMode == "single",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "single") callApi("do_set_pip_mode", pipMode) }
                    )
                    ModeOption(
                        text = "🎲 Aléatoire",
                        selected = pipMode == "random",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "random") callApi("do_set_pip_mode", "random") }
                    )
                    ModeOption(
                        text = "🔀 Mixte",
                        selected = pipMode == "mixed",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "mixed") callApi("do_set_pip_mode", "mixed") }
                    )
                }
                Text(
                    text = when (pipMode) {
                        "random" -> "Un symbole tiré au sort par lancer. Touche les symboles pour les inclure ou les exclure du tirage (un au moins reste actif)."
                        "mixed" -> "Un symbole différent sur chaque point du dé. Touche les symboles pour les inclure ou les exclure du tirage (un au moins reste actif)."
                        else -> "Touche un symbole pour le choisir."
                    },
                    style = TextStyle(fontFamily = fonts.body, fontSize = 13.sp, color = Ink),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    symbols.forEach { symbol ->
                        val key = symbol.optString("key")
                        val selected = if (pipMode == "single") key == activeSingle else key in enabled
                        val chipShape = RoundedCornerShape(20.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .alpha(if (selected) 1f else 0.7f)
                                .clip(chipShape)
                                .background(if (selected) Red else Color.White)
                                .border(2.dp, Ink, chipShape)
                                .clickable(enabled = !isLoading) {
                                    if (pipMode == "single") {
                                        callApi("do_set_pip_symbol", key)
                                    } else {
                                        callApi("do_toggle_enabled_symbol", key)
                                    }
                                }
                                .padding(horizontal = 12.dp)
                        ) {
                            SymbolIcon(symbol, 22.dp, 18.sp)
                            Text(
                                text = symbol.optString("label"),
                                maxLines = 1,
                                style = TextStyle(
                                    fontFamily = fonts.bodyBold,
                                    fontSize = 14.sp,
                                    color = if (selected) Color.White else Ink
                                )
                            )
                        }
                    }
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ink)
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontFamily = fonts.body, fontSize = 14.sp, color = DangerText),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
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

@Composable
private fun ModeOption(
    text: String,
    selected: Boolean,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Red else Color.White)
            .border(2.dp, Ink, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
                color = if (selected) Color.White else Ink
            )
        )
    }
}

// =====================================================================
// Jauges totemiques
// =====================================================================

@Composable
fun TotemGaugesRow(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    // Clic sur l'icone d'un totem : ouvre sa fiche (pouvoirs / capacite speciale).
    onTotemClick: ((String) -> Unit)? = null
) {
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()

    var symbols by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Texte narratif renvoye par do_use_totem_energy quand un pouvoir se
    // manifeste -- distinct de "error", reserve aux vraies erreurs techniques.
    var effectMessage by remember { mutableStateOf<String?>(null) }
    var showTotemManager by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        val session = sessionState
        symbols = buildMap {
            session?.optJSONArray("all_symbols")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    put(obj.optString("key"), obj)
                }
            }
        }
    }

    fun useTotem(key: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                isLoading = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", "do_use_totem_energy", key)
                    .toString()
                val parsed = JSONObject(result)
                val effect = parsed.str("effect")
                val aiError = parsed.str("ai_error")
                if (effect.isNotEmpty()) effectMessage = effect
                if (aiError.isNotEmpty()) error = aiError
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { isLoading = false }
            }
        }
    }

    Section(modifier) {
        SectionTitle("Jauges totémiques", fonts)

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(symbols.toList()) { (key, symbol) ->
                    // Comme l'ancienne page : icone cliquable seulement si une fiche existe.
                    val hasInfo = sessionState?.optJSONObject("totem_info")?.has(key) == true
                    TotemGaugeItem(
                        symbol = symbol,
                        energy = sessionState?.optJSONObject("totem_energy")?.optInt(key, 0) ?: 0,
                        fonts = fonts,
                        onUse = { useTotem(key) },
                        onInfo = if (hasInfo) ({ onTotemClick?.invoke(key) }) else null
                    )
                }
            }
        }

        // Affiches en dehors du "if (isLoading)" : sinon le message pose juste
        // avant la fin du chargement disparaissait aussitot.
        error?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp, ErrorOnPhoto),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        effectMessage?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        ComicButton(
            text = "➕ Ajouter / gérer les totems",
            onClick = { showTotemManager = true },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showTotemManager) {
        TotemManagerDialog(
            viewModel = viewModel,
            onDismiss = { showTotemManager = false }
        )
    }
}

@Composable
private fun TotemGaugeItem(
    symbol: JSONObject,
    energy: Int,
    fonts: AppFonts,
    onUse: () -> Unit,
    onInfo: (() -> Unit)? = null
) {
    val isReady = energy >= TOTEM_THRESHOLD

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(92.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isReady) Gold.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.25f))
                .border(
                    if (isReady) 3.dp else 2.dp,
                    if (isReady) Gold else Color.White.copy(alpha = 0.5f),
                    CircleShape
                )
                .then(if (onInfo != null) Modifier.clickable(onClick = onInfo) else Modifier)
        ) {
            SymbolIcon(symbol, 46.dp, 30.sp)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = symbol.optString("label"),
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = bodyStyle(fonts, 12.sp)
        )
        Text(
            text = "$energy/$TOTEM_THRESHOLD",
            style = boldStyle(fonts, 13.sp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        GaugeBar(
            fraction = energy.toFloat() / TOTEM_THRESHOLD,
            color = if (isReady) Gold else Color.White,
            height = 8.dp
        )

        Spacer(modifier = Modifier.height(6.dp))

        ComicButton(
            text = "Utiliser",
            onClick = onUse,
            fonts = fonts,
            enabled = isReady,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =====================================================================
// Quetes secondaires
// =====================================================================

@Composable
fun SideQuestsList(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

    var sideQuests by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionState) {
        sideQuests = buildList {
            sessionState?.optJSONArray("side_quests")?.let { arr ->
                for (i in 0 until arr.length()) {
                    add(arr.getJSONObject(i))
                }
            }
        }
    }

    fun completeQuest(quest: JSONObject) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                isLoading = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", "do_complete_side_quest", quest.optInt("id"))
                    .toString()
                viewModel.loadSessionState(result)
                sideQuests = sideQuests.filter { it.optInt("id") != quest.optInt("id") }
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { isLoading = false }
            }
        }
    }

    Section(modifier) {
        SectionTitle("Quêtes secondaires", fonts)

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (sideQuests.isEmpty()) {
            Text(
                text = "Aucune quête secondaire en cours",
                style = bodyStyle(fonts, 14.sp)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(sideQuests) { quest ->
                    SideQuestItem(
                        quest = quest,
                        fonts = fonts,
                        onComplete = { completeQuest(quest) }
                    )
                }
            }
        }

        error?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp, ErrorOnPhoto),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SideQuestItem(
    quest: JSONObject,
    fonts: AppFonts,
    onComplete: () -> Unit
) {
    val kind = quest.optString("kind")
    val status = quest.optString("status")
    val kindText = if (kind == "ami") "Nouvel ami" else "Nouvel objet/totem"
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(shape)
            .background(Ink.copy(alpha = 0.45f))
            .border(2.dp, Color.White.copy(alpha = 0.4f), shape)
            .padding(10.dp)
    ) {
        Text(text = "⭐ Quête secondaire", style = boldStyle(fonts, 12.sp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = kindText, style = bodyStyle(fonts, 13.sp))
        Spacer(modifier = Modifier.height(8.dp))
        ComicButton(
            text = "Terminer",
            onClick = onComplete,
            fonts = fonts,
            enabled = status == "ouverte",
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =====================================================================
// Historique des lancers
// =====================================================================

@Composable
fun HistoryList(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fateFaces by viewModel.fateFaces.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

    var showClearConfirm by remember { mutableStateOf(false) }

    // Du plus recent au plus ancien.
    val records = buildList<JSONObject> {
        sessionState?.optJSONArray("history")?.let { history ->
            for (i in history.length() - 1 downTo 0) {
                add(history.getJSONObject(i))
            }
        }
    }

    fun callSimple(func: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            // Un seul mutex.withLock : le Mutex de kotlinx.coroutines n'est pas
            // reentrant, l'ancien code imbrique (withLock dans withLock) bloquait
            // definitivement sur "Annuler".
            mutex.withLock {
                try {
                    val result = python.getModule("game_api")
                        .callAttr("call_json", func)
                        .toString()
                    viewModel.loadSessionState(result)
                } catch (e: Exception) {
                    // Ignore, comme avant.
                }
            }
        }
    }

    Section(modifier) {
        SectionTitle("Dernier lancer", fonts)

        if (records.isEmpty()) {
            Text(
                text = "(aucun lancer pour l'instant)",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle(fonts, 14.sp, Color.White.copy(alpha = 0.9f))
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                records.forEachIndexed { index, record ->
                    HistoryItem(
                        record = record,
                        sessionState = sessionState,
                        fateFaces = fateFaces,
                        fonts = fonts
                    )
                    if (index < records.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DividerColor)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ComicButton(
                text = "↩ Annuler",
                onClick = { callSimple("do_undo") },
                fonts = fonts,
                kind = ButtonKind.Secondary,
                modifier = Modifier.weight(1f)
            )
            ComicButton(
                text = "Effacer",
                onClick = { showClearConfirm = true },
                fonts = fonts,
                kind = ButtonKind.Secondary,
                textColor = ErrorOnPhoto,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Paper,
            title = {
                Text(
                    "Effacer l'historique",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 22.sp, color = Ink)
                )
            },
            text = {
                Text(
                    "Effacer tout l'historique des lancers de cette histoire ?",
                    style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    callSimple("do_clear")
                }) {
                    Text(
                        "Effacer",
                        style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = DangerText)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(
                        "Annuler",
                        style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = Ink)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryItem(
    record: JSONObject,
    sessionState: JSONObject?,
    fateFaces: List<JSONObject>,
    fonts: AppFonts
) {
    val success = record.optInt("success", -1)
    val fate = record.str("fate")
    val note = record.str("note")
    val pipKey = record.optJSONArray("pip_choice")
        ?.let { if (it.length() > 0) it.optString(0) else null }
        ?: sessionState?.optString("pip_symbol").orEmpty()
    val fateFace = if (fate.isNotEmpty()) {
        fateFaces.firstOrNull { it.optString("key") == fate }
    } else {
        null
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(text = "#${record.optInt("id")}", style = boldStyle(fonts, 15.sp))
        if (note.isNotEmpty()) {
            Text(text = "[$note]", style = bodyStyle(fonts, 13.sp, Gold))
        }
        if (success != -1) {
            Text(text = "Réussite=$success", style = bodyStyle(fonts, 15.sp))
            SymbolIcon(findSymbol(sessionState, pipKey), 20.dp, 16.sp, fallback = pipKey)
        }
        if (fateFace != null) {
            Text(
                text = "Destin=${fateFace.optString("emoji")} ${fateFace.optString("label")}",
                style = bodyStyle(fonts, 15.sp)
            )
        }
    }
}

// =====================================================================
// Valeurs autorisees (fenetre)
// =====================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowedValuesDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val fonts = rememberAppFonts()

    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var allowedValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var allowedFates by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(sessionState) {
        allowedValues = sessionState?.optJSONArray("allowed_success_values")?.let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        } ?: listOf(1, 2, 3, 4, 5, 6)

        allowedFates = sessionState?.optJSONArray("allowed_fate_keys")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: FATE_OPTIONS.map { it.first }
    }

    // La liste affichee est rafraichie par le LaunchedEffect ci-dessus des que
    // loadSessionState() publie le nouvel etat.
    fun callApi(func: String, vararg args: Any) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                isLoading = true
                error = null
            }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", func, *args)
                    .toString()
                viewModel.loadSessionState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                mutex.withLock { isLoading = false }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
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
                    text = "Valeurs autorisées",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Dé de réussite (1-6)",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (1..6).forEach { value ->
                        ToggleRow(
                            checked = value in allowedValues,
                            label = value.toString(),
                            fonts = fonts,
                            onToggle = { callApi("do_toggle_allowed_value", value) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ComicButton(
                    text = "Réinitialiser",
                    onClick = { callApi("do_reset_allowed_values") },
                    fonts = fonts,
                    kind = ButtonKind.Paper,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Dé du destin",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FATE_OPTIONS.forEach { (key, label) ->
                        ToggleRow(
                            checked = key in allowedFates,
                            label = label,
                            fonts = fonts,
                            onToggle = { callApi("do_toggle_allowed_fate", key) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ComicButton(
                    text = "Réinitialiser",
                    onClick = { callApi("do_reset_allowed_fate") },
                    fonts = fonts,
                    kind = ButtonKind.Paper,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isLoading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ink)
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = TextStyle(fontFamily = fonts.body, fontSize = 14.sp, color = DangerText)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

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

@Composable
private fun ToggleRow(
    checked: Boolean,
    label: String,
    fonts: AppFonts,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .toggleable(
                value = checked,
                onValueChange = { onToggle() },
                role = Role.Checkbox
            )
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) Red else Color.White)
                .border(2.dp, Ink, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(text = "✓", style = TextStyle(fontSize = 15.sp, color = Color.White))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink)
        )
    }
}

// =====================================================================
// Briques visuelles communes
// =====================================================================

internal enum class ButtonKind { Primary, Secondary, Paper }

/** Bloc sans fond ni bordure (l'image reste visible), largeur limitee. */
@Composable
internal fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        content = content
    )
}

@Composable
internal fun SectionTitle(text: String, fonts: AppFonts, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 8.dp),
        style = TextStyle(
            fontFamily = fonts.display,
            fontSize = 20.sp,
            letterSpacing = 0.5.sp,
            color = Color.White,
            shadow = TextShadow
        )
    )
}

internal fun bodyStyle(fonts: AppFonts, size: TextUnit, color: Color = Color.White) = TextStyle(
    fontFamily = fonts.body,
    fontSize = size,
    color = color,
    shadow = TextShadow
)

internal fun boldStyle(fonts: AppFonts, size: TextUnit, color: Color = Color.White) = TextStyle(
    fontFamily = fonts.bodyBold,
    fontSize = size,
    color = color,
    shadow = TextShadow
)

/** Ombre pleine decalee vers le bas/droite, comme `box-shadow: Xpx Xpx 0 couleur` en CSS. */
private fun Modifier.hardShadow(offset: Dp, radius: Dp, color: Color): Modifier =
    this.drawBehind {
        val o = offset.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(o, o),
            size = size,
            cornerRadius = CornerRadius(radius.toPx())
        )
    }

/**
 * Bouton "BD". Primary : rouge, texte blanc. Secondary : transparent, texte blanc
 * ombre (l'image reste visible). Paper : fond blanc, texte noir (pour les fenetres
 * sur fond creme).
 */
@Composable
internal fun ComicButton(
    text: String,
    onClick: () -> Unit,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    textColor: Color? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when (kind) {
        ButtonKind.Primary -> Red
        ButtonKind.Secondary -> SecondaryButtonFill
        ButtonKind.Paper -> Color.White
    }
    // "Ombre" decalee facon BD : uniquement si le bouton a un fond. Sur un bouton
    // transparent elle serait visible A TRAVERS le bouton.
    val hasFill = fill.alpha > 0f
    val contentColor = textColor ?: if (kind == ButtonKind.Paper) Ink else Color.White
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .offset(x = if (pressed) 2.dp else 0.dp, y = if (pressed) 2.dp else 0.dp)
            .then(if (hasFill) Modifier.hardShadow(if (pressed) 1.dp else 3.dp, 10.dp, Ink) else Modifier)
            .background(fill, shape)
            .border(if (compact) 2.dp else 3.dp, Ink, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = if (compact) 8.dp else 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = if (compact) 14.sp else 17.sp,
                letterSpacing = 1.sp,
                color = contentColor,
                shadow = if (hasFill) null else TextShadow
            )
        )
    }
}

/** Barre de progression a plat (a la place de LinearProgressIndicator). */
@Composable
private fun GaugeBar(fraction: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.25f))
            .border(1.5.dp, Ink.copy(alpha = 0.7f), shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color)
        )
    }
}

/**
 * Chaine d'un champ JSON, "" si absent OU null. (optString(nom, "") renvoie la
 * chaine "null" quand la valeur JSON est null -- ex. "ai_error": null.)
 */
internal fun JSONObject.str(name: String): String =
    if (isNull(name)) "" else optString(name, "")

internal fun findSymbol(state: JSONObject?, key: String): JSONObject? {
    val arr = state?.optJSONArray("all_symbols") ?: return null
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        if (obj.optString("key") == key) return obj
    }
    return null
}

// Petit cache : les images de totem (400 px max) sont reaffichees a chaque recomposition.
private val totemBitmapCache = HashMap<String, ImageBitmap>()

/**
 * Icone d'un symbole/totem. `image` (game_api.all_symbols) est un NOM DE FICHIER stocke
 * dans <filesDir>/totem_images/ (le dossier de travail Python est filesDir) -- pas du
 * base64. Priorite comme dans l'ancienne page (render_pip_symbol) : image > emoji >
 * texte de repli.
 */
@Composable
internal fun SymbolIcon(
    symbol: JSONObject?,
    size: Dp,
    fontSize: TextUnit,
    fallback: String = ""
) {
    val context = LocalContext.current
    val imageName = symbol?.str("image").orEmpty()
    val emoji = symbol?.str("emoji").orEmpty().ifEmpty { fallback }

    if (imageName.isNotEmpty()) {
        val cached = synchronized(totemBitmapCache) { totemBitmapCache[imageName] }
        val bitmap by produceState<ImageBitmap?>(cached, imageName) {
            if (value == null) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val file = File(context.filesDir, "totem_images/$imageName")
                        val decoded = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                        if (decoded != null) {
                            synchronized(totemBitmapCache) { totemBitmapCache[imageName] = decoded }
                        }
                        decoded
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = symbol?.str("label"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        } else {
            Text(text = emoji, style = TextStyle(fontSize = fontSize))
        }
    } else {
        Text(text = emoji, style = TextStyle(fontSize = fontSize))
    }
}
