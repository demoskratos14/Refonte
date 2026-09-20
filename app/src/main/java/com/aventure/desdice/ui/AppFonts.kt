package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat

/**
 * Polices de l'ancienne version (dice_web.py) : Bangers pour les titres,
 * Nunito pour le texte courant.
 *
 * Ordre de recherche pour chaque police :
 *   1. app/src/main/assets/fonts/  (Bangers-Regular.ttf, Nunito-Regular.ttf)
 *   2. app/src/main/res/font/      (noms courants : bangers_regular, bangers,
 *                                   nunito_regular, nunito, nunito_variablefont_wght)
 *   3. police par defaut du systeme : l'appli compile et fonctionne quand
 *      meme, seule l'apparence des textes change.
 */
class AppFonts(val display: FontFamily, val body: FontFamily)

private fun loadFamily(
    context: Context,
    assetPath: String,
    resNames: List<String>
): FontFamily {
    try {
        return FontFamily(Typeface.createFromAsset(context.assets, assetPath))
    } catch (e: Exception) {
        // pas dans assets : on essaie res/font
    }
    for (name in resNames) {
        val id = context.resources.getIdentifier(name, "font", context.packageName)
        if (id != 0) {
            try {
                val typeface = ResourcesCompat.getFont(context, id)
                if (typeface != null) return FontFamily(typeface)
            } catch (e: Exception) {
                // on essaie le nom suivant
            }
        }
    }
    return FontFamily.Default
}

@Composable
fun rememberAppFonts(): AppFonts {
    val context = LocalContext.current
    return remember(context) {
        AppFonts(
            display = loadFamily(
                context,
                "fonts/Bangers-Regular.ttf",
                listOf("bangers_regular", "bangers")
            ),
            body = loadFamily(
                context,
                "fonts/Nunito-Regular.ttf",
                listOf("nunito_regular", "nunito", "nunito_variablefont_wght")
            )
        )
    }
}
