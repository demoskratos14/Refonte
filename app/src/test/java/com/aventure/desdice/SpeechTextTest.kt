package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests du nettoyage du texte de l'IA avant la synthèse vocale (SpeechText.clean). */
class SpeechTextTest {

    @Test
    fun emptyString_staysEmpty() {
        assertEquals("", SpeechText.clean(""))
        assertEquals("", SpeechText.clean("   \n  "))
    }

    @Test
    fun plainText_isUnchanged_accentsAndHyphensIncluded() {
        val text = "Élodie a trouvé l'épée, peut-être même deux."
        assertEquals(text, SpeechText.clean(text))
    }

    @Test
    fun markdownEmphasisAndCodeMarkers_areRemoved_textIsKept() {
        assertEquals("Un dragon gigantesque !", SpeechText.clean("Un **dragon** gigantesque !"))
        assertEquals("Elle lit le mot magique", SpeechText.clean("Elle lit le `mot` *magique*"))
    }

    @Test
    fun headings_loseTheirHashes() {
        assertEquals("Chapitre 1", SpeechText.clean("# Chapitre 1"))
        assertEquals("Le départ", SpeechText.clean("### Le départ"))
    }

    @Test
    fun bullets_loseTheirMarkers() {
        assertEquals("un\ndeux\ntrois", SpeechText.clean("- un\n• deux\n- trois"))
    }

    @Test
    fun emojis_areRemoved_andSpacesCollapsed() {
        assertEquals("Bravo !", SpeechText.clean("Bravo 🎉 !"))
        // symboles divers (✨ ❤️) et drapeau (🇫🇷)
        assertEquals("Merci", SpeechText.clean("✨❤️ Merci 🇫🇷"))
    }

    @Test
    fun zeroWidthJoinerSequences_areRemovedEntirely() {
        val family = "👨‍👩‍👧"
        assertEquals("famille", SpeechText.clean("$family famille"))
    }

    @Test
    fun singleSeparator_isReadAsCallToAction() {
        assertEquals("Texte\nÀ toi !\nSuite", SpeechText.clean("Texte\n---\nSuite"))
    }

    @Test
    fun trailingSeparator_isReadAsCallToAction() {
        assertEquals("Que fais-tu ?\nÀ toi !", SpeechText.clean("Que fais-tu ?\n---"))
    }

    @Test
    fun onlyTheLastSeparator_becomesCallToAction_othersAreDropped() {
        assertEquals("A\n\nB\nÀ toi !\nC", SpeechText.clean("A\n---\nB\n---\nC"))
    }

    @Test
    fun otherSeparatorStyles_areRecognised() {
        assertEquals("Texte\nÀ toi !", SpeechText.clean("Texte\n***"))
        assertEquals("Texte\nÀ toi !", SpeechText.clean("Texte\n_____"))
    }

    @Test
    fun manyBlankLines_areCollapsedToOne() {
        assertEquals("a\n\nb", SpeechText.clean("a\n\n\n\n\nb"))
    }

    @Test
    fun result_isTrimmed() {
        assertEquals("salut", SpeechText.clean("  \n salut \n  "))
    }
}
