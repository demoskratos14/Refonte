package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests du texte du bilan de fin d'histoire. */
class BilanDialogTest {

    @Test
    fun bilanCountText_zeroAndOneAreSingular() {
        assertEquals("0 lancer", bilanCountText(0, "lancer", "lancers"))
        assertEquals("1 lancer", bilanCountText(1, "lancer", "lancers"))
    }

    @Test
    fun bilanCountText_twoOrMoreArePlural() {
        assertEquals("2 lancers", bilanCountText(2, "lancer", "lancers"))
        assertEquals("37 lancers", bilanCountText(37, "lancer", "lancers"))
    }
}
