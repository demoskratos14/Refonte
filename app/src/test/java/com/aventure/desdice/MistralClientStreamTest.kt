package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Décodage des événements SSE que Mistral envoie quand la réponse est demandée au fil de l'eau. */
class MistralClientStreamTest {

    private fun chunk(delta: String, finish: String? = null): String {
        val finishJson = if (finish == null) "null" else "\"$finish\""
        return """data: {"id":"abc","object":"chat.completion.chunk","model":"m","choices":[{"index":0,"delta":$delta,"finish_reason":$finishJson}]}"""
    }

    @Test
    fun morceauDeTexte_estLu() {
        val c = MistralClient.parseStreamLine(chunk("""{"content":"Bon"}"""))
        assertNotNull(c)
        assertEquals("Bon", c!!.text)
        assertNull(c.finishReason)
        assertFalse(c.done)
        assertNull(c.error)
    }

    @Test
    fun premierEvenement_avecRoleEtContenuVide_nAjouteRien() {
        val c = MistralClient.parseStreamLine(chunk("""{"role":"assistant","content":""}"""))
        assertNotNull(c)
        assertEquals("", c!!.text)
    }

    @Test
    fun accentsEtSautsDeLigne_sontConserves() {
        val c = MistralClient.parseStreamLine(chunk("""{"content":"Écoute…\n\nLa porte s'ouvre"}"""))
        assertEquals("Écoute…\n\nLa porte s'ouvre", c!!.text)
    }

    @Test
    fun finishReason_estRetenu() {
        assertEquals("stop", MistralClient.parseStreamLine(chunk("""{"content":""}""", "stop"))!!.finishReason)
        assertEquals("length", MistralClient.parseStreamLine(chunk("""{"content":"fin"}""", "length"))!!.finishReason)
    }

    @Test
    fun marqueurDeFin_estReconnu_avecOuSansEspace() {
        assertTrue(MistralClient.parseStreamLine("data: [DONE]")!!.done)
        assertTrue(MistralClient.parseStreamLine("data:[DONE]")!!.done)
    }

    @Test
    fun contenuEnListeDeMorceauxTypes_estRecolle() {
        val c = MistralClient.parseStreamLine(
            chunk("""{"content":[{"type":"text","text":"Sal"},{"type":"thinking","text":"x"},{"type":"text","text":"ut"}]}""")
        )
        assertEquals("Salut", c!!.text)
    }

    @Test
    fun contenuNul_donneUnTexteVide() {
        val c = MistralClient.parseStreamLine(chunk("""{"content":null}"""))
        assertEquals("", c!!.text)
    }

    @Test
    fun lignesSansDonnees_sontIgnorees() {
        assertNull(MistralClient.parseStreamLine(""))
        assertNull(MistralClient.parseStreamLine(": keep-alive"))
        assertNull(MistralClient.parseStreamLine("event: message"))
        assertNull(MistralClient.parseStreamLine("data:"))
        assertNull(MistralClient.parseStreamLine("data:   "))
    }

    @Test
    fun evenementIllisible_estIgnore_sansLeverD_exception() {
        assertNull(MistralClient.parseStreamLine("data: {pas du json"))
        assertNull(MistralClient.parseStreamLine("data: 42"))
    }

    @Test
    fun evenementDeStatistiques_sansChoix_estIgnore() {
        assertNull(MistralClient.parseStreamLine("""data: {"id":"abc","choices":[],"usage":{"total_tokens":12}}"""))
        assertNull(MistralClient.parseStreamLine("""data: {"id":"abc","usage":{"total_tokens":12}}"""))
    }

    @Test
    fun erreurEnvoyeeDansLeFlux_estRemontee() {
        val c = MistralClient.parseStreamLine("""data: {"object":"error","message":"Service surchargé","type":"overloaded"}""")
        assertNotNull(c)
        assertEquals("Service surchargé", c!!.error)
    }

    @Test
    fun reponseReconstituee_parConcatenationDesMorceaux() {
        val lines = listOf(
            "data: " + """{"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
            chunk("""{"content":"Il était "}"""),
            "",
            chunk("""{"content":"une fois."}"""),
            chunk("""{"content":""}""", "stop"),
            "data: [DONE]"
        )
        val text = StringBuilder()
        var finish: String? = null
        var done = false
        for (line in lines) {
            val c = MistralClient.parseStreamLine(line) ?: continue
            text.append(c.text)
            if (c.finishReason != null) finish = c.finishReason
            if (c.done) done = true
        }
        assertEquals("Il était une fois.", text.toString())
        assertEquals("stop", finish)
        assertTrue(done)
    }
}
