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
 * Nunito pour le texte courant, Nunito Bold pour les intitules en gras.
 *
 *   - display  : Bangers (titres, boutons)
 *   - body     : Nunito (texte courant)
 *   - bodyBold : Nunito Bold (intitules). Compose ne sait pas mettre en gras
 *                une police chargee depuis un fichier : il faut utiliser cette
 *                famille plutot que fontWeight = Bold avec `body`.
 *
 * Ordre de recherche pour chaque police :
 *   1. app/src/main/assets/fonts/  (Bangers-Regular.ttf, Nunito-Regular.ttf ;
 *      Nunito Bold est retrouvee quelle que soit la casse / le separateur du nom :
 *      Nunito-Bold.ttf, nunito-bold.ttf, NunitoBold.ttf...)
 *   2. app/src/main/res/font/      (noms courants : bangers_regular, bangers,
 *                                   nunito_regular, nunito, nunito_variablefont_wght,
 *                                   nunito_bold)
 *   3. police par defaut du systeme : l'appli compile et fonctionne quand
 *      meme, seule l'apparence des textes change (bodyBold retombe alors sur body).
 */
class AppFonts(
    val display: FontFamily,
    val body: FontFamily,
    val bodyBold: FontFamily = body
)

private fun typefaceFamilyOrNull(context: Context, assetPath: String): FontFamily? =
    try {
        FontFamily(Typeface.createFromAsset(context.assets, assetPath))
    } catch (e: Exception) {
        null
    }

private fun resFamilyOrNull(context: Context, resNames: List<String>): FontFamily? {
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
    return null
}

/** Cherche dans assets/<dir> un fichier dont le nom (sans extension, sans tirets/espaces, en minuscules) vaut `normalized`. */
private fun findAssetPath(context: Context, dir: String, normalized: String): String? {
    val files: Array<String> = try {
        context.assets.list(dir) ?: emptyArray()
    } catch (e: Exception) {
        emptyArray()
    }
    val match = files.firstOrNull { file ->
        file.substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() } == normalized
    }
    return match?.let { "$dir/$it" }
}

private fun loadFamily(
    context: Context,
    assetPath: String,
    resNames: List<String>
): FontFamily =
    typefaceFamilyOrNull(context, assetPath)
        ?: resFamilyOrNull(context, resNames)
        ?: FontFamily.Default

@Composable
fun rememberAppFonts(): AppFonts {
    val context = LocalContext.current
    return remember(context) {
        val body = loadFamily(
            context,
            "fonts/Nunito-Regular.ttf",
            listOf("nunito_regular", "nunito", "nunito_variablefont_wght")
        )
        val bold = findAssetPath(context, "fonts", "nunitobold")
            ?.let { typefaceFamilyOrNull(context, it) }
            ?: resFamilyOrNull(context, listOf("nunito_bold", "nunitobold"))
            ?: body
        AppFonts(
            display = loadFamily(
                context,
                "fonts/Bangers-Regular.ttf",
                listOf("bangers_regular", "bangers")
            ),
            body = body,
            bodyBold = bold
        )
    }
}
