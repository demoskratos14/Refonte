package com.aventure.desdice.screens

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --- Palette de l'ancienne version web (dice_web.py) -------------------
private val Ink = Color(0xFF14161A)
private val Paper = Color(0xFFFBF3E1)
private val Red = Color(0xFFE0263C)
private val Purple = Color(0xFF6A4C93)
private val DangerText = Color(0xFF8A1020)
private val FateDieBackground = Color(0xFFFFF7E0)

// --- Reglages de lisibilite / d'animation (a ajuster si besoin) --------
// Voile sombre par-dessus la photo (haut -> 30 % -> bas). Plus les valeurs
// alpha (les 2 premiers chiffres apres 0x) sont petites, plus la photo se voit.
private val ScrimTop = Color(0x33000000)
private val ScrimMid = Color(0x14000000)
private val ScrimBottom = Color(0x59000000)
private val DividerColor = Color(0x4DFFFFFF)
// Ombre portee des textes poses directement sur la photo.
private val TextShadow = Shadow(Color(0xCC000000), Offset(1.5f, 2f), 6f)
// Duree du lancer (millisecondes).
internal const val SpinDurationMs = 1800

// Meme table que PIP_POSITIONS (dice_web.py) : (ligne, colonne) dans une grille 3x3.
private val PIP_POSITIONS: Map<Int, List<Pair<Int, Int>>> = mapOf(
    1 to listOf(2 to 2),
    2 to listOf(1 to 1, 3 to 3),
    3 to listOf(1 to 1, 2 to 2, 3 to 3),
    4 to listOf(1 to 1, 1 to 3, 3 to 1, 3 to 3),
    5 to listOf(1 to 1, 1 to 3, 2 to 2, 3 to 1, 3 to 3),
    6 to listOf(1 to 1, 1 to 3, 2 to 1, 2 to 3, 3 to 1, 3 to 3)
)

private data class ClassicRoll(
    val id: Int,
    val success: Int?,
    val fateEmoji: String?,
    val fateLabel: String?
)

internal data class FateFace(val emoji: String, val label: String)

/**
 * Symbole (image, ou emoji deja mesure) dessine a la place d'un point noir sur les
 * faces du de de reussite qui roule -- utilise par l'ecran de jeu, ou chaque pip est
 * le symbole d'un totem. Sans glyphes, le de garde ses points noirs.
 */
internal class PipGlyph(val image: ImageBitmap?, val emoji: TextLayoutResult?)

/** Taille (dp) d'un symbole sur une face portant `count` points (comme l'ancienne page de jeu). */
internal fun pipGlyphSizeDp(count: Int): Int = when (count) {
    1 -> 96
    2 -> 38
    3 -> 32
    4 -> 29
    5 -> 26
    else -> 22
}

private fun parseHistory(result: JSONObject): List<ClassicRoll> {
    val arr = result.optJSONArray("history") ?: return emptyList()
    val list = mutableListOf<ClassicRoll>()
    for (i in 0 until arr.length()) {
        val entry = arr.getJSONObject(i)
        list.add(
            ClassicRoll(
                id = entry.optInt("id"),
                success = if (entry.isNull("success")) null else entry.optInt("success"),
                fateEmoji = entry.optString("fate_emoji", "").ifEmpty { null },
                fateLabel = entry.optString("fate_label", "").ifEmpty { null }
            )
        )
    }
    return list // du plus ancien au plus recent
}

/**
 * Page "Des classiques" : le de de reussite (1-6) et le de du destin,
 * independants de toute histoire, pense comme aide-memoire pendant une
 * partie sur table. Habillage repris de render_classic_dice_page
 * (dice_web.py) : fond photo, boutons "BD", polices Bangers / Nunito.
 * Les des sont de vrais cubes 3D qui roulent, rebondissent et s'immobilisent
 * sur la face tiree (le resultat est deja tire avant l'animation).
 *
 * S'appuie sur GameViewModel.classicDiceStateAwait / classicDiceRollAwait /
 * classicDiceClearAwait / fateFaces (portage Kotlin pur, ex game_api.py).
 *
 * Necessite res/drawable/bg_classic_dice.jpg.
 */
