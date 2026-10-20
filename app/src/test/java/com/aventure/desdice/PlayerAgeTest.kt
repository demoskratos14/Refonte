package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests des paliers d'âge (AgeTier.kt) : seuils 12 et 18, âge inconnu = palier enfant. */
class PlayerAgeTest {

    @Test
    fun unknownOrInvalidAge_isTheChildTier() {
        assertEquals(AgeTier.CHILD, PlayerAge.tierOf(PlayerAge.UNKNOWN))
        assertEquals(AgeTier.CHILD, PlayerAge.tierOf(-5))
    }

    @Test
    fun tierBoundaries_are12And18() {
        assertEquals(AgeTier.CHILD, PlayerAge.tierOf(3))
        assertEquals(AgeTier.CHILD, PlayerAge.tierOf(6))
        assertEquals(AgeTier.CHILD, PlayerAge.tierOf(11))
        assertEquals(AgeTier.TEEN, PlayerAge.tierOf(12))
        assertEquals(AgeTier.TEEN, PlayerAge.tierOf(14))
        assertEquals(AgeTier.TEEN, PlayerAge.tierOf(17))
        assertEquals(AgeTier.ADULT, PlayerAge.tierOf(18))
        assertEquals(AgeTier.ADULT, PlayerAge.tierOf(45))
    }

    @Test
    fun veryYoungAge_isRaisedToTheMinimum_andHugeAgeIsCapped() {
        assertEquals(PlayerAge.MIN_AGE, PlayerAge.normalize(1))
        assertEquals(PlayerAge.MAX_AGE, PlayerAge.normalize(500))
        assertEquals(AgeTier.ADULT, PlayerAge.tierOf(500))
    }

    @Test
    fun normalize_keepsAnOrdinaryAge_andTurnsNonPositiveIntoUnknown() {
        assertEquals(9, PlayerAge.normalize(9))
        assertEquals(PlayerAge.UNKNOWN, PlayerAge.normalize(0))
        assertEquals(PlayerAge.UNKNOWN, PlayerAge.normalize(-3))
    }

    @Test
    fun parse_readsTypedText_andIgnoresGarbage() {
        assertEquals(14, PlayerAge.parse(" 14 "))
        assertEquals(7, PlayerAge.parse("7"))
        assertEquals(PlayerAge.UNKNOWN, PlayerAge.parse(""))
        assertEquals(PlayerAge.UNKNOWN, PlayerAge.parse("abc"))
        assertEquals(PlayerAge.UNKNOWN, PlayerAge.parse("12 ans"))
    }
}
