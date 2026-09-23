// ImageUtils.kt
// Portage Kotlin de image_utils.py.
//
// Différence avec Pillow côté Python : Android décode/encode toujours
// nativement (BitmapFactory / Bitmap.compress), donc pas d'équivalent de
// _PIL_AVAILABLE ici — ces fonctions sont toujours disponibles. Le flou
// gaussien de Pillow (ImageFilter.GaussianBlur) n'a pas d'équivalent
// natif simple sur Android (RenderScript est déprécié) : boxBlur()
// ci-dessous approxime un flou gaussien par plusieurs passes de flou de
// boîte (technique "stack blur" classique), appliqué sur une version
// réduite de l'image pour rester fluide sur mobile — voir
// BLUR_WORKING_MAX_DIMENSION.

package com.aventure.desdice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtils {

    private const val MAX_BG_DIMENSION = 1600
    private const val BG_JPEG_QUALITY = 82
    private const val TARGET_BG_RATIO = 0.6
    private const val PORTRAIT_TOLERANCE = 1.05
    private const val BG_LETTERBOX_BLUR_RADIUS = 45

    private const val MAX_TOTEM_DIMENSION = 400
    private const val TOTEM_JPEG_QUALITY = 85

    // Le flou est fait "à la main" pixel par pixel (pas d'équivalent
    // GaussianBlur natif) : on le limite à une image de travail plafonnée
    // à cette taille avant de flouter, puis on remet à l'échelle — sans
    // quoi flouter un fond de plusieurs Mpx serait bien trop lent sur
    // téléphone.
    private const val BLUR_WORKING_MAX_DIMENSION = 900

    private fun resizeIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (max(w, h) <= maxDimension) return bitmap
        val ratio = maxDimension / max(w, h).toFloat()
        val newW = max(1, (w * ratio).roundToInt())
        val newH = max(1, (h * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Miroir de _letterbox_to_portrait() : convertit une image trop
     * "large" (paysage, ou proche du carré) en portrait en ajoutant du
     * remplissage FLOU en haut/bas — jamais en recadrant les côtés — pour
     * ne perdre aucun élément de la composition d'origine. Une image déjà
     * suffisamment verticale est renvoyée TELLE QUELLE.
     */
    private fun letterboxToPortrait(
        source: Bitmap,
        targetRatio: Double = TARGET_BG_RATIO,
        blurRadius: Int = BG_LETTERBOX_BLUR_RADIUS
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (h <= 0) return source
        val ratio = w / h.toDouble()
        if (ratio <= targetRatio * PORTRAIT_TOLERANCE) return source

        val canvasW = w
        val canvasH = (w / targetRatio).roundToInt()

        // Fond de remplissage : version agrandie de l'image (façon "cover"
        // CSS), recadrée au centre, puis floutée.
        val canvasRatio = canvasW / canvasH.toDouble()
        val bgW: Int
        val bgH: Int
        if (ratio > canvasRatio) {
            bgH = canvasH
            bgW = max(1, (bgH * ratio).roundToInt())
        } else {
            bgW = canvasW
            bgH = max(1, (bgW / ratio).roundToInt())
        }
        val scaledBg = Bitmap.createScaledBitmap(source, bgW, bgH, true)
        val cropLeft = ((bgW - canvasW) / 2).coerceIn(0, bgW - 1)
        val cropTop = ((bgH - canvasH) / 2).coerceIn(0, bgH - 1)
        val cropW = (bgW - cropLeft).coerceAtMost(canvasW).coerceAtLeast(1)
        val cropH = (bgH - cropTop).coerceAtMost(canvasH).coerceAtLeast(1)
        var background = Bitmap.createBitmap(scaledBg, cropLeft, cropTop, cropW, cropH)
        if (cropW != canvasW || cropH != canvasH) {
            background = Bitmap.createScaledBitmap(background, canvasW, canvasH, true)
        }

        // Flou sur une version réduite (perf mobile), puis remise à l'échelle.
        val workingScale = min(1f, BLUR_WORKING_MAX_DIMENSION / max(background.width, background.height).toFloat())
        val blurred = if (workingScale < 1f) {
            val smallW = max(1, (background.width * workingScale).roundToInt())
            val smallH = max(1, (background.height * workingScale).roundToInt())
            val small = Bitmap.createScaledBitmap(background, smallW, smallH, true)
            val smallBlurred = boxBlur(small, max(1, (blurRadius * workingScale).roundToInt()))
            Bitmap.createScaledBitmap(smallBlurred, background.width, background.height, true)
        } else {
            boxBlur(background, blurRadius)
        }

        // Premier plan : l'image d'origine ENTIÈRE, non recadrée, centrée
        // verticalement par-dessus le fond flouté.
        val canvasBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawBitmap(blurred, 0f, 0f, null)
        val pasteY = (canvasH - h) / 2f
        canvas.drawBitmap(source, 0f, pasteY, null)
        return canvasBitmap
    }

    /** Flou approximatif (box blur répété façon "stack blur") — largement suffisant pour un fond flouté d'arrière-plan. */
    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlurPass(pixels, w, h, radius, horizontal = true)
            boxBlurPass(pixels, w, h, radius, horizontal = false)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val windowSize = 2 * radius + 1
        val temp = IntArray(pixels.size)
        for (line in 0 until lines) {
            fun index(pos: Int): Int = if (horizontal) line * w + pos else pos * w + line
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = pixels[index(i.coerceIn(0, length - 1))]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (pos in 0 until length) {
                temp[index(pos)] = ((aSum / windowSize) shl 24) or
                    ((rSum / windowSize) shl 16) or
                    ((gSum / windowSize) shl 8) or
                    (bSum / windowSize)
                val outPos = (pos - radius).coerceIn(0, length - 1)
                val inPos = (pos + radius + 1).coerceIn(0, length - 1)
                val pOut = pixels[index(outPos)]
                val pIn = pixels[index(inPos)]
                aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        System.arraycopy(temp, 0, pixels, 0, pixels.size)
    }

    /**
     * Pour une image de FOND : complète en portrait si besoin (voir
     * letterboxToPortrait — ne modifie pas les images déjà verticales),
     * redimensionne et réencode systématiquement en JPEG opaque. En cas
     * d'échec (image illisible...), renvoie les octets d'origine.
     */
    fun resizeBgBytes(rawBytes: ByteArray, maxDimension: Int = MAX_BG_DIMENSION, quality: Int = BG_JPEG_QUALITY): ByteArray {
        if (rawBytes.isEmpty()) return rawBytes
        return try {
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            val letterboxed = letterboxToPortrait(decoded)
            val resized = resizeIfNeeded(letterboxed, maxDimension)
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            rawBytes
        }
    }

    /**
     * Pour une image de TOTEM : redimensionne, conserve la transparence si
     * la source en a une (réencodée en PNG dans ce cas), sinon réencode en
     * JPEG. Renvoie (bytes, extension), ou (bytes d'origine, null) en cas
     * d'échec — l'appelant garde alors l'extension d'origine du fichier.
     */
    fun resizeTotemBytes(
        rawBytes: ByteArray,
        maxDimension: Int = MAX_TOTEM_DIMENSION,
        quality: Int = TOTEM_JPEG_QUALITY
    ): Pair<ByteArray, String?> {
        if (rawBytes.isEmpty()) return rawBytes to null
        return try {
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes to null
            val alpha = decoded.hasAlpha()
            val resized = resizeIfNeeded(decoded, maxDimension)
            val out = ByteArrayOutputStream()
            if (alpha) {
                resized.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray() to "png"
            } else {
                resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray() to "jpg"
            }
        } catch (e: Exception) {
            rawBytes to null
        }
    }
}