@Composable
fun ClassicDiceScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: GameViewModel = viewModel()
) {
    val fonts = rememberAppFonts()
    // Fleche retour : si onBack n'est pas fourni, on declenche le meme retour que
    // le bouton du telephone (le BackHandler de MainActivity ferme alors la page).
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val goBack: () -> Unit = {
        if (onBack != null) onBack() else backDispatcher?.onBackPressed()
    }
    val scope = rememberCoroutineScope()
    val playDiceRollSound = rememberDiceRollSound()

    var loading by remember { mutableStateOf(true) }
    var rolling by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<ClassicRoll>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Faces du destin : exposees directement par GameViewModel (FATE_FACES
    // cote Kotlin, DiceSession.kt) -- plus besoin d'appel asynchrone a
    // get_fate_faces. On les reduit au type local FateFace (emoji +
    // libelle), seul necessaire au dessin du cube.
    val fateFaces = remember(viewModel.fateFaces) {
        viewModel.fateFaces.map { FateFace(it.emoji, it.label) }
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Animation de lancer : progression 0 -> 1 partagee par les deux des.
    var spin by remember { mutableStateOf<SpinState?>(null) }
    val progress = remember { Animatable(0f) }

    fun applyResult(result: JSONObject) {
        history = parseHistory(result).reversed() // plus recent en premier
    }

    fun roll(kind: String) {
        if (rolling) return
        scope.launch {
            rolling = true
            error = null
            try {
                // Le lancer est tire et enregistre tout de suite (cote
                // GameEngine/DiceSession) ; l'animation ne fait que "raconter"
                // ce resultat, et l'historique n'est mis a jour (et publie
                // dans le StateFlow du ViewModel) qu'a la fin, pour ne pas
                // spoiler le suspense -- meme logique que rollAwaitingAnimation()
                // sur l'ecran de jeu principal.
                val result = viewModel.classicDiceRollAwait(kind)
                val newest = parseHistory(result).lastOrNull()

                val successValue = newest?.success
                val successSpec = if (kind != "fate" && successValue != null && successValue in 1..6) {
                    newSpinSpec(faceIndex = successValue - 1, bounces = 3, timeScale = 1f)
                } else null

                val cubeFaces = fateFaces.take(6)
                val newestEmoji = newest?.fateEmoji
                val newestLabel = newest?.fateLabel.orEmpty()
                val fateIndex = if (kind != "success" && newestEmoji != null && cubeFaces.size == 6) {
                    cubeFaces.indexOfFirst { it.emoji == newestEmoji && it.label == newestLabel }
                } else -1
                val fateSpec = if (fateIndex >= 0) {
                    newSpinSpec(faceIndex = fateIndex, bounces = 2, timeScale = 0.88f)
                } else null

                if (successSpec != null || fateSpec != null) {
                    spin = SpinState(successSpec, fateSpec)
                    playDiceRollSound()
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(durationMillis = SpinDurationMs, easing = LinearEasing))
                }
                spin = null
                applyResult(result)
                viewModel.publishClassicDiceState(result)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                spin = null
                rolling = false
            }
        }
    }

    fun clearHistory() {
        if (rolling) return
        scope.launch {
            rolling = true
            error = null
            try {
                applyResult(viewModel.classicDiceClearAwait())
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                rolling = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            applyResult(viewModel.classicDiceStateAwait())
        } catch (e: Exception) {
            error = "Erreur : ${e.message}"
        }
        // Les faces du destin (fateFaces, ci-dessus) viennent directement de
        // GameViewModel.fateFaces -- plus besoin d'appel asynchrone dedie.
        loading = false
    }

    // Dernieres valeurs affichees sur les des (meme logique que
    // _last_classic_dice_values : on remonte l'historique pour chaque de).
    val lastSuccess = history.firstOrNull { it.success != null }?.success
    val lastFateRoll = history.firstOrNull { it.fateEmoji != null }
    val lastFate: FateFace? = lastFateRoll?.let { r ->
        r.fateEmoji?.let { e -> FateFace(e, r.fateLabel.orEmpty()) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = rememberBackgroundPainter(BackgroundSlot.CLASSIC_DICE),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Voile leger par-dessus la photo (pour garder les textes lisibles).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to ScrimTop,
                            0.3f to ScrimMid,
                            1.0f to ScrimBottom
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp)
        ) {
            // --- Barre du haut : fleche retour + titre -------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = goBack
                        )
                        .semantics { contentDescription = "Retour" },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "←",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 30.sp,
                            shadow = Shadow(Color(0xB3000000), Offset(0f, 3f), 8f)
                        )
                    )
                }
                Text(
                    text = "🎲 Des classiques",
                    style = TextStyle(
                        fontFamily = fonts.display,
                        fontSize = 24.sp,
                        letterSpacing = 1.sp,
                        color = Color.White,
                        shadow = Shadow(Color(0xB3000000), Offset(0f, 3f), 8f)
                    )
                )
            }
            Spacer(Modifier.height(14.dp))

            val sectionModifier = Modifier.align(Alignment.CenterHorizontally)

            // --- Section 1 : les des + boutons (sans fond : la photo reste visible)
            Section(sectionModifier) {
                DiceSection(
                    success = lastSuccess,
                    fate = lastFate,
                    fonts = fonts,
                    spin = spin,
                    progress = progress,
                    cubeFateFaces = fateFaces.take(6),
                    rolling = rolling,
                    onRollSuccess = { roll("success") },
                    onRollFate = { roll("fate") },
                    onRollBoth = { roll("both") }
                )
            }
            Spacer(Modifier.height(12.dp))

            // --- Section 2 : historique ----------------------------------
            Section(sectionModifier) {
                Text(
                    text = "Historique",
                    style = TextStyle(
                        fontFamily = fonts.bodyBold,
                        fontSize = 16.sp,
                        color = Color.White,
                        shadow = TextShadow
                    )
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    history.isEmpty() -> Text(
                        text = "(aucun lancer pour l'instant)",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            fontFamily = fonts.body,
                            fontSize = 14.4.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            shadow = TextShadow
                        )
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        history.forEachIndexed { index, roll ->
                            val parts = mutableListOf<String>()
                            if (roll.success != null) parts.add("Dé classique=${roll.success}")
                            if (roll.fateEmoji != null) parts.add("Destin=${roll.fateEmoji} ${roll.fateLabel.orEmpty()}")
                            Text(
                                text = "#${roll.id} — " + parts.joinToString(" | "),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.2.sp,
                                    color = Color.White,
                                    shadow = TextShadow
                                )
                            )
                            if (index < history.lastIndex) {
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
                Spacer(Modifier.height(10.dp))
                ComicButton(
                    text = "Effacer l'historique",
                    onClick = { showClearConfirm = true },
                    background = Color.White,
                    contentColor = DangerText,
                    fonts = fonts,
                    enabled = !rolling,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    style = TextStyle(
                        fontFamily = fonts.body,
                        fontSize = 14.sp,
                        color = Color(0xFFFFC9C9),
                        shadow = TextShadow
                    )
                )
            }
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
                    "Effacer tout l'historique des dés classiques ?",
                    style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink)
                )
            },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; clearHistory() }) {
                    Text("Effacer", style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = DangerText))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Annuler", style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = Ink))
                }
            }
        )
    }
}

