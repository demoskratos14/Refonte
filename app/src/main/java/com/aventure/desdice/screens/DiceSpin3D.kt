package com.aventure.desdice.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppTextStyles
import com.aventure.desdice.ui.titleSp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Duree du lancer (millisecondes).
internal const val SpinDurationMs = 1800

// Position des points de chaque face : (ligne, colonne) dans une grille 3x3.
internal val PIP_POSITIONS: Map<Int, List<Pair<Int, Int>>> = mapOf(
    1 to listOf(2 to 2),
    2 to listOf(1 to 1, 3 to 3),
    3 to listOf(1 to 1, 2 to 2, 3 to 3),
    4 to listOf(1 to 1, 1 to 3, 3 to 1, 3 to 3),
    5 to listOf(1 to 1, 1 to 3, 2 to 2, 3 to 1, 3 to 3),
    6 to listOf(1 to 1, 1 to 3, 2 to 1, 2 to 3, 3 to 1, 3 to 3)
)

/**
 * Symbole (image, ou emoji deja mesure) dessine a la place d'un point noir sur les
 * faces du de de reussite qui roule -- utilise par l'ecran de jeu, ou chaque pip est
 * le symbole d'un totem. Sans glyphes, le de garde ses points noirs.
 */
internal class PipGlyph(val image: ImageBitmap?, val emoji: TextLayoutResult?)

/** Taille (dp) d'un symbole sur une face portant `count` points. */
internal fun pipGlyphSizeDp(count: Int): Int = when (count) {
    1 -> 96
    2 -> 38
    3 -> 32
    4 -> 29
    5 -> 26
    else -> 22
}

// ----------------------------------------------------------------------
// De 3D qui roule : un vrai cube dessine face par face dans un Canvas
// ----------------------------------------------------------------------

/** Ce qui est en train de rouler (null = ce de-la reste a plat). */
internal class SpinState(
    val success: SpinSpec?,
    val fate: SpinSpec?,
    // Dé de réussite autre qu'un d6 : nombre écrit sur chacune des 6 faces du cube qui roule
    // (la face finale porte le vrai résultat). null = points classiques.
    val successNumbers: List<Int>? = null
)

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
    textStyles: AppTextStyles,
    faceColor: Color,
    // De de reussite seulement : symboles a dessiner sur chaque face, par face (index 0..5)
    // puis par point. null = points noirs (page "Des classiques").
    pipGlyphs: List<List<PipGlyph>>? = null,
    // De de reussite autre qu'un d6 : nombre a ecrire sur chacune des 6 faces (prioritaire sur
    // les points et sur pipGlyphs). null = comportement d'origine.
    faceNumbers: List<Int>? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val numberLayouts = remember(faceNumbers, fonts) {
        faceNumbers?.map { n ->
            val text = n.toString()
            val size = when {
                text.length >= 3 -> 46.sp
                text.length == 2 -> 64.sp
                else -> 80.sp
            }
            textMeasurer.measure(text, TextStyle(fontFamily = fonts.display, fontSize = size, color = ClassicInk))
        }
    }
    val emojiLayouts = remember(cubeFateFaces) {
        cubeFateFaces?.map { textMeasurer.measure(it.emoji, TextStyle(fontSize = 58.sp, color = ClassicInk)) }
    }
    val labelLayouts = remember(cubeFateFaces, fonts, textStyles) {
        cubeFateFaces?.map {
            textMeasurer.measure(
                it.label,
                TextStyle(fontFamily = fonts.display, fontSize = textStyles.titleSp(16f), color = ClassicPurple, textAlign = TextAlign.Center)
            )
        }
    }
    val finalR = remember(spec) { finalRotation(FACE_FRAMES[spec.faceIndex]) }

    Canvas(Modifier.fillMaxSize()) {
        val p = (progress.value / spec.timeScale).coerceIn(0f, 1f)
        drawTumblingCube(spec, finalR, p, faceColor, emojiLayouts, labelLayouts, pipGlyphs, numberLayouts)
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
    pipGlyphs: List<List<PipGlyph>>? = null,
    numberLayouts: List<TextLayoutResult>? = null
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
            color = ClassicInk.copy(alpha = hardAlpha),
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
        drawRoundRect(color = ClassicInk, size = Size(base, base), cornerRadius = CornerRadius(corner))
        drawRoundRect(
            color = faceColor,
            topLeft = Offset(border, border),
            size = Size(base - 2 * border, base - 2 * border),
            cornerRadius = CornerRadius(corner - border)
        )

        if (numberLayouts != null) {
            // De a nombres (autre qu'un d6) : le nombre de la face, centre.
            numberLayouts.getOrNull(i)?.let { nl ->
                drawText(nl, topLeft = Offset((base - nl.size.width) / 2f, (base - nl.size.height) / 2f))
            }
        } else if (emojiLayouts == null || labelLayouts == null) {
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
                        color = ClassicInk,
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
