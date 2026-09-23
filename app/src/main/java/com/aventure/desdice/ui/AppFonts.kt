package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat

/**
 * Polices de l'application :
 *
 *   - display  : police des TITRES (histoires, en-têtes, boutons). Par défaut Bangers.
 *   - body     : police du TEXTE courant. Par défaut Nunito.
 *   - bodyBold : variante grasse du texte (intitulés). Compose ne sait pas mettre en gras
 *                une police chargée depuis un fichier : il faut utiliser cette
 *                famille plutôt que fontWeight = Bold avec `body`.
 *
 * Les deux premières peuvent être choisies par l'utilisateur dans l'écran
 * Réglages (voir FontPrefs) parmi les fichiers de app/src/main/assets/fonts.
 * Sans choix (ou si le fichier choisi est illisible), on retombe sur l'ordre
 * de recherche d'origine pour chaque police :
 *   1. app/src/main/assets/fonts/  (Bangers-Regular.ttf, Nunito-Regular.ttf ;
 *      Nunito Bold est retrouvée quelle que soit la casse / le séparateur du nom)
 *   2. app/src/main/res/font/      (bangers_regular, bangers, nunito_regular, nunito,
 *                                   nunito_variablefont_wght, nunito_bold)
 *   3. police par défaut du système (bodyBold retombe alors sur body).
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

/** "Nunito-Regular.ttf" -> chemin de "Nunito-Bold.ttf" s'il existe dans assets/fonts, sinon null. */
private fun boldSiblingPath(context: Context, regularFile: String): String? {
    val base = regularFile.substringBeforeLast('.')
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .removeSuffix("regular")
    return findAssetPath(context, FontPrefs.FONTS_DIR, base + "bold")
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
    val prefs = remember(context) { FontPrefs.get(context) }
    // Lus ici, dans la composition : un changement dans Réglages recompose
    // tous les écrans qui utilisent rememberAppFonts().
    val bodyFile = prefs.bodyFontFile
    val titleFile = prefs.titleFontFile

    return remember(context, bodyFile, titleFile) {
        // --- Texte courant ---
        val defaultBody = loadFamily(
            context,
            "fonts/Nunito-Regular.ttf",
            listOf("nunito_regular", "nunito", "nunito_variablefont_wght")
        )
        val defaultBold = findAssetPath(context, "fonts", "nunitobold")
            ?.let { typefaceFamilyOrNull(context, it) }
            ?: resFamilyOrNull(context, listOf("nunito_bold", "nunitobold"))
            ?: defaultBody

        val customBody = bodyFile?.let { typefaceFamilyOrNull(context, "fonts/$it") }
        val body = customBody ?: defaultBody
        val bold = if (bodyFile != null && customBody != null) {
            boldSiblingPath(context, bodyFile)?.let { typefaceFamilyOrNull(context, it) } ?: customBody
        } else {
            defaultBold
        }

        // --- Titres ---
        val defaultDisplay = loadFamily(
            context,
            "fonts/Bangers-Regular.ttf",
            listOf("bangers_regular", "bangers")
        )
        val display = titleFile?.let { typefaceFamilyOrNull(context, "fonts/$it") } ?: defaultDisplay

        AppFonts(display = display, body = body, bodyBold = bold)
    }
}