// ----------------------------------------------------------------------
// Bruitage de lancer
// ----------------------------------------------------------------------

/**
 * Charge une fois par composition le bruitage de lancer de des, via
 * SoundPool (latence minimale, adapte a un son court et repete -- contrairement
 * a MediaPlayer, plus lent a demarrer). Le fichier attendu est
 * res/raw/dice_roll.<extension> (mp3, wav ou ogg) : A AJOUTER TOI-MEME au
 * projet (app/src/main/res/raw/, a creer si besoin) -- ce fichier ne peut
 * pas etre genere ici. L'animation de lancer dure SpinDurationMs = 1800 ms,
 * un son de cette longueur ou un peu moins colle le mieux au mouvement des
 * des ; un son plus court peut etre repete via le parametre loop de play()
 * ci-dessous si besoin (laisse a 0 = pas de repetition pour l'instant).
 */
@Composable
private fun rememberDiceRollSound(): () -> Unit {
    val context = LocalContext.current
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    var soundId by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        soundId = soundPool.load(context, R.raw.dice_roll, 1)
        onDispose { soundPool.release() }
    }
    return {
        if (soundId != 0) soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }
}

// ----------------------------------------------------------------------
// Briques visuelles "BD" : ombre decalee, sections, boutons, des a plat
// ----------------------------------------------------------------------

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

