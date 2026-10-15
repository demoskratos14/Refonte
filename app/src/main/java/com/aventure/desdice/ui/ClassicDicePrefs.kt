package com.aventure.desdice.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit

/**
 * Plateau de dés de la page « Dés classiques » : la liste des dés que l'on veut lancer, dans
 * l'ordre, mémorisée dans les SharedPreferences `classic_dice_prefs`. État Compose : l'écran se met
 * à jour tout seul, et le plateau est retrouvé à la prochaine ouverture de l'appli.
 *
 * Chaque élément de [dice] est soit un nombre de faces (6 = dé classique à points, toute autre
 * valeur affiche un nombre sur le dé), soit [FATE_DIE] (le dé du destin). Le dé du destin n'est
 * donc plus imposé : on l'ajoute, ou non, comme n'importe quel autre dé.
 */
class ClassicDicePrefs private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val dice = mutableStateListOf<Int>().apply { addAll(load()) }

    private fun load(): List<Int> {
        val saved = prefs.getString(KEY_DICE, null)
        if (saved != null) {
            return saved.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { isValid(it) }
                .take(MAX_DICE)
        }
        // Aucune liste enregistrée : on reprend l'ancien réglage à un seul dé (dé de réussite +
        // dé du destin), s'il existe.
        val legacyFaces = prefs.getInt(KEY_LEGACY_FACES, DEFAULT_FACES).coerceIn(MIN_FACES, MAX_FACES)
        return listOf(legacyFaces, FATE_DIE)
    }

    private fun save() {
        prefs.edit { putString(KEY_DICE, dice.joinToString(",")) }
    }

    private fun isValid(d: Int) = d == FATE_DIE || d in MIN_FACES..MAX_FACES

    /** Ajoute un dé au plateau ; renvoie false s'il est invalide ou si le plateau est plein. */
    fun addDie(d: Int): Boolean {
        if (!isValid(d) || dice.size >= MAX_DICE) return false
        dice.add(d)
        save()
        return true
    }

    fun removeDieAt(index: Int) {
        if (index !in dice.indices) return
        dice.removeAt(index)
        save()
    }

    companion object {
        /** Valeur qui désigne le dé du destin dans la liste des dés. */
        const val FATE_DIE = 0
        const val DEFAULT_FACES = 6
        const val MIN_FACES = 2
        const val MAX_FACES = 999
        const val MAX_DICE = 8

        /** Dés courants proposés en un tap ; toute autre valeur passe par "Autre". */
        val COMMON_FACES = listOf(4, 6, 8, 10, 12, 20)

        private const val PREFS_NAME = "classic_dice_prefs"
        private const val KEY_DICE = "dice_list"
        private const val KEY_LEGACY_FACES = "success_faces"

        @Volatile
        private var instance: ClassicDicePrefs? = null

        fun get(context: Context): ClassicDicePrefs =
            instance ?: synchronized(this) {
                instance ?: ClassicDicePrefs(context).also { instance = it }
            }
    }
}
