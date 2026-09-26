package com.aventure.desdice.ui

import android.content.Context
import android.content.SharedPreferences
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
 * Réglages complets (police, couleur, taille) d'UNE catégorie de texte, persistés dans les
 * SharedPreferences fournies sous les 3 clés passées au constructeur.
 *
 * Chaque catégorie de FontPrefs ([FontPrefs.title], [FontPrefs.body], [FontPrefs.reply]) est
 * une instance indépendante : changer la couleur des titres n'affecte ni la police du texte
 * courant, ni la taille des réponses de l'IA.
 *
 * `fontFile == null` = police par défaut de la catégorie ; `color == null` = couleur
 * automatique (celle de l'écran / du thème, comme avant l'ajout de ce réglage). Toutes les
 * propriétés sont des états Compose : tout écran qui les lit est recomposé automatiquement dès
 * qu'elles changent.
 */
class TextStyleChoice internal constructor(
    private val prefs: SharedPreferences,
    private val keyFont: String,
    private val keyColor: String,
    private val keySize: String,
    defaultFontFile: String?,
    defaultSizeSp: Float,
    val minSizeSp: Float,
    val maxSizeSp: Float
) {
    /** Fichier choisi dans assets/fonts, ou null = police par défaut de la catégorie. */
    var fontFile: String? by mutableStateOf(prefs.getString(keyFont, defaultFontFile))
        private set

    private var colorArgb: Int? by mutableStateOf(
        prefs.getInt(keyColor, NO_COLOR).takeIf { it != NO_COLOR }
    )

    /** Couleur choisie, ou null = automatique. */
    val color: Color? get() = colorArgb?.let { Color(it) }

    /** Taille du texte, en sp. */
    var sizeSp: Float by mutableFloatStateOf(
        prefs.getFloat(keySize, defaultSizeSp).coerceIn(minSizeSp, maxSizeSp)
    )
        private set

    fun setFont(file: String?) {
        fontFile = file
        prefs.edit().apply {
            if (file == null) remove(keyFont) else putString(keyFont, file)
        }.apply()
    }

    fun setColor(newColor: Color?) {
        colorArgb = newColor?.let { it.toArgb() and 0x00FFFFFF or (0xFF shl 24) }
        prefs.edit().apply {
            if (newColor == null) remove(keyColor) else putInt(keyColor, colorArgb!!)
        }.apply()
    }

    fun setSize(sp: Float) {
        sizeSp = sp.coerceIn(minSizeSp, maxSizeSp)
        prefs.edit().putFloat(keySize, sizeSp).apply()
    }

    private companion object {
        const val NO_COLOR = 0
    }
}

/**
 * Choix de polices/couleurs/tailles de l'utilisateur, mémorisés dans les SharedPreferences.
 *
 * Trois catégories indépendantes (chacune une [TextStyleChoice]) :
 *   - [title] : titres (histoires, en-têtes, boutons). Par défaut Bangers.
 *   - [body]  : texte courant (descriptions, libellés d'interface, narration hors bulles IA).
 *     Par défaut Nunito.
 *   - [reply] : échanges avec l'IA narratrice (bulles de conversation + champ de saisie du
 *     joueur). Par défaut la même police que [body], réglable séparément.
 *
 * Réglages > page « Écriture ».
 */
