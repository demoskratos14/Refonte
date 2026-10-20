package com.aventure.desdice.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aventure.desdice.ui.Audience
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test Compose sur la JVM (Robolectric, sans emulateur) : l'ecran de choix du public
 * affiche bien ses textes et ses trois boutons, et chaque bouton renvoie le bon choix.
 *
 * Limite connue : les polices fantaisie ne sont pas rendues fidelement ici. On detecte
 * donc un texte manquant, un bouton qui disparait ou un clic mal branche, pas un
 * rendu esthetique.
 */
@RunWith(RobolectricTestRunner::class)
class AudienceChoiceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysTitleAndThreeChoices() {
        compose.setContent { AudienceChoiceScreen(onChosen = {}) }

        compose.onNodeWithText("Cette histoire est faite pour", substring = true).assertExists()
        Audience.values().forEach { audience ->
            compose.onNodeWithText(audience.label).assertExists()
        }
    }

    // setContent ne peut etre appele qu'une fois par test : un test par bouton.

    @Test
    fun clickingFilles_returnsFilles() = clickAndExpect(Audience.FILLES)

    @Test
    fun clickingGarcons_returnsGarcons() = clickAndExpect(Audience.GARCONS)

    @Test
    fun clickingDuo_returnsDuo() = clickAndExpect(Audience.DUO)

    private fun clickAndExpect(expected: Audience) {
        val chosen = mutableListOf<Audience>()
        compose.setContent { AudienceChoiceScreen(onChosen = { chosen += it }) }

        compose.onNodeWithText(expected.label).performClick()

        assertEquals(listOf(expected), chosen)
    }
}
