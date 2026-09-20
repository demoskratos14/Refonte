package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * Polices de l'ancienne version (dice_web.py) : Bangers pour les titres,
 * Nunito pour le texte courant.
 *
 * Les fichiers sont lus depuis app/src/main/assets/fonts/ :
 *   - Bangers-Regular.ttf
 *   - Nunito-Regular.ttf
 * Si un fichier est absent, on retombe sur la police par defaut du
 * systeme : l'appli compile et fonctionne quand meme, seule l'apparence
 * des textes change.
 */
class AppFonts(val display: FontFamily, val body: FontFamily)

private fun loadFamily(context: Context, assetPath: String): FontFamily =
    try {
        FontFamily(Typeface.createFromAsset(context.assets, assetPath))
    } catch (e: Exception) {
        FontFamily.Default
    }

@Composable
fun rememberAppFonts(): AppFonts {
    val context = LocalContext.current
    return remember(context) {
        AppFonts(
            display = loadFamily(context, "fonts/Bangers-Regular.ttf"),
            body = loadFamily(context, "fonts/Nunito-Regular.ttf")
        )
    }
}