class FontPrefs private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val title = TextStyleChoice(
        prefs, KEY_TITLE_FONT, KEY_TITLE_COLOR, KEY_TITLE_SIZE,
        DEFAULT_TITLE_FILE, DEFAULT_TITLE_SIZE_SP, MIN_TITLE_SIZE_SP, MAX_TITLE_SIZE_SP
    )
    val body = TextStyleChoice(
        prefs, KEY_BODY_FONT, KEY_BODY_COLOR, KEY_BODY_SIZE,
        DEFAULT_BODY_FILE, DEFAULT_BODY_SIZE_SP, MIN_BODY_SIZE_SP, MAX_BODY_SIZE_SP
    )
    val reply = TextStyleChoice(
        prefs, KEY_REPLY_FONT, KEY_REPLY_COLOR, KEY_REPLY_SIZE,
        DEFAULT_BODY_FILE, DEFAULT_REPLY_SIZE_SP, MIN_REPLY_SIZE_SP, MAX_REPLY_SIZE_SP
    )

    // ------------------------------------------------------------------
    // Compatibilité avec l'ancienne API à plat (utilisée ailleurs dans le projet avant
    // l'introduction de title/body/reply). Ne stocke rien de nouveau : délègue directement
    // aux instances ci-dessus, mêmes clés SharedPreferences qu'avant pour body/title/reply
    // couleur+taille -> aucune perte de réglage déjà enregistré par un utilisateur.
    // Préférer title/body/reply directement dans le nouveau code.
    // ------------------------------------------------------------------
    @Deprecated("Utiliser body.fontFile", ReplaceWith("body.fontFile"))
    val bodyFontFile: String? get() = body.fontFile

    @Deprecated("Utiliser title.fontFile", ReplaceWith("title.fontFile"))
    val titleFontFile: String? get() = title.fontFile

    @Deprecated("Utiliser body.setFont(file)", ReplaceWith("body.setFont(file)"))
    fun setBodyFont(file: String?) = body.setFont(file)

    @Deprecated("Utiliser title.setFont(file)", ReplaceWith("title.setFont(file)"))
    fun setTitleFont(file: String?) = title.setFont(file)

    @Deprecated("Utiliser reply.color", ReplaceWith("reply.color"))
    val replyTextColor: Color? get() = reply.color

    @Deprecated("Utiliser reply.sizeSp", ReplaceWith("reply.sizeSp"))
    val replyTextSizeSp: Float get() = reply.sizeSp

    @Deprecated("Utiliser reply.setColor(color)", ReplaceWith("reply.setColor(color)"))
    fun setReplyTextColor(color: Color?) = reply.setColor(color)

    @Deprecated("Utiliser reply.setSize(sp)", ReplaceWith("reply.setSize(sp)"))
    fun setReplyTextSize(sp: Float) = reply.setSize(sp)

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

        const val DEFAULT_TITLE_SIZE_SP = 22f
        const val MIN_TITLE_SIZE_SP = 16f
        const val MAX_TITLE_SIZE_SP = 34f

        const val DEFAULT_BODY_SIZE_SP = 15f
        const val MIN_BODY_SIZE_SP = 12f
        const val MAX_BODY_SIZE_SP = 20f

        const val DEFAULT_REPLY_SIZE_SP = 15f
        const val MIN_REPLY_SIZE_SP = 12f
        const val MAX_REPLY_SIZE_SP = 24f

        // Couleurs prédéfinies : remplacées par une roue chromatique (Réglages > Écriture,
        // ColorWheelPicker dans SettingsScreen.kt) -- plus de liste fixe à proposer ici.

        private const val PREFS_NAME = "app_font_prefs"

        // Mêmes clés qu'avant la refonte pour la police du texte/titres et pour la
        // couleur/taille des réponses IA : les réglages déjà enregistrés par un utilisateur
        // sont conservés tels quels après mise à jour de l'appli.
        private const val KEY_TITLE_FONT = "title_font_file"
        private const val KEY_TITLE_COLOR = "title_color_argb"
        private const val KEY_TITLE_SIZE = "title_size_sp"

        private const val KEY_BODY_FONT = "body_font_file"
        private const val KEY_BODY_COLOR = "body_color_argb"
        private const val KEY_BODY_SIZE = "body_size_sp"

        // La police des réponses IA est un nouveau réglage (avant : toujours celle du texte
        // courant) -> nouvelle clé, pas de conflit possible.
        private const val KEY_REPLY_FONT = "reply_font_file"
        private const val KEY_REPLY_COLOR = "reply_text_color_argb"
        private const val KEY_REPLY_SIZE = "reply_text_size_sp"

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
