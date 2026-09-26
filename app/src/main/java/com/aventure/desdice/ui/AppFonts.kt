package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat

/**
 * Polices de l'application :
 *
 *   - display  : police des TITRES (histoires, en-têtes, boutons). Par défaut Bangers.
 *   - body     : police du TEXTE courant. Par défaut Nunito.
 *   - bodyBold : variante grasse du texte (intitulés). Compose ne sait pas mettre en gras
 *                une police chargée depuis un fichier : il faut utiliser cette
 *                famille plutôt que fontWeight = Bold avec `body`.
 *   - reply    : police des échanges avec l'IA narratrice (bulles de conversation + champ de
 *                saisie du joueur). Par défaut celle du texte courant (`body`), réglable
 *                séparément (voir FontPrefs.reply).
 *
 * display, body et reply peuvent être choisies par l'utilisateur dans l'écran
 * Réglages (voir FontPrefs) parmi les fichiers de app/src/main/assets/fonts, chacune
 * indépendamment des deux autres. Sans choix (ou si le fichier choisi est illisible), on
 * retombe sur l'ordre de recherche d'origine pour `display` et `body` :
 *   1. app/src/main/assets/fonts/  (Bangers-Regular.ttf, Nunito-Regular.ttf ;
 *      Nunito Bold est retrouvée quelle que soit la casse / le séparateur du nom)
 *   2. app/src/main/res/font/      (bangers_regular, bangers, nunito_regular, nunito,
 *                                   nunito_variablefont_wght, nunito_bold)
 *   3. police par défaut du système (bodyBold retombe alors sur body).
 * `reply`, elle, retombe simplement sur `body` sans choix propre.
 */
class AppFonts(
    val display: FontFamily,
    val body: FontFamily,
    val bodyBold: FontFamily = body,
    val reply: FontFamily = body
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
    val bodyFile = prefs.body.fontFile
    val titleFile = prefs.title.fontFile
    val replyFile = prefs.reply.fontFile

    return remember(context, bodyFile, titleFile, replyFile) {
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

        // --- Réponses IA : sans choix propre, on retombe sur la police du texte courant ---
        val reply = replyFile?.let { typefaceFamilyOrNull(context, "fonts/$it") } ?: body

        AppFonts(display = display, body = body, bodyBold = bold, reply = reply)
    }
}

/**
 * Styles de texte complets — police + couleur + taille — pour les 3 catégories réglables dans
 * Réglages > Écriture : [title], [body] / [bodyBold] et [reply]. Chacun reflète en direct les
 * réglages correspondants de [FontPrefs] (title/body/reply) : tout écran qui utilise
 * [rememberAppTextStyles] est recomposé automatiquement dès qu'un réglage change.
 *
 * `color` vaut `Color.Unspecified` quand l'utilisateur n'a pas choisi de couleur pour la
 * catégorie ("Auto") : le style hérite alors de la couleur ambiante (celle du Text() ou du
 * thème), exactement comme un TextStyle() sans couleur.
 *
 * À utiliser à la place de fontFamily/fontSize/color posés à la main sur chaque Text(), pour
 * que les réglages de Réglages > Écriture s'appliquent où qu'ils soient utilisés :
 *   Text(text = "...", style = textStyles.title)
 *   Text(text = "...", style = MaterialTheme.typography.bodyMedium.merge(textStyles.reply))
 */
class AppTextStyles(
    val title: TextStyle,
    val body: TextStyle,
    val bodyBold: TextStyle,
    val reply: TextStyle,
    /** Couleur à poser à la place d'une couleur codée en dur pour du texte de titre, ou null
     *  si "Auto" (garder la couleur d'origine de l'écran à cet endroit). */
    val titleColor: Color?,
    /** Couleur à poser à la place d'une couleur codée en dur pour du texte courant, ou null
     *  si "Auto". */
    val bodyColor: Color?,
    /** Multiplicateur à appliquer à toute taille de TITRE codée en dur dans un écran
     *  (`fontSize * titleScale`), pour que le réglage de taille des titres s'applique aussi
     *  bien au gros titre d'une histoire qu'au petit titre d'un en-tête, en conservant leurs
     *  proportions respectives (1f = taille par défaut, non modifiée). */
    val titleScale: Float,
    /** Même principe que [titleScale], pour le texte courant. */
    val bodyScale: Float
)

@Composable
fun rememberAppTextStyles(fonts: AppFonts, prefs: FontPrefs): AppTextStyles {
    val titleColor = prefs.title.color ?: Color.Unspecified
    val titleSize = prefs.title.sizeSp
    val bodyColor = prefs.body.color ?: Color.Unspecified
    val bodySize = prefs.body.sizeSp
    val replyColor = prefs.reply.color ?: Color.Unspecified
    val replySize = prefs.reply.sizeSp

    return remember(fonts, titleColor, titleSize, bodyColor, bodySize, replyColor, replySize) {
        AppTextStyles(
            title = TextStyle(fontFamily = fonts.display, fontSize = titleSize.sp, color = titleColor),
            body = TextStyle(fontFamily = fonts.body, fontSize = bodySize.sp, color = bodyColor),
            bodyBold = TextStyle(fontFamily = fonts.bodyBold, fontSize = bodySize.sp, color = bodyColor),
            reply = TextStyle(fontFamily = fonts.reply, fontSize = replySize.sp, color = replyColor),
            titleColor = prefs.title.color,
            bodyColor = prefs.body.color,
            titleScale = titleSize / FontPrefs.DEFAULT_TITLE_SIZE_SP,
            bodyScale = bodySize / FontPrefs.DEFAULT_BODY_SIZE_SP
        )
    }
}

/** `base` (en sp, taille d'origine codée en dur) mis à l'échelle du réglage Titres de l'utilisateur. */
fun AppTextStyles.titleSp(base: Float) = (base * titleScale).sp

/** `base` (en sp, taille d'origine codée en dur) mis à l'échelle du réglage Texte courant de l'utilisateur. */
fun AppTextStyles.bodySp(base: Float) = (base * bodyScale).sp
