package com.aventure.desdice.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aventure.desdice.R

/**
 * Musique de fond des menus.
 *
 * Les morceaux sont découverts tout seuls : tout fichier de app/src/main/res/raw
 * dont le nom commence par `music_` (music_foret.mp3, music_ambiance1.ogg...)
 * entre dans la liste de lecture. Rien à déclarer dans le code ; sans fichier,
 * tout ce qui suit est sans effet.
 *
 * Règles :
 *   - un morceau tiré au hasard démarre au lancement de l'appli (MusicHost) ;
 *   - à la fin d'un morceau, un autre est tiré au hasard (jamais le même deux fois de
 *     suite s'il y en a plusieurs) ;
 *   - entrer dans une histoire coupe la musique (enterStory) ; la page de jeu
 *     propose de la relancer si on en a envie ;
 *   - la musique se met en pause quand l'appli passe en arrière-plan.
 */
object MusicPlayer {

    private var player: MediaPlayer? = null
    private var lastTrack = 0
    private var pausedByBackground = false
    private var appContext: Context? = null

    /** true tant qu'un morceau est chargé (lecture ou pause d'arrière-plan). Lu par l'interface. */
    var isPlaying by mutableStateOf(false)
        private set

    /** true quand on est dans une page de jeu : le démarrage automatique est alors désactivé. */
    var inStory = false
        private set

    private val trackIds: List<Int> by lazy {
        try {
            R.raw::class.java.fields
                .filter { it.name.startsWith("music_") }
                .mapNotNull { runCatching { it.getInt(null) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun hasTracks(): Boolean = trackIds.isNotEmpty()

    /** Lance un morceau au hasard (différent du précédent s'il y en a plusieurs). Sans effet si muet ou sans morceau. */
    fun startRandom(context: Context) {
        val app = context.applicationContext
        appContext = app
        val volume = SoundPrefs.get(app).effectiveMusicVolume
        if (volume <= 0f || trackIds.isEmpty()) return
        val candidates = if (trackIds.size > 1) trackIds.filter { it != lastTrack } else trackIds
        val id = candidates.random()
        release()
        try {
            val mp = MediaPlayer.create(app, id) ?: return
            mp.setVolume(volume, volume)
            if (trackIds.size == 1) {
                mp.isLooping = true
            } else {
                mp.setOnCompletionListener { finished ->
                    finished.release()
                    if (player === finished) {
                        player = null
                        isPlaying = false
                        if (!pausedByBackground) startRandom(app)
                    }
                }
            }
            mp.setOnErrorListener { errored, _, _ ->
                errored.release()
                if (player === errored) {
                    player = null
                    isPlaying = false
                }
                true
            }
            player = mp
            lastTrack = id
            pausedByBackground = false
            mp.start()
            isPlaying = true
        } catch (e: Exception) {
            player = null
            isPlaying = false
        }
    }

    /** Démarrage automatique des menus : seulement si rien ne joue et qu'on n'est pas dans une histoire. */
    fun autoStart(context: Context) {
        appContext = context.applicationContext
        if (player == null && !inStory) startRandom(context)
    }

    /**
     * Bouton « Lancer une musique » de la page de jeu : exprime une envie explicite,
     * donc réactive la musique si elle était coupée dans les Réglages.
     */
    fun startFromGameButton(context: Context) {
        val prefs = SoundPrefs.get(context)
        if (prefs.musicMuted) prefs.changeMusicMuted(false)
        if (prefs.musicVolume < 0.05f) prefs.changeMusicVolume(0.5f)
        startRandom(context)
    }

    fun stop() {
        release()
        pausedByBackground = false
        isPlaying = false
    }

    /** Entrée dans une histoire : la musique s'arrête pour ne pas gêner la lecture. */
    fun enterStory() {
        inStory = true
        stop()
    }

    /** Sortie de la page de jeu. La musique ne redémarre pas toute seule. */
    fun leaveStory() {
        inStory = false
    }

    /** Appliqué quand le curseur de volume des Réglages bouge. */
    fun updateVolume(context: Context) {
        val volume = SoundPrefs.get(context).effectiveMusicVolume
        player?.setVolume(volume, volume)
    }

    /** Appliqué quand l'interrupteur « Musique » des Réglages change. */
    fun onMusicMutedChanged(context: Context) {
        if (SoundPrefs.get(context).musicMuted) {
            stop()
        } else if (!inStory && player == null) {
            startRandom(context)
        }
    }

    fun pauseForBackground() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                pausedByBackground = true
            }
        } catch (e: Exception) {
        }
    }

    fun resumeFromBackground() {
        if (!pausedByBackground) return
        pausedByBackground = false
        try {
            player?.start()
        } catch (e: Exception) {
        }
    }

    private fun release() {
        val current = player ?: return
        player = null
        try {
            current.stop()
        } catch (e: Exception) {
        }
        try {
            current.release()
        } catch (e: Exception) {
        }
    }
}

/**
 * À poser UNE fois, tout en haut de l'interface (AppNavigation) : démarre la
 * musique au lancement et la met en pause / la reprend avec l'appli.
 */
@Composable
fun MusicHost() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { MusicPlayer.autoStart(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> MusicPlayer.pauseForBackground()
                Lifecycle.Event.ON_START -> MusicPlayer.resumeFromBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
