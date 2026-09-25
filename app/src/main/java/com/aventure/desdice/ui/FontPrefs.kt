package com.aventure.desdice.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

    // ------------------------------------------------------------------
    // Couleur et taille du texte des zones de réponse (échanges avec l'IA,
    // champ de saisie du joueur). Réglages > page « Écriture ».
    // ------------------------------------------------------------------

    /** Couleur choisie, ou null = automatique (couleur par défaut de l'écran). */
    var replyTextColorArgb: Int? by mutableStateOf(
        prefs.getInt(KEY_REPLY_COLOR, NO_COLOR).takeIf { it != NO_COLOR }
    )
        private set

    val replyTextColor: Color? get() = replyTextColorArgb?.let { Color(it) }

    /** Taille du texte des réponses, en sp. */
    var replyTextSizeSp: Float by mutableFloatStateOf(
        prefs.getFloat(KEY_REPLY_SIZE, DEFAULT_REPLY_SIZE_SP)
    )
        private set

    fun setReplyTextColor(color: Color?) {
        replyTextColorArgb = color?.let { it.toArgb() and 0x00FFFFFF or (0xFF shl 24) }
        prefs.edit().apply {
            if (color == null) remove(KEY_REPLY_COLOR) else putInt(KEY_REPLY_COLOR, replyTextColorArgb!!)
        }.apply()
    }

    fun setReplyTextSize(sp: Float) {
        replyTextSizeSp = sp.coerceIn(MIN_REPLY_SIZE_SP, MAX_REPLY_SIZE_SP)
        prefs.edit().putFloat(KEY_REPLY_SIZE, replyTextSizeSp).apply()
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
        const val DEFAULT_REPLY_SIZE_SP = 15f
        const val MIN_REPLY_SIZE_SP = 12f
        const val MAX_REPLY_SIZE_SP = 24f

        /** Couleurs proposées dans les Réglages ; "Auto" (null) n'y figure pas, gérée à part. */
        val REPLY_TEXT_COLOR_PRESETS: List<Pair<String, Color>> = listOf(
            "Blanc" to Color(0xFFFFFFFF),
            "Crème" to Color(0xFFFBF3E1),
            "Ambre" to Color(0xFFFFC94D),
            "Ciel" to Color(0xFF8FD3FE),
            "Menthe" to Color(0xFF8FE3B0),
            "Rose" to Color(0xFFFFA8C5),
            "Lavande" to Color(0xFFC9A8FF),
            "Encre" to Color(0xFF14161A)
        )

        private const val PREFS_NAME = "app_font_prefs"
        private const val KEY_BODY = "body_font_file"
        private const val KEY_TITLE = "title_font_file"
        private const val KEY_REPLY_COLOR = "reply_text_color_argb"
        private const val KEY_REPLY_SIZE = "reply_text_size_sp"
        private const val NO_COLOR = 0

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
