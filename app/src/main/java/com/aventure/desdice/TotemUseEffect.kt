package com.aventure.desdice

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.SoundPrefs
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effet « un totem est utilisé » : l'image du totem (ou son emoji) apparaît petite au centre de
 * l'écran, grossit avec un léger rebond dans un halo doré à rayons, puis s'efface. Déclenché à
 * la fin de l'utilisation d'un totem (voir TotemGaugesRow) ; se règle dans Réglages > Sons.
 */
object TotemEffect {

    /** Compteur observé par [TotemUseOverlay] : chaque incrément lance une animation. */
    var burst by mutableIntStateOf(0)
        private set

    /** Totem à montrer (objet de all_symbols : key, label, emoji, image), lu au lancement. */
    var symbol: JSONObject? = null
        private set

    /** [ignoreSettings] sert au bouton « Essayer » des Réglages. */
    fun fire(context: Context, symbol: JSONObject?, ignoreSettings: Boolean = false) {
        if (!ignoreSettings && !SoundPrefs.get(context).totemAnimationEnabled) return
        if (!ThreatExplosion.animationsAllowed(context)) return
        this.symbol = symbol
        burst++
    }
}

private const val TOTEM_MS = 2300
private const val TOTEM_CENTER_Y = 0.42f
private val GOLD = Color(0xFFFFD54F)

private class Glint(val angle: Float, val radius: Float, val size: Float, val phase: Float)

private val GLINTS: List<Glint> = Random(7).let { rnd ->
    List(22) {
        Glint(
            angle = rnd.nextFloat() * 6.2831855f,
            radius = 0.25f + rnd.nextFloat() * 0.75f,
            size = 2.5f + rnd.nextFloat() * 4f,
            phase = rnd.nextFloat()
        )
    }
}

/** Montée avec dépassement puis retour : l'image « jaillit » avant de se stabiliser. */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t.coerceIn(0f, 1f) - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * À poser en DERNIER enfant de l'écran (au-dessus du contenu). Ne capte aucun toucher, reste muet
 * pour TalkBack, et n'est dessiné que pendant l'animation.
 */
@Composable
fun TotemUseOverlay(modifier: Modifier = Modifier) {
    val burst = TotemEffect.burst
    // `burst` est global : sans cela, chaque écran qui s'affiche rejouerait le dernier effet.
    val startBurst = remember { burst }
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<JSONObject?>(null) }
    var active by remember { mutableStateOf(false) }

    LaunchedEffect(burst) {
        if (burst == startBurst) return@LaunchedEffect
        shown = TotemEffect.symbol
        active = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(TOTEM_MS, easing = LinearEasing))
        active = false
    }
    if (!active) return

    val p = progress.value
    val symbol = shown
    // 0 -> 0.28 : apparition (petit -> grand, avec rebond) ; 0.28 -> 0.78 : tenue avec légère
    // pulsation ; 0.78 -> 1 : l'image grossit encore un peu en s'effaçant.
    val grow = 0.12f + 0.88f * easeOutBack(p / 0.28f)
    val pulse = if (p in 0.28f..0.78f) 1f + 0.03f * sin((p - 0.28f) * 40f) else 1f
    val exit = ((p - 0.78f) / 0.22f).coerceIn(0f, 1f)
    val scale = grow * pulse * (1f + 0.35f * exit)
    val alpha = (p / 0.1f).coerceIn(0f, 1f) * (1f - exit)

    Box(modifier = modifier.fillMaxSize().clearAndSetSemantics { }) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawTotemAura(p, alpha) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(BiasAlignment(0f, TOTEM_CENTER_Y * 2f - 1f))
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                SymbolIcon(symbol, 200.dp, 140.sp)
            }
            val label = symbol?.str("label").orEmpty()
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = TextStyle(
                        color = Color(0xFFFFE082),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(Color.Black, Offset(2f, 2f), 8f)
                    )
                )
            }
        }
    }
}

private fun DrawScope.drawTotemAura(p: Float, alpha: Float) {
    if (alpha <= 0f) return
    val center = Offset(size.width / 2f, size.height * TOTEM_CENTER_Y)
    val base = 150.dp.toPx()
    val grow = 0.4f + 0.6f * easeOutBack(p / 0.3f).coerceAtLeast(0f)

    // Voile léger pour faire ressortir le totem sur un fond chargé.
    drawRect(Color.Black.copy(alpha = 0.35f * alpha))

    // Halo doré.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                GOLD.copy(alpha = 0.65f * alpha),
                Color(0xFFFF9800).copy(alpha = 0.30f * alpha),
                Color.Transparent
            ),
            center = center,
            radius = base * 2.1f * grow
        ),
        radius = base * 2.1f * grow,
        center = center
    )

    // Rayons qui tournent lentement derrière le totem.
    val rotation = p * 1.2f
    val rayCount = 14
    for (i in 0 until rayCount) {
        val a = rotation + i * (6.2831855f / rayCount)
        val inner = base * 0.75f * grow
        val outer = base * (1.55f + if (i % 2 == 0) 0.35f else 0f) * grow
        drawLine(
            color = GOLD.copy(alpha = 0.55f * alpha),
            start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
            end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // Petites étincelles dorées qui scintillent et montent autour.
    for (g in GLINTS) {
        val t = ((p * 1.6f + g.phase) % 1f)
        val r = base * (0.8f + g.radius * 1.4f) * grow
        val x = center.x + cos(g.angle) * r
        val y = center.y + sin(g.angle) * r - t * 40.dp.toPx()
        drawCircle(
            color = Color(0xFFFFF3B0).copy(alpha = (1f - t) * alpha),
            radius = g.size * (1f - 0.5f * t) * density,
            center = Offset(x, y)
        )
    }
}