/** Bloc sans fond ni bordure (la photo reste visible), largeur limitee comme l'ancienne carte. */
@Composable
private fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun ComicButton(
    text: String,
    onClick: () -> Unit,
    background: Color,
    contentColor: Color,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.6f)
            .offset(x = if (pressed) 2.dp else 0.dp, y = if (pressed) 2.dp else 0.dp)
            .hardShadow(if (pressed) 1.dp else 3.dp, 10.dp, Ink)
            .background(background, shape)
            .border(3.dp, Ink, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 17.6.sp,
                letterSpacing = 1.sp,
                color = contentColor
            )
        )
    }
}

/**
 * Les deux des cote a cote s'il y a la place, sinon l'un sous l'autre.
 * Un de "en train de rouler" est remplace par le cube 3D (TumblingDie),
 * l'autre reste a plat avec sa derniere valeur.
 *
 * Plus de boutons texte sous les des : chaque de est desormais cliquable
 * directement (roll("success")/roll("fate")), et un bouton rond "⚡" pose
 * entre les deux declenche roll("both"), a la place de l'ancien gros
 * bouton "Lancer les deux dés" sous la section.
 */
@Composable
private fun DiceSection(
    success: Int?,
    fate: FateFace?,
    fonts: AppFonts,
    spin: SpinState?,
    progress: Animatable<Float, AnimationVector1D>,
    cubeFateFaces: List<FateFace>,
    rolling: Boolean,
    onRollSuccess: () -> Unit,
    onRollFate: () -> Unit,
    onRollBoth: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fullDieSize = 150.dp
        val centerButtonSize = 56.dp
        val gap = 12.dp

        // Cote a cote, le bouton rond central a besoin de sa propre place
        // en plus des deux des : si la largeur ne suffit pas a les
        // ecarter suffisamment a taille pleine, on retrecit legerement les
        // deux des (jamais en dessous de 100dp) plutot que de les faire
        // se chevaucher ou deborder.
        val rowDieSize = remember(maxWidth) {
            val required = fullDieSize * 2 + centerButtonSize + gap * 2
            if (maxWidth >= required) {
                fullDieSize
            } else {
                val available = (maxWidth - centerButtonSize - gap * 2).coerceAtLeast(0.dp)
                (available / 2).coerceIn(100.dp, fullDieSize)
            }
        }

        val successDie: @Composable (Dp) -> Unit = { dieSize ->
            DieWithCaption("Dé classique", fonts) {
                val spec = spin?.success
                if (spec != null) {
                    Box(Modifier.size(dieSize).graphicsLayer { rotationZ = -1f }) {
                        TumblingDie(spec, progress, null, fonts, Color.White)
                    }
                } else {
                    DieBox(
                        rotation = -1f,
                        background = Color.White,
                        size = dieSize,
                        onClick = onRollSuccess,
                        enabled = !rolling
                    ) { SuccessDieFace(success) }
                }
            }
        }
        val fateDie: @Composable (Dp) -> Unit = { dieSize ->
            DieWithCaption("Dé du destin", fonts) {
                val spec = spin?.fate
                if (spec != null) {
                    Box(Modifier.size(dieSize).graphicsLayer { rotationZ = 1f }) {
                        TumblingDie(spec, progress, cubeFateFaces, fonts, FateDieBackground)
                    }
                } else {
                    DieBox(
                        rotation = 1f,
                        background = FateDieBackground,
                        size = dieSize,
                        onClick = onRollFate,
                        enabled = !rolling
                    ) { FateDieFace(fate, fonts) }
                }
            }
        }
        if (maxWidth >= 316.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
            ) {
                successDie(rowDieSize)
                RollBothButton(size = centerButtonSize, enabled = !rolling, onClick = onRollBoth)
                fateDie(rowDieSize)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                successDie(fullDieSize)
                RollBothButton(size = centerButtonSize, enabled = !rolling, onClick = onRollBoth)
                fateDie(fullDieSize)
            }
        }
    }
}

