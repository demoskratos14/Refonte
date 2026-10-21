package com.aventure.desdice.screens

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.R
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.ClassicDicePrefs
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.ui.rememberAppTextStyles
import com.aventure.desdice.ui.bodySp
import com.aventure.desdice.ui.titleSp
import com.aventure.desdice.ui.SoundPrefs
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random


// --- Palette "papier BD" -------------------
internal val ClassicInk = Color(0xFF14161A)
internal val ClassicPaper = Color(0xFFFBF3E1)
internal val ClassicRed = Color(0xFFE0263C)
internal val ClassicPurple = Color(0xFF6A4C93)
internal val ClassicDangerText = Color(0xFF8A1020)
internal val ClassicFateDieBackground = Color(0xFFFFF7E0)

// --- Reglages de lisibilite / d'animation (a ajuster si besoin) --------
// Voile sombre par-dessus la photo (haut -> 30 % -> bas). Plus les valeurs
// alpha (les 2 premiers chiffres apres 0x) sont petites, plus la photo se voit.
private val ScrimTop = Color(0x33000000)
private val ScrimMid = Color(0x14000000)
private val ScrimBottom = Color(0x59000000)
private val DividerColor = Color(0x4DFFFFFF)
// Ombre portee des textes poses directement sur la photo.
internal val ClassicTextShadow = Shadow(Color(0xCC000000), Offset(1.5f, 2f), 6f)

/** Un dé tiré : `faces` = nombre de faces (0 pour le dé du destin), `value` = nombre obtenu, `fate` = face du destin. */
internal data class DieResult(val faces: Int, val value: Int?, val fate: FateFace?)

/** Un lancer de l'historique : les résultats de tous les dés lancés ensemble, dans l'ordre du plateau. */
private data class ClassicRoll(val id: Int, val results: List<DieResult>)

/** Dés en train de rouler : par index de plateau, leurs paramètres d'animation (et nombres des faces pour un dé non classique). */
internal class ClassicSpin(val specs: Map<Int, SpinSpec>, val numbers: Map<Int, List<Int>>)

internal data class FateFace(val emoji: String, val label: String)

private fun parseHistory(result: JSONObject): List<ClassicRoll> {
    val arr = result.optJSONArray("history") ?: return emptyList()
    val list = mutableListOf<ClassicRoll>()
    for (i in 0 until arr.length()) {
        val entry = arr.getJSONObject(i)
        val rs = entry.optJSONArray("results") ?: JSONArray()
        val results = mutableListOf<DieResult>()
        for (j in 0 until rs.length()) {
            val r = rs.getJSONObject(j)
            if (r.optString("type") == "fate") {
                results.add(DieResult(0, null, FateFace(r.optString("fate_emoji", ""), r.optString("fate_label", ""))))
            } else {
                results.add(DieResult(r.optInt("faces", 6), r.optInt("value"), null))
            }
        }
        list.add(ClassicRoll(entry.optInt("id"), results))
    }
    return list // du plus ancien au plus recent
}

