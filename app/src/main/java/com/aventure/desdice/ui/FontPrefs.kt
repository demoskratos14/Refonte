package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/** Une police trouvée dans assets/fonts : nom de fichier + libellé lisible. */
data class FontOption(val file: String, val label: String)

/**
 * Choix de polices de l'utilisateur, mémorisés dans les SharedPreferences.
 *
 * Les deux propriétés sont des états Compose : tout écran qui appelle
 * rememberAppFonts() est recomposé automatiquement dès qu'un choix change.
 * `null` = police par défaut (Nunito pour le texte, Bangers pour les titres).
 */
class FontPrefs private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Police du texte courant de l'appli (nom de fichier dans assets/fonts). */
    var bodyFontFile: String? by mutableStateOf(prefs.getString(KEY_BODY, null))
        private set

    /** Police des titres (histoires, en-têtes, boutons). */
    var titleFontFile: String? by mutableStateOf(prefs.getString(KEY_TITLE, null))
        private set

    fun setBodyFont(file: String?) {
        bodyFontFile = file
        prefs.edit().putString(KEY_BODY, file).apply()
    }

    fun setTitleFont(file: String?) {
        titleFontFile = file
        prefs.edit().putString(KEY_TITLE, file).apply()
    }

    private val familyCache = HashMap<String, FontFamily?>()

    /** FontFamily d'un fichier de assets/fonts (mise en cache), ou null si illisible. */
    fun familyFor(file: String): FontFamily? = familyCache.getOrPut(file) {
        try {
            FontFamily(Typeface.createFromAsset(appContext.assets, "$FONTS_DIR/$file"))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val FONTS_DIR = "fonts"
        const val DEFAULT_BODY_FILE = "Nunito-Regular.ttf"
        const val DEFAULT_TITLE_FILE = "Bangers-Regular.ttf"

        private const val PREFS_NAME = "app_font_prefs"
        private const val KEY_BODY = "body_font_file"
        private const val KEY_TITLE = "title_font_file"

        @Volatile
        private var instance: FontPrefs? = null

        fun get(context: Context): FontPrefs =
            instance ?: synchronized(this) {
                instance ?: FontPrefs(context.applicationContext).also { instance = it }
            }
    }
}

// Variantes de style (gras, italique...) : on ne les propose pas comme police
// à part entière — la variante grasse de la police du texte est retrouvée
// automatiquement (voir boldSiblingPath dans AppFonts.kt).
private val STYLED_SUFFIXES = listOf("bold", "italic", "light", "thin", "medium", "black")

/** Toutes les polices .ttf / .otf présentes dans app/src/main/assets/fonts, triées par nom. */
fun listAssetFonts(context: Context): List<FontOption> {
    val files: List<String> = try {
        context.assets.list(FontPrefs.FONTS_DIR)?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
    return files
        .filter { f -> f.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") } }
        .filter { f ->
            val n = f.substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() }
            STYLED_SUFFIXES.none { n.endsWith(it) }
        }
        .map { FontOption(it, prettyFontName(it)) }
        .sortedBy { it.label.lowercase() }
}

/** "PermanentMarker-Regular.ttf" -> "Permanent Marker". */
fun prettyFontName(file: String): String {
    var name = file.substringBeforeLast('.')
    name = name.replace(Regex("[-_ ]?VariableFont.*", RegexOption.IGNORE_CASE), "")
    name = name.replace(Regex("[-_ ]?Regular$", RegexOption.IGNORE_CASE), "")
    name = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    name = name.replace('_', ' ').replace('-', ' ').trim()
    return name.ifEmpty { file }
}