/** Bouton rond "⚡" (lancer les deux dés a la fois), pose entre les deux des -- meme habillage "BD" (ombre/bordure) que ComicButton, en cercle. */
@Composable
private fun RollBothButton(size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.6f)
            .offset(x = if (pressed) 2.dp else 0.dp, y = if (pressed) 2.dp else 0.dp)
            .hardShadow(if (pressed) 1.dp else 3.dp, size / 2, Ink)
            .background(Red, CircleShape)
            .border(3.dp, Ink, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .semantics { contentDescription = "Lancer les deux dés" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⚡",
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = (size.value * 0.42f).sp, color = Color.White)
        )
    }
}

@Composable
private fun DieWithCaption(caption: String, fonts: AppFonts, die: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        die()
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 13.6.sp,
                color = Color.White,
                shadow = TextShadow
            )
        )
    }
}

@Composable
internal fun DieBox(
    rotation: Float,
    background: Color,
    size: Dp = 150.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .hardShadow(5.dp, 14.dp, Ink)
            .background(background, shape)
            .border(4.dp, Ink, shape)
            .let {
                if (onClick != null) {
                    it.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick
                    )
                } else it
            }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun SuccessDieFace(value: Int?) {
    val pips = value?.let { PIP_POSITIONS[it] }
    if (pips == null) {
        Text(
            text = "⚡",
            modifier = Modifier.alpha(0.55f),
            style = TextStyle(fontSize = 54.sp)
        )
    } else {
        Canvas(Modifier.fillMaxSize()) {
            val cellW = size.width / 3f
            val cellH = size.height / 3f
            val radius = 11.dp.toPx()
            pips.forEach { (row, col) ->
                drawCircle(
                    color = Ink,
                    radius = radius,
                    center = Offset((col - 0.5f) * cellW, (row - 0.5f) * cellH)
                )
            }
        }
    }
}

@Composable
internal fun FateDieFace(fate: FateFace?, fonts: AppFonts) {
    if (fate == null) {
        Text(text = "🔮", style = TextStyle(fontSize = 54.sp))
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = fate.emoji, style = TextStyle(fontSize = 58.sp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = fate.label,
                textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = fonts.display, fontSize = 16.sp, color = Purple)
            )
        }
    }
}

// ----------------------------------------------------------------------
// De 3D qui roule : un vrai cube dessine face par face dans un Canvas
// ----------------------------------------------------------------------

/** Ce qui est en train de rouler (null = ce de-la reste a plat). */
internal class SpinState(val success: SpinSpec?, val fate: SpinSpec?)

/**
 * Parametres aleatoires d'un lancer : deux axes de rotation avec leur
 * nombre de tours (le de "culbute" au lieu de tourner autour d'un seul
 * axe), le nombre de rebonds, et la face sur laquelle il doit s'arreter.
 */
