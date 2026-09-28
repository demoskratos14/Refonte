package com.aventure.desdice.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Dé de réussite choisi sur la page « Dés classiques » (nombre de faces), mémorisé dans les
 * SharedPreferences `classic_dice_prefs`. État Compose : l'écran se met à jour tout seul, et le
 * choix est retrouvé à la prochaine ouverture de l'appli.
 *
 * 6 faces = dé classique à points (comme avant) ; toute autre valeur affiche un nombre sur le dé.
 */
class ClassicDicePrefs private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var successFaces by mutableIntStateOf(prefs.getInt(KEY_FACES, DEFAULT_FACES).coerceIn(MIN_FACES, MAX_FACES))
        private set

    fun changeSuccessFaces(faces: Int) {
        successFaces = faces.coerceIn(MIN_FACES, MAX_FACES)
        prefs.edit().putInt(KEY_FACES, successFaces).apply()
    }

    companion object {
        const val DEFAULT_FACES = 6
        const val MIN_FACES = 2
        const val MAX_FACES = 999

        /** Dés courants proposés en un tap ; toute autre valeur passe par "Autre". */
        val COMMON_FACES = listOf(4, 6, 8, 10, 12, 20)

        private const val PREFS_NAME = "classic_dice_prefs"
        private const val KEY_FACES = "success_faces"

        @Volatile
        private var instance: ClassicDicePrefs? = null

        fun get(context: Context): ClassicDicePrefs =
            instance ?: synchronized(this) {
                instance ?: ClassicDicePrefs(context).also { instance = it }
            }
    }
}
