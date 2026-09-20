package com.aventure.desdice.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

// --- Palette de l'ancienne version web (dice_web.py) -------------------
private val Ink = Color(0xFF14161A)
private val Paper = Color(0xFFFBF3E1)
private val Red = Color(0xFFE0263C)
private val Purple = Color(0xFF6A4C93)
private val DangerText = Color(0xFF8A1020)
private val FateDieBackground = Color(0xFFFFF7E0)
private val LineColor = Color(0x2614161A)

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

private data class FateFace(val emoji: String, val label: String)

/**
 * Page "Des classiques" : le de de reussite (1-6) et le de du destin,
 * independants de toute histoire, pense comme aide-memoire pendant une
 * partie sur table. Habillage repris de render_classic_dice_page
 * (dice_web.py) : fond photo + degrade sombre, cartes creme a bordure
 * noire et ombre decalee, polices Bangers / Nunito.
 *
 * S'appuie sur classic_dice_state / do_classic_dice_roll /
 * do_classic_dice_clear / get_fate_faces de game_api.py.
 *
 * Necessite res/drawable/bg_classic_dice.jpg.
 */
@Composable
fun ClassicDiceScreen(modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val fonts = rememberAppFonts()
    val python = remember { Python.getInstance() }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var rolling by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<ClassicRoll>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var fateFaces by remember { mutableStateOf<List<FateFace>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Animation de lancer : faces aleatoires qui defilent avant le vrai resultat.
    var animSuccessOn by remember { mutableStateOf(false) }
    var animFateOn by remember { mutableStateOf(false) }
    var animSuccess by remember { mutableStateOf<Int?>(null) }
    var animFate by remember { mutableStateOf<FateFace?>(null) }

    fun applyResult(result: String) {
        val parsed = JSONObject(result)
        val historyArray = parsed.optJSONArray("history")
        val list = mutableListOf<ClassicRoll>()
        if (historyArray != null) {
            for (i in 0 until historyArray.length()) {
                val entry = historyArray.getJSONObject(i)
                list.add(
                    ClassicRoll(
                        id = entry.optInt("id"),
                        success = if (entry.isNull("success")) null else entry.optInt("success"),
                        fateEmoji = entry.optString("fate_emoji", "").ifEmpty { null },
                        fateLabel = entry.optString("fate_label", "").ifEmpty { null }
                    )
                )
            }
        }
        history = list.reversed() // plus recent en premier
    }

    suspend fun callPython(funcName: String, vararg args: Any): String =
        withContext(Dispatchers.IO) {
            python.getModule("game_api")
                .callAttr("call_json", funcName, *args)
                .toString()
        }

    fun roll(kind: String) {
        if (rolling) return
        scope.launch {
            rolling = true
            error = null
            val rollSuccess = kind != "fate"
            val rollFate = kind != "success"
            animSuccessOn = rollSuccess
            animFateOn = rollFate && fateFaces.isNotEmpty()
            for (i in 0 until 10) {
                if (rollSuccess) animSuccess = Random.nextInt(1, 7)
                if (animFateOn) animFate = fateFaces.random()
                delay(50L + i * 15L)
            }
            try {
                applyResult(callPython("do_classic_dice_roll", kind))
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                animSuccessOn = false
                animFateOn = false
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
                applyResult(callPython("do_classic_dice_clear"))
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                rolling = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            applyResult(callPython("classic_dice_state"))
        } catch (e: Exception) {
            error = "Erreur : ${e.message}"
        }
        // Faces du destin, uniquement pour l'animation (facultatif).
        try {
            val arr = JSONArray(callPython("get_fate_faces"))
            val faces = mutableListOf<FateFace>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val emoji = o.optString("emoji", "")
                if (emoji.isNotEmpty()) faces.add(FateFace(emoji, o.optString("label", "")))
            }
            fateFaces = faces
        } catch (e: Exception) {
            // Pas d'animation du destin, sans consequence.
        }
        loading = false
    }

    // Dernieres valeurs affichees sur les des (meme logique que
    // _last_classic_dice_values : on remonte l'historique pour chaque de).
    val lastSuccess = history.firstOrNull { it.success != null }?.success
    val lastFateRoll = history.firstOrNull { it.fateEmoji != null }
    val lastFate: FateFace? = lastFateRoll?.let { r ->
        r.fateEmoji?.let { e -> FateFace(e, r.fateLabel.orEmpty()) }
    }
    val shownSuccess = if (animSuccessOn) animSuccess else lastSuccess
    val shownFate = if (animFateOn) animFate else lastFate

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(R.drawable.bg_classic_dice),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Le "scrim" : degrade sombre par-dessus la photo.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x8C000000),
                            0.3f to Color(0x59000000),
                            1.0f to Color(0xD90A0806)
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
                        .size(40.dp)
                        .background(Color(0x8014161A), CircleShape)
                        .clickable(onClick = onBack)
                        .semantics { contentDescription = "Retour" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "←",
                        style = TextStyle(color = Color.White, fontSize = 22.sp)
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

            val cardModifier = Modifier.align(Alignment.CenterHorizontally)

            // --- Carte 1 : les des + boutons -----------------------------
            ComicCard(cardModifier) {
                DiceSection(success = shownSuccess, fate = shownFate, fonts = fonts)
                Spacer(Modifier.height(10.dp))
                ComicButton(
                    text = "⚡ Lancer les deux dés",
                    onClick = { roll("both") },
                    background = Red,
                    contentColor = Color.White,
                    fonts = fonts,
                    enabled = !rolling,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ComicButton(
                        text = "Dé classique",
                        onClick = { roll("success") },
                        background = Color.White,
                        contentColor = Ink,
                        fonts = fonts,
                        enabled = !rolling,
                        modifier = Modifier.weight(1f)
                    )
                    ComicButton(
                        text = "Dé du destin",
                        onClick = { roll("fate") },
                        background = Color.White,
                        contentColor = Ink,
                        fonts = fonts,
                        enabled = !rolling,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // --- Carte 2 : historique ------------------------------------
            ComicCard(cardModifier) {
                Text(
                    text = "Historique",
                    style = TextStyle(
                        fontFamily = fonts.body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = Ink
                    )
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> CircularProgressIndicator(
                        color = Ink,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    history.isEmpty() -> Text(
                        text = "(aucun lancer pour l'instant)",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().alpha(0.7f),
                        style = TextStyle(fontFamily = fonts.body, fontSize = 14.4.sp, color = Ink)
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
                                    color = Ink
                                )
                            )
                            if (index < history.lastIndex) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(LineColor)
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
                        color = Color(0xFFFFC9C9)
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
// Briques visuelles "BD" : ombre decalee, cartes, boutons, des
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

@Composable
private fun ComicCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .hardShadow(5.dp, 14.dp, Color(0x66000000))
            .background(Paper, shape)
            .border(3.dp, Ink, shape)
            .padding(20.dp),
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

/** Les deux des cote a cote s'il y a la place, sinon l'un sous l'autre (comme le flex-wrap de la page web). */
@Composable
private fun DiceSection(success: Int?, fate: FateFace?, fonts: AppFonts) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val successDie: @Composable () -> Unit = {
            DieWithCaption("Dé classique", fonts) {
                DieBox(rotation = -1f, background = Color.White) { SuccessDieFace(success) }
            }
        }
        val fateDie: @Composable () -> Unit = {
            DieWithCaption("Dé du destin", fonts) {
                DieBox(rotation = 1f, background = FateDieBackground) { FateDieFace(fate, fonts) }
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
                color = Ink.copy(alpha = 0.85f)
            )
        )
    }
}

@Composable
private fun DieBox(rotation: Float, background: Color, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(150.dp)
            .graphicsLayer { rotationZ = rotation }
            .hardShadow(5.dp, 14.dp, Ink)
            .background(background, shape)
            .border(4.dp, Ink, shape)
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
private fun FateDieFace(fate: FateFace?, fonts: AppFonts) {
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