internal class SpinSpec(
    val faceIndex: Int,
    val axis1: FloatArray,
    val axis2: FloatArray,
    val turns1: Float,
    val turns2: Float,
    val bounces: Int,
    val timeScale: Float // < 1 : ce de s'arrete un peu avant la fin de l'animation
)

private fun randomAxis(): FloatArray {
    val x = Random.nextFloat() * 2f - 1f
    val y = Random.nextFloat() * 2f - 1f
    val z = Random.nextFloat() * 2f - 1f
    val len = sqrt(x * x + y * y + z * z)
    return if (len < 0.25f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(x / len, y / len, z / len)
}

internal fun newSpinSpec(faceIndex: Int, bounces: Int, timeScale: Float): SpinSpec {
    val sign1 = if (Random.nextBoolean()) 1f else -1f
    val sign2 = if (Random.nextBoolean()) 1f else -1f
    return SpinSpec(
        faceIndex = faceIndex,
        axis1 = randomAxis(),
        axis2 = randomAxis(),
        turns1 = sign1 * (2f + Random.nextFloat()),
        turns2 = sign2 * (1f + Random.nextFloat()),
        bounces = bounces,
        timeScale = timeScale
    )
}

/** Repere d'une face du cube : normale sortante n, "droite" r et "haut" u (r x u = n). */
private class FaceFrame(val n: FloatArray, val r: FloatArray, val u: FloatArray)

// Axes : X vers la droite, Y vers le haut, Z vers le joueur.
// Index i = face portant la valeur i+1 (les faces opposees font 7).
private val FACE_FRAMES = arrayOf(
    FaceFrame(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),    // 1 : devant
    FaceFrame(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),   // 2 : dessus
    FaceFrame(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)),   // 3 : droite
    FaceFrame(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f)),   // 4 : gauche
    FaceFrame(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),   // 5 : dessous
    FaceFrame(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))   // 6 : derriere
)

// Direction de la lumiere (normalisee) : un peu depuis le haut gauche, surtout de face.
private val LIGHT = floatArrayOf(-0.2506f, 0.3509f, 0.9023f)

// Matrices 3x3 stockees ligne par ligne (9 floats).
private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
    val o = FloatArray(9)
    for (i in 0..2) for (j in 0..2) {
        var s = 0f
        for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
        o[i * 3 + j] = s
    }
    return o
}

private fun mulVec(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
    m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
)

/** Rotation d'un angle (radians) autour d'un axe unitaire (formule de Rodrigues). */
private fun rotationMatrix(axis: FloatArray, angle: Float): FloatArray {
    val ax = axis[0]; val ay = axis[1]; val az = axis[2]
    val c = cos(angle); val s = sin(angle); val t = 1f - c
    return floatArrayOf(
        t * ax * ax + c,      t * ax * ay - s * az, t * ax * az + s * ay,
        t * ax * ay + s * az, t * ay * ay + c,      t * ay * az - s * ax,
        t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c
    )
}

/** Orientation qui amene la face donnee bien droite, face au joueur. */
private fun finalRotation(frame: FaceFrame): FloatArray = floatArrayOf(
    frame.r[0], frame.r[1], frame.r[2],
    frame.u[0], frame.u[1], frame.u[2],
    frame.n[0], frame.n[1], frame.n[2]
)

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * De qui roule : `cubeFateFaces == null` -> de classique (points),
 * sinon de du destin (emoji + libelle sur chacune des 6 faces).
 * `progress` est lu uniquement au moment du dessin : l'animation ne
 * provoque aucune recomposition.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
