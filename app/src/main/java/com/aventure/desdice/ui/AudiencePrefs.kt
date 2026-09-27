package com.aventure.desdice.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Public visé par l'appli : filles, garçons, ou les deux. Choisi au tout premier
 * démarrage (voir AudienceChoiceScreen dans le package screens) et modifiable ensuite
 * depuis Réglages > Profil (ProfileCard dans SettingsScreen.kt).
 *
 * Détermine :
 *  - quelles icônes sont proposées dans Réglages > Icône (voir AppIconStore.iconsFor) ;
 *  - le fond par défaut de la page « Nouvelle histoire » du carrousel, tant qu'aucune
 *    image personnalisée n'a été choisie (voir rememberBackgroundPainter dans
 *    BackgroundStore.kt, cas de BackgroundSlot.NEW_STORY).
 */
enum class Audience(val prefValue: String, val label: String) {
    FILLES("filles", "Filles"),
    GARCONS("garcons", "Garçons"),
    DUO("mixte", "Les 2")
}

/**
 * Choix persisté dans les SharedPreferences. `audience == null` tant que rien n'a
 * encore été choisi (avant le tout premier écran) -- `hasChosen` sert justement à
 * détecter ce cas dans AppNavigation (MainActivity.kt).
 */
class AudiencePrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var audience: Audience? by mutableStateOf(
        prefs.getString(KEY_AUDIENCE, null)?.let { stored ->
            Audience.values().firstOrNull { it.prefValue == stored }
        }
    )
        private set

    val hasChosen: Boolean get() = audience != null

    fun updateAudience(value: Audience) {
        audience = value
        prefs.edit().putString(KEY_AUDIENCE, value.prefValue).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_audience_prefs"
        private const val KEY_AUDIENCE = "audience"

        @Volatile
        private var instance: AudiencePrefs? = null

        fun get(context: Context): AudiencePrefs =
            instance ?: synchronized(this) {
                instance ?: AudiencePrefs(context.applicationContext).also { instance = it }
            }
    }
}
