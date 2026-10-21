package com.aventure.desdice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aventure.desdice.ui.SoundPrefs

/** Retours par vibration (permission VIBRATE, accordée automatiquement à l'installation). */
object Haptics {

    /**
     * Deux secousses (courte, puis plus longue) pour souligner un moment de danger : la réponse de
     * l'IA qui suit une explosion de la jauge de menace. Ne fait rien si l'appareil ne vibre pas ;
     * ne plante jamais.
     */
    fun dangerBuzz(context: Context, ignoreSetting: Boolean = false) {
        try {
            if (!ignoreSetting && !SoundPrefs.get(context).vibrationEnabled) return
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val pattern = longArrayOf(0, 120, 90, 260)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
            // Une vibration manquée ne doit jamais gêner la partie.
        }
    }
}
