package com.aventure.desdice.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test Compose sur la JVM (Robolectric) de l'ecran Reglages : il s'affiche avec son titre et
 * les sections de la premiere page (Polices), la fleche et le bouton retour du telephone
 * appellent onBack, et un glissement change de page.
 *
 * Meme limite que AudienceChoiceScreenTest : on verifie la presence des textes et le
 * comportement, pas le rendu des polices fantaisie.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Vrai si au moins un texte contient [text] (certains libelles apparaissent plusieurs fois). */
    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun displaysTitleAndFontSections() {
        compose.setContent { SettingsScreen(onBack = {}) }

        assertTrue(hasText("Réglages"))
        assertTrue(hasText("Titres"))
        assertTrue(hasText("Texte courant"))
        assertTrue(hasText("Réponses de l'IA"))
    }

    @Test
    fun clickingBackArrow_callsOnBack() {
        var backCount = 0
        compose.setContent { SettingsScreen(onBack = { backCount++ }) }

        compose.onNodeWithText("\u2190").performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun phoneBackButton_callsOnBack() {
        var backCount = 0
        compose.setContent { SettingsScreen(onBack = { backCount++ }) }

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, backCount)
    }

    @Test
    fun swipingLeft_showsPhotosPage() {
        compose.setContent { SettingsScreen(onBack = {}) }
        assertFalse(hasText("Images de fond"))

        // Sur toute la racine : le geste doit passer dans la zone du pager, pas dans l'en-tete.
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertTrue(hasText("Images de fond"))
    }
}
