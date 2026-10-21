package com.aventure.desdice.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aventure.desdice.R
import androidx.core.content.edit

/**
 * Réglages du bruit des dés (Réglages > page « Sons »), mémorisés dans les
 * SharedPreferences `app_sound_prefs`.
 *
 * Les deux valeurs sont des états Compose : l'écran Réglages se met à jour tout
 * seul, et tout code qui lit `effectiveVolume` juste avant de jouer le son voit
 * toujours la valeur à jour, sans redémarrage.
 */
class SoundPrefs private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Volume choisi, de 0.0 (silence) à 1.0 (plein volume). Conservé même quand le son est coupé. */
    var diceVolume by mutableFloatStateOf(prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f))
        private set

    /** true = bruit des dés coupé (le volume choisi est conservé pour la réactivation). */
    var diceMuted by mutableStateOf(prefs.getBoolean(KEY_MUTED, false))
        private set

    /** Volume de la musique de fond, de 0.0 à 1.0 (plus discret par défaut que les dés). */
    var musicVolume by mutableFloatStateOf(prefs.getFloat(KEY_MUSIC_VOLUME, 0.5f).coerceIn(0f, 1f))
        private set

    /** true = musique de fond coupée. */
    var musicMuted by mutableStateOf(prefs.getBoolean(KEY_MUSIC_MUTED, false))
        private set

    /** true = le téléphone vibre quand la jauge de menace explose (Réglages > Sons). */
    var vibrationEnabled by mutableStateOf(prefs.getBoolean(KEY_VIBRATION, true))
        private set

    fun changeVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        prefs.edit { putBoolean(KEY_VIBRATION, enabled) }
    }

    /** true = animation d'explosion plein écran quand la jauge de menace explose (Réglages > Sons). */
    var explosionAnimationEnabled by mutableStateOf(prefs.getBoolean(KEY_EXPLOSION_ANIM, true))
        private set

    fun changeExplosionAnimationEnabled(enabled: Boolean) {
        explosionAnimationEnabled = enabled
        prefs.edit { putBoolean(KEY_EXPLOSION_ANIM, enabled) }
    }

    val effectiveMusicVolume: Float
        get() = if (musicMuted) 0f else musicVolume

    fun changeMusicVolume(value: Float) {
        musicVolume = value.coerceIn(0f, 1f)
        prefs.edit { putFloat(KEY_MUSIC_VOLUME, musicVolume) }
    }

    fun changeMusicMuted(muted: Boolean) {
        musicMuted = muted
        prefs.edit { putBoolean(KEY_MUSIC_MUTED, muted) }
    }

    /** Volume à appliquer au lecteur : 0 si le son est coupé, sinon le volume choisi. */
    val effectiveVolume: Float
        get() = if (diceMuted) 0f else diceVolume

    fun setVolume(value: Float) {
        diceVolume = value.coerceIn(0f, 1f)
        prefs.edit { putFloat(KEY_VOLUME, diceVolume) }
    }

    fun setMuted(muted: Boolean) {
        diceMuted = muted
        prefs.edit { putBoolean(KEY_MUTED, muted) }
    }

    // ------------------------------------------------------------------
    // Morceaux de musique activés/désactivés pour le tirage au sort (Réglages > Sons,
    // MusicCard). Un morceau non listé ici est considéré activé -- décocher tout
    // reviendrait simplement à ce qu'aucun morceau ne soit tiré (voir
    // MusicPlayer.startRandom), sans rapport avec l'interrupteur "Musique" lui-même.
    // ------------------------------------------------------------------

    /** Noms de ressource (ex. "music_fantasy_epic") des morceaux décochés par l'utilisateur. */
    var disabledMusicTracks: Set<String> by mutableStateOf(
        prefs.getStringSet(KEY_DISABLED_TRACKS, emptySet())?.toSet() ?: emptySet()
    )
        private set

    fun isTrackEnabled(name: String): Boolean = name !in disabledMusicTracks

    fun setTrackEnabled(name: String, enabled: Boolean) {
        disabledMusicTracks = if (enabled) disabledMusicTracks - name else disabledMusicTracks + name
        prefs.edit { putStringSet(KEY_DISABLED_TRACKS, disabledMusicTracks) }
    }

    companion object {
        private const val PREFS_NAME = "app_sound_prefs"
        private const val KEY_VOLUME = "dice_volume"
        private const val KEY_MUTED = "dice_muted"
        private const val KEY_MUSIC_VOLUME = "music_volume"
        private const val KEY_MUSIC_MUTED = "music_muted"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_EXPLOSION_ANIM = "explosion_animation_enabled"
        private const val KEY_DISABLED_TRACKS = "disabled_music_tracks"

        @Volatile
        private var instance: SoundPrefs? = null

        fun get(context: Context): SoundPrefs =
            instance ?: synchronized(this) {
                instance ?: SoundPrefs(context).also { instance = it }
            }
    }
}

/**
 * Joue le bruit des dés (res/raw/dice_roll.mp3) au volume des Réglages.
 * Ne joue rien si le son est coupé ou si le volume est à zéro. Un nouveau
 * lancer interrompt le son précédent au lieu de les superposer.
 */
object DiceSoundPlayer {

    private var player: MediaPlayer? = null

    fun play(context: Context) {
        val volume = SoundPrefs.get(context).effectiveVolume
        if (volume <= 0f) return
        stop()
        try {
            val mp = MediaPlayer.create(context.applicationContext, R.raw.dice_roll) ?: return
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            mp.setOnErrorListener { errored, _, _ ->
                errored.release()
                if (player === errored) player = null
                true
            }
            player = mp
            mp.start()
        } catch (e: Exception) {
            player = null
        }
    }

    fun stop() {
        val current = player ?: return
        player = null
        try {
            current.stop()
        } catch (e: Exception) {
            // déjà arrêté : rien à faire
        }
        try {
            current.release()
        } catch (e: Exception) {
        }
    }
}
