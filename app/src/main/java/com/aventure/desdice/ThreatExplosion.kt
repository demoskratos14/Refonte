package com.aventure.desdice

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.SoundPrefs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effet « la jauge de menace explose » : vibration + animation d'explosion plein écran, déclenchés
 * ensemble quand la réponse de l'IA qui suit l'explosion s'affiche (voir AiPanel). Les deux se
 * règlent dans Réglages > Sons.
 */
object ThreatExplosion {

    /** Compteur observé par [ThreatExplosionOverlay] : chaque incrément lance une explosion. */
    var burst by mutableIntStateOf(0)
        private set

    /** [ignoreSettings] sert au bouton « Essayer » des Réglages (affiche l'effet même désactivé). */
    fun fire(context: Context, ignoreSettings: Boolean = false) {
        Haptics.dangerBuzz(context, ignoreSetting = ignoreSettings)
        val prefs = SoundPrefs.get(context)
        if ((ignoreSettings || prefs.explosionAnimationEnabled) && animationsAllowed(context)) burst++
    }

    /** Respecte le réglage Android « supprimer les animations » (échelle de durée à 0). */
    private fun animationsAllowed(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
        true
    }
}

private const val EXPLOSION_MS = 1500
private const val CENTER_Y = 0.38f // hauteur du centre de l'explosion, en fraction de l'écran

private class Spark(val angle: Float, val speed: Float, val size: Float, val delay: Float)

private val SPARKS: List<Spark> = Random(42).let { rnd ->
    List(40) {
        Spark(
            angle = rnd.nextFloat() * 6.2831855f,
            speed = 0.35f + rnd.nextFloat() * 0.65f,
            size = 3f + rnd.nextFloat() * 5f,
            delay = rnd.nextFloat() * 0.12f
        )
    }
}

private fun easeOut(t: Float): Float {
    val u = 1f - t.coerceIn(0f, 1f)
    return 1f - u * u * u
}

/**
 * À poser en DERNIER enfant de l'écran (au-dessus du contenu). Ne capte aucun toucher, reste muet
 * pour TalkBack, et n'est dessiné que pendant l'animation.
 */
@Composable
fun ThreatExplosionOverlay(modifier: Modifier = Modifier) {
    val burst = ThreatExplosion.burst
    // `burst` est global : sans cela, chaque écran qui s'affiche rejouerait la dernière explosion.
    val startBurst = remember { burst }
    val progress = remember { Animatable(0f) }
    var active by remember { mutableStateOf(false) }

    LaunchedEffect(burst) {
        if (burst == startBurst) return@LaunchedEffect
        active = true
        progress.snapTo(0f)
        progress.animateTo(1f, tween(EXPLOSION_MS, easing = LinearEasing))
        active = false
    }
    if (!active) return

    val p = progress.value
    Box(modifier = modifier.fillMaxSize().clearAndSetSemantics { }) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawExplosion(p) }
        Text(
            text = "\uD83D\uDCA5",
            fontSize = 110.sp,
            modifier = Modifier
                .align(BiasAlignment(0f, CENTER_Y * 2f - 1f))
                .graphicsLayer {
                    val scale = 0.3f + 1.4f * easeOut(p / 0.55f)
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - ((p - 0.25f) / 0.4f).coerceIn(0f, 1f)
                }
        )
    }
}

private fun DrawScope.drawExplosion(p: Float) {
    val center = Offset(size.width / 2f, size.height * CENTER_Y)
    val maxR = hypot(size.width, size.height) * 0.55f

    // Éclair lumineux au début.
    val flash = (1f - p / 0.35f).coerceIn(0f, 1f)
    if (flash > 0f) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFE08A).copy(alpha = 0.75f * flash),
                    Color(0xFFFF6A00).copy(alpha = 0.45f * flash),
                    Color.Transparent
                ),
                center = center,
                radius = maxR * 0.9f
            )
        )
    }

    // Deux ondes de choc qui s'élargissent.
    for (delay in floatArrayOf(0f, 0.12f)) {
        val t = (p - delay) / 0.65f
        if (t > 0f && t < 1f) {
            drawCircle(
                color = Color(0xFFFF8A00).copy(alpha = (1f - t) * 0.85f),
                radius = maxR * easeOut(t),
                center = center,
                style = Stroke(width = (16f * (1f - t) + 2f) * density)
            )
        }
    }

    // Étincelles projetées, qui retombent un peu en refroidissant (jaune -> rouge).
    for (s in SPARKS) {
        val t = (p - s.delay) / 0.75f
        if (t <= 0f || t >= 1f) continue
        val dist = s.speed * maxR * 0.8f * easeOut(t)
        val pos = Offset(
            center.x + cos(s.angle) * dist,
            center.y + sin(s.angle) * dist + t * t * size.height * 0.10f
        )
        drawCircle(
            color = lerp(Color(0xFFFFE08A), Color(0xFFD62828), t).copy(alpha = 1f - t),
            radius = s.size * (1f - 0.6f * t) * density,
            center = pos
        )
    }
}
