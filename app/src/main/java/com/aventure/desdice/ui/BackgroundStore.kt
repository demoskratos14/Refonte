package com.aventure.desdice.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.aventure.desdice.ImageUtils
import com.aventure.desdice.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Les fonds d'écran personnalisables (les fonds des histoires, eux, sont déjà
 * gérés par la création / l'édition d'histoire). `defaultRes` = image livrée
 * avec l'appli, utilisée tant qu'aucune image personnalisée n'est choisie.
 */
enum class BackgroundSlot(val key: String, val label: String, val defaultRes: Int) {
    KEY_PAGE("key_page", "Page de la clé API", R.raw.bg_key_page),
    SETTINGS("settings", "Page Réglages", R.raw.bg_settings),
    NEW_STORY("new_story", "Nouvelle histoire (carrousel)", R.drawable.new_story_bg),
    CREATE_STORY("create_story", "Création d'histoire", R.drawable.bg_create_story),
    CLASSIC_DICE("classic_dice", "Dés classiques", R.drawable.bg_classic_dice)
}

/**
 * Images de fond choisies par l'utilisateur, copiées dans
 * filesDir/backgrounds/<slot>.jpg (redimensionnées / complétées en portrait
 * par ImageUtils.resizeBgBytes, comme les fonds d'histoires).
 *
 * `versions` est un état Compose : quand une image change, tous les écrans qui
 * utilisent rememberBackgroundPainter() se rechargent tout seuls.
 * 0 = pas d'image personnalisée (on affiche l'image par défaut).
 */
class BackgroundStore private constructor(private val appContext: Context) {

    private val dir = File(appContext.filesDir, "backgrounds")
    private val versions = mutableStateMapOf<String, Long>()

    init {
        BackgroundSlot.values().forEach { slot ->
            val file = fileFor(slot)
            versions[slot.key] = if (file.exists()) file.lastModified() else 0L
        }
    }

    private fun fileFor(slot: BackgroundSlot) = File(dir, "${slot.key}.jpg")

    fun versionOf(slot: BackgroundSlot): Long = versions[slot.key] ?: 0L

    fun hasCustom(slot: BackgroundSlot): Boolean = versionOf(slot) != 0L

    /** Copie l'image choisie (Uri de la galerie) comme fond de `slot`. Renvoie false si illisible. */
    suspend fun setFromUri(slot: BackgroundSlot, uri: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) {
            try {
                val raw = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext false
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
                val bytes = ImageUtils.resizeBgBytes(raw)
                dir.mkdirs()
                fileFor(slot).writeBytes(bytes)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (ok) versions[slot.key] = System.currentTimeMillis()
        return ok
    }

    /** Revient à l'image livrée avec l'appli. */
    fun reset(slot: BackgroundSlot) {
        fileFor(slot).delete()
        versions[slot.key] = 0L
    }

    suspend fun loadBitmap(slot: BackgroundSlot): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(fileFor(slot).path)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: BackgroundStore? = null

        fun get(context: Context): BackgroundStore =
            instance ?: synchronized(this) {
                instance ?: BackgroundStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Painter du fond de `slot` : l'image personnalisée si elle existe, sinon
 * l'image par défaut. Le décodage se fait hors du thread principal ; en
 * attendant, un aplat sombre évite de voir clignoter l'image par défaut.
 */
@Composable
fun rememberBackgroundPainter(slot: BackgroundSlot): Painter {
    val context = LocalContext.current
    val store = remember(context) { BackgroundStore.get(context) }
    val version = store.versionOf(slot) // lu ici : un changement recompose

    val loaded by produceState(initialValue = false to (null as ImageBitmap?), slot, version) {
        value = if (version == 0L) (true to null) else (true to store.loadBitmap(slot))
    }
    val bitmap = loaded.second
    val customPainter = remember(bitmap) { bitmap?.let { BitmapPainter(it) } }
    val defaultPainter = painterResource(id = slot.defaultRes)

    return when {
        version == 0L -> defaultPainter
        !loaded.first -> ColorPainter(Color(0xFF1B140C))
        customPainter != null -> customPainter
        else -> defaultPainter // fichier illisible : on retombe sur l'image par défaut
    }
}
