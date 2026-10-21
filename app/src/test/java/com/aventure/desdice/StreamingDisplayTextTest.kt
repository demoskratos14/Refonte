package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Texte montré au joueur pendant que l'IA écrit encore (voir AiPanel.streamingDisplayText) : rien
 * de technique ne doit apparaître, même une fraction de seconde, et le texte doit ressembler à
 * celui de la réponse terminée pour que le remplacement final ne change rien à l'écran.
 */
class StreamingDisplayTextTest {

    @Test
    fun texteOrdinaire_resteTelQuel() {
        assertEquals("Bonjour\nLa porte grince", streamingDisplayText("Bonjour\nLa porte grince"))
    }

    @Test
    fun espacesDuDebut_sontRetires() {
        assertEquals("Bonjour", streamingDisplayText("  \nBonjour"))
    }

    @Test
    fun baliseDeFinComplete_estMasquee() {
        assertEquals("Fin du chapitre.", streamingDisplayText("Fin du chapitre.\n[[FIN_CHAPITRE]]"))
        assertEquals("Fin.", streamingDisplayText("Fin.\n[[HISTOIRE_TERMINEE]]"))
    }

    @Test
    fun baliseDeFinEnCoursDEcriture_estMasquee() {
        assertEquals("Fin.", streamingDisplayText("Fin.\n["))
        assertEquals("Fin.", streamingDisplayText("Fin.\n[[FIN_CHA"))
    }

    @Test
    fun lignesOptionCompletes_sontMasquees() {
        val raw = "Que fais-tu ?\nOPTION: Ouvrir la porte\nOPTION: Fuir"
        assertEquals("Que fais-tu ?", streamingDisplayText(raw))
    }

    @Test
    fun debutDeLigneOption_estMasque() {
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\nO"))
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\nOPT"))
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\nOPTION"))
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\n**OPTION: Fu"))
    }

    @Test
    fun ligneOptionEnCoursDEcriture_estMasquee() {
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\nOPTION: Ouv"))
        assertEquals(
            "Que fais-tu ?",
            streamingDisplayText("Que fais-tu ?\nOPTION: Ouvrir la porte\nOPTION: Fu")
        )
    }

    @Test
    fun motQuiCommenceCommeOption_maisN_enEstPas_sAffiche() {
        assertEquals("Il y a\nOpération réussie", streamingDisplayText("Il y a\nOpération réussie"))
        assertEquals("Il y a\nOptimiste", streamingDisplayText("Il y a\nOptimiste"))
    }

    @Test
    fun separateurs_sontMasques() {
        assertEquals("Texte", streamingDisplayText("Texte\n-"))
        assertEquals("Texte", streamingDisplayText("Texte\n---"))
        assertEquals("Texte\n\nSuite", streamingDisplayText("Texte\n---\nSuite"))
    }

    @Test
    fun separateurPuisOptions_sontTousMasques() {
        assertEquals("Que fais-tu ?", streamingDisplayText("Que fais-tu ?\n---\nOPTION: A\nOPTION: B"))
    }
}