/**
 * Page "Des classiques" : un plateau de des a composer soi-meme (de 0 a 8 des). On ajoute
 * exactement les des voulus -- d4, classique (d6 a points), d8, d10, d12, d20, un nombre de faces
 * libre, ou le de du destin (facultatif, comme les autres) -- et on les lance tous d'un coup ou
 * un par un (tap sur le de). Independant de toute histoire, pense comme aide-memoire pendant une
 * partie sur table. Habillage : fond photo,
 * boutons "BD", polices Bangers / Nunito. Les des sont de vrais cubes 3D qui roulent,
 * rebondissent et s'immobilisent sur la face tiree (le resultat est deja tire avant l'animation).
 *
 * S'appuie sur GameViewModel.classicDiceStateAwait / classicDiceRollManyAwait /
 * classicDiceClearAwait / fateFaces, et sur ClassicDicePrefs.dice pour le plateau.
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
    val context = LocalContext.current
    val fontPrefs = remember(context) { FontPrefs.get(context) }
    val textStyles = rememberAppTextStyles(fonts, fontPrefs)
    val dicePrefs = remember(context) { ClassicDicePrefs.get(context) }
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
    // Faces du destin : exposees directement par GameViewModel (FATE_FACES cote Kotlin,
    // DiceSession.kt). On les reduit au type local FateFace (emoji + libelle), seul necessaire
    // au dessin du cube.
    val fateFaces = remember(viewModel.fateFaces) {
        viewModel.fateFaces.map { FateFace(it.emoji, it.label) }
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Animation de lancer : progression 0 -> 1 partagee par tous les des qui roulent.
    var spin by remember { mutableStateOf<ClassicSpin?>(null) }
    val progress = remember { Animatable(0f) }

    // Dernier resultat affiche a plat sur chaque de du plateau (meme index que dicePrefs.dice).
    val lastResults = remember {
        mutableStateListOf<DieResult?>().apply { repeat(dicePrefs.dice.size) { add(null) } }
    }

    fun applyResult(result: JSONObject) {
        history = parseHistory(result).reversed() // plus recent en premier
    }

    fun addDie(d: Int) {
        if (rolling) return
        if (dicePrefs.addDie(d)) lastResults.add(null)
    }

    fun removeDie(index: Int) {
        if (rolling) return
        dicePrefs.removeDieAt(index)
        if (index in lastResults.indices) lastResults.removeAt(index)
    }

    // Lance les des du plateau dont l'index est dans `indices` (tous, ou un seul si on tape dessus).
    fun roll(indices: List<Int>) {
        if (rolling) return
        val picked = indices.filter { it in dicePrefs.dice.indices }
        if (picked.isEmpty()) return
        val toRoll = picked.map { dicePrefs.dice[it] }
        scope.launch {
            rolling = true
            error = null
            try {
                // Le lancer est tire et enregistre tout de suite (cote GameEngine) ; l'animation
                // ne fait que "raconter" ce resultat, et l'historique n'est mis a jour (et publie
                // dans le StateFlow du ViewModel) qu'a la fin, pour ne pas spoiler le suspense.
                val result = viewModel.classicDiceRollManyAwait(toRoll)
                val rolled = parseHistory(result).lastOrNull()?.results.orEmpty() // meme ordre que toRoll

                val specs = mutableMapOf<Int, SpinSpec>()
                val numbers = mutableMapOf<Int, List<Int>>()
                val cubeFaces = fateFaces.take(6)
                picked.forEachIndexed { k, slot ->
                    val r = rolled.getOrNull(k) ?: return@forEachIndexed
                    val fate = r.fate
                    val value = r.value
                    if (fate != null) {
                        val idx = if (cubeFaces.size == 6) {
                            cubeFaces.indexOfFirst { it.emoji == fate.emoji && it.label == fate.label }
                        } else -1
                        if (idx >= 0) specs[slot] = newSpinSpec(faceIndex = idx, bounces = 2, timeScale = 0.88f)
                    } else if (value != null) {
                        if (r.faces == 6 && value in 1..6) {
                            specs[slot] = newSpinSpec(faceIndex = value - 1, bounces = 3, timeScale = 1f)
                        } else {
                            // Autre de qu'un d6 : pas de points, le cube porte des nombres. La face
                            // qui finit vers nous affiche le vrai resultat, les cinq autres des
                            // nombres au hasard dans la meme plage (illusion pendant qu'il roule).
                            val finalFace = Random.nextInt(6)
                            numbers[slot] = List(6) { i ->
                                if (i == finalFace) value else Random.nextInt(1, r.faces + 1)
                            }
                            specs[slot] = newSpinSpec(faceIndex = finalFace, bounces = 3, timeScale = 1f)
                        }
                    }
                }

                if (specs.isNotEmpty()) {
                    spin = ClassicSpin(specs, numbers)
                    playDiceRollSound()
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(durationMillis = SpinDurationMs, easing = LinearEasing))
                }
                spin = null
                picked.forEachIndexed { k, slot ->
                    val r = rolled.getOrNull(k)
                    if (r != null && slot in lastResults.indices) lastResults[slot] = r
                }
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
        loading = false
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
                        fontSize = textStyles.titleSp(24f),
                        letterSpacing = 1.sp,
                        color = textStyles.titleColor ?: Color.White,
                        shadow = Shadow(Color(0xB3000000), Offset(0f, 3f), 8f)
                    )
                )
            }
            Spacer(Modifier.height(14.dp))

            val sectionModifier = Modifier.align(Alignment.CenterHorizontally)

            // --- Section 1 : le plateau de des + bouton "tout lancer" ----
            Section(sectionModifier) {
                DiceTray(
                    dice = dicePrefs.dice,
                    lastResults = lastResults,
                    fateFaces = fateFaces.take(6),
                    spin = spin,
                    progress = progress,
                    rolling = rolling,
                    fonts = fonts,
                    textStyles = textStyles,
                    onRollOne = { roll(listOf(it)) },
                    onRemove = { removeDie(it) }
                )
                if (dicePrefs.dice.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    val n = dicePrefs.dice.size
                    ClassicComicButton(
                        text = if (n == 1) "⚡ Lancer le dé" else "⚡ Lancer les $n dés",
                        onClick = { roll(dicePrefs.dice.indices.toList()) },
                        background = ClassicRed,
                        contentColor = Color.White,
                        fonts = fonts,
                        textStyles = textStyles,
                        enabled = !rolling,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // --- Section 2 : ajouter un de au plateau ---------------------
            Section(sectionModifier) {
                AddDieSection(
                    count = dicePrefs.dice.size,
                    enabled = !rolling,
                    fonts = fonts,
                    textStyles = textStyles,
                    onAdd = { addDie(it) }
                )
            }
            Spacer(Modifier.height(12.dp))

            // --- Section 3 : historique ----------------------------------
            Section(sectionModifier) {
                Text(
                    text = "Historique",
                    style = TextStyle(
                        fontFamily = fonts.bodyBold,
                        fontSize = textStyles.bodySp(16f),
                        color = textStyles.bodyColor ?: Color.White,
                        shadow = ClassicTextShadow
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
                            fontSize = textStyles.bodySp(14.4f),
                            color = textStyles.bodyColor ?: Color.White.copy(alpha = 0.9f),
                            shadow = ClassicTextShadow
                        )
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        history.forEachIndexed { index, roll ->
                            val parts = roll.results.map { r ->
                                val fate = r.fate
                                when {
                                    fate != null -> "Destin=${fate.emoji} ${fate.label}"
                                    r.faces == 6 -> "Dé classique=${r.value}"
                                    else -> "d${r.faces}=${r.value}"
                                }
                            }
                            Text(
                                text = "#${roll.id} — " + parts.joinToString(" | "),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.2.sp,
                                    color = Color.White,
                                    shadow = ClassicTextShadow
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
                ClassicComicButton(
                    text = "Effacer l'historique",
                    onClick = { showClearConfirm = true },
                    background = Color.White,
                    contentColor = ClassicDangerText,
                    fonts = fonts,
                    textStyles = textStyles,
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
                        fontSize = textStyles.bodySp(14f),
                        color = Color(0xFFFFC9C9),
                        shadow = ClassicTextShadow
                    )
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = ClassicPaper,
            title = {
                Text(
                    "Effacer l'historique",
                    style = TextStyle(fontFamily = fonts.display, fontSize = textStyles.titleSp(22f), color = ClassicInk)
                )
            },
            text = {
                Text(
                    "Effacer tout l'historique des dés classiques ?",
                    style = TextStyle(fontFamily = fonts.body, fontSize = textStyles.bodySp(16f), color = ClassicInk)
                )
            },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; clearHistory() }) {
                    Text("Effacer", style = TextStyle(fontFamily = fonts.display, fontSize = textStyles.titleSp(18f), color = ClassicDangerText))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Annuler", style = TextStyle(fontFamily = fonts.display, fontSize = textStyles.titleSp(18f), color = ClassicInk))
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
    var soundId by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        soundId = soundPool.load(context, R.raw.dice_roll, 1)
        onDispose { soundPool.release() }
    }
    return {
        // Volume lu à chaque lancer : Réglages > Sons (son coupé ou baissé) s'applique tout de suite.
        val volume = SoundPrefs.get(context).effectiveVolume
        if (soundId != 0 && volume > 0f) soundPool.play(soundId, volume, volume, 1, 0, 1f)
    }
}