internal fun TumblingDie(
    spec: SpinSpec,
    progress: Animatable<Float, AnimationVector1D>,
    cubeFateFaces: List<FateFace>?,
    fonts: AppFonts,
    faceColor: Color,
    // De de reussite seulement : symboles a dessiner sur chaque face, par face (index 0..5)
    // puis par point. null = points noirs (page "Des classiques").
    pipGlyphs: List<List<PipGlyph>>? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val emojiLayouts = remember(cubeFateFaces) {
        cubeFateFaces?.map { textMeasurer.measure(it.emoji, TextStyle(fontSize = 58.sp, color = Ink)) }
    }
    val labelLayouts = remember(cubeFateFaces, fonts) {
        cubeFateFaces?.map {
            textMeasurer.measure(
                it.label,
                TextStyle(fontFamily = fonts.display, fontSize = 16.sp, color = Purple, textAlign = TextAlign.Center)
            )
        }
    }
    val finalR = remember(spec) { finalRotation(FACE_FRAMES[spec.faceIndex]) }

    Canvas(Modifier.fillMaxSize()) {
        val p = (progress.value / spec.timeScale).coerceIn(0f, 1f)
        drawTumblingCube(spec, finalR, p, faceColor, emojiLayouts, labelLayouts, pipGlyphs)
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawTumblingCube(
    spec: SpinSpec,
    finalR: FloatArray,
    p: Float,
    faceColor: Color,
    emojiLayouts: List<TextLayoutResult>?,
    labelLayouts: List<TextLayoutResult>?,
    pipGlyphs: List<List<PipGlyph>>? = null
) {
    val base = size.width // arete du de au repos = 150 dp
    val q = 1f - p
    val twoPi = (2.0 * PI).toFloat()

    // Orientation : on part d'une position aleatoire et on "deroule" deux
    // rotations vers l'orientation finale (ralentissement progressif).
    val decay = q.pow(2.2f)
    val rot = matMul(
        matMul(finalR, rotationMatrix(spec.axis1, spec.turns1 * twoPi * decay)),
        rotationMatrix(spec.axis2, spec.turns2 * twoPi * decay)
    )

    // Rebonds decroissants (0 = au sol) ; le de grossit un peu quand il est en l'air.
    val hop = abs(sin(spec.bounces * PI.toFloat() * p)) * q.pow(1.2f)
    val settle = smoothStep((p - 0.55f) / 0.45f)
    val sizeK = (0.70f + 0.30f * settle) * (1f + 0.10f * hop)
    val s = base * sizeK
    val h = s / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f - hop * 26.dp.toPx()

    // Ombre portee au sol : s'estompe et se resserre quand le de est en l'air.
    val shadowAlpha = (0.30f * (1f - 0.5f * hop) * (1f - p * p * p)).coerceIn(0f, 1f)
    if (shadowAlpha > 0.01f) {
        val sw = s * 0.95f * (1f - 0.3f * hop)
        val sh = 16.dp.toPx() * (1f - 0.3f * hop)
        drawOval(
            color = Color.Black.copy(alpha = shadowAlpha),
            topLeft = Offset(cx - sw / 2f, size.height / 2f + s * 0.45f - sh / 2f),
            size = Size(sw, sh)
        )
    }

    // A l'arret, l'ombre "BD" du de a plat reapparait pour que la transition soit invisible.
    val hardAlpha = smoothStep((p - 0.8f) / 0.2f)
    if (hardAlpha > 0.01f) {
        drawRoundRect(
            color = Ink.copy(alpha = hardAlpha),
            topLeft = Offset(cx - h + 5.dp.toPx(), cy - h + 5.dp.toPx()),
            size = Size(s, s),
            cornerRadius = CornerRadius(14.dp.toPx() * sizeK)
        )
    }

    val nc = drawContext.canvas.nativeCanvas
    val corner = 14.dp.toPx()
    val border = 4.dp.toPx()

    for (i in 0 until 6) {
        val f = FACE_FRAMES[i]
        val n = mulVec(rot, f.n)
        if (n[2] <= 0.02f) continue // face tournee vers l'arriere : cachee
        val r = mulVec(rot, f.r)
        val u = mulVec(rot, f.u)

        // Centre de la face et trois de ses coins, projetes a l'ecran
        // (projection orthographique, y de l'ecran vers le bas).
        val fcx = cx + n[0] * h
        val fcy = cy - n[1] * h
        val tlx = fcx - r[0] * h + u[0] * h
        val tly = fcy + r[1] * h - u[1] * h
        val trx = fcx + r[0] * h + u[0] * h
        val try2 = fcy - r[1] * h - u[1] * h
        val blx = fcx - r[0] * h - u[0] * h
        val bly = fcy + r[1] * h + u[1] * h

        // Transformation qui envoie le repere local de la face (0..base, y vers le bas)
        // sur le parallelogramme projete : tout ce qui est dessine ensuite
        // (bordure, points, emoji, texte) suit la perspective de la face.
        val m = android.graphics.Matrix()
        m.setValues(
            floatArrayOf(
                (trx - tlx) / base, (blx - tlx) / base, tlx,
                (try2 - tly) / base, (bly - tly) / base, tly,
                0f, 0f, 1f
            )
        )
        nc.save()
        nc.concat(m)

        // Bordure + face, comme le de a plat.
        drawRoundRect(color = Ink, size = Size(base, base), cornerRadius = CornerRadius(corner))
        drawRoundRect(
            color = faceColor,
            topLeft = Offset(border, border),
            size = Size(base - 2 * border, base - 2 * border),
            cornerRadius = CornerRadius(corner - border)
        )

        if (emojiLayouts == null || labelLayouts == null) {
            // De classique : points sur une grille 3x3.
            val pad = border + 10.dp.toPx()
            val cell = (base - 2 * pad) / 3f
            val radius = 11.dp.toPx()
            val glyphs = pipGlyphs?.getOrNull(i)
            if (glyphs != null && glyphs.isNotEmpty()) {
                // Symboles des totems a la place des points noirs.
                val box = pipGlyphSizeDp(i + 1).dp.toPx()
                PIP_POSITIONS[i + 1]?.forEachIndexed { index, (row, col) ->
                    val glyph = glyphs.getOrNull(index) ?: glyphs.first()
                    drawPipGlyph(
                        glyph,
                        Offset(pad + (col - 0.5f) * cell, pad + (row - 0.5f) * cell),
                        box
                    )
                }
            } else {
                PIP_POSITIONS[i + 1]?.forEach { (row, col) ->
                    drawCircle(
                        color = Ink,
                        radius = radius,
                        center = Offset(pad + (col - 0.5f) * cell, pad + (row - 0.5f) * cell)
                    )
                }
            }
        } else if (i < emojiLayouts.size) {
            // De du destin : emoji + libelle centres sur la face.
            val e = emojiLayouts[i]
            val l = labelLayouts[i]
            val gap = 4.dp.toPx()
            val top = (base - (e.size.height + gap + l.size.height)) / 2f
            drawText(e, topLeft = Offset((base - e.size.width) / 2f, top))
            drawText(l, topLeft = Offset((base - l.size.width) / 2f, top + e.size.height + gap))
        }

        // Ombrage : les faces qui s'eloignent de la lumiere s'assombrissent.
        val light = n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]
        val shade = 0.38f * (1f - (light / 0.9f).coerceIn(0f, 1f))
        if (shade > 0.01f) {
            drawRoundRect(
                color = Color.Black.copy(alpha = shade),
                size = Size(base, base),
                cornerRadius = CornerRadius(corner)
            )
        }

        nc.restore()
    }
}

/** Dessine un symbole (image ajustee dans un carre `box`, ou emoji) centre en `center`. */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawPipGlyph(glyph: PipGlyph, center: Offset, box: Float) {
    val image = glyph.image
    val emoji = glyph.emoji
    if (image != null) {
        val k = minOf(box / image.width, box / image.height)
        val w = image.width * k
        val h = image.height * k
        drawImage(
            image = image,
            dstOffset = IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt())
        )
    } else if (emoji != null) {
        drawText(
            emoji,
            topLeft = Offset(center.x - emoji.size.width / 2f, center.y - emoji.size.height / 2f)
        )
    }
}
