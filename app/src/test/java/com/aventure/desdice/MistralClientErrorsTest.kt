package com.aventure.desdice

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages d'erreur affichés au joueur quand Mistral est injoignable ou refuse la clé. */
class MistralClientErrorsTest {

    @Test
    fun cleRefusee401_estReconnueCommeProblemeDeCle() {
        val msg = MistralClient.httpErrorMessage(401, "Unauthorized")
        assertTrue(msg.contains("401"))
        assertTrue(MistralClient.isKeyRefusedMessage(msg))
    }

    @Test
    fun acces403_estReconnuCommeProblemeDeCle() {
        val msg = MistralClient.httpErrorMessage(403, "Forbidden")
        assertTrue(MistralClient.isKeyRefusedMessage(msg))
    }

    @Test
    fun limite429_restePropose_sansRaccourciCle() {
        val msg = MistralClient.httpErrorMessage(429, "Too many requests.")
        assertTrue(msg.contains("429"))
        assertFalse(MistralClient.isKeyRefusedMessage(msg))
    }

    @Test
    fun panneServeur5xx_nAccuseJamaisLaCle() {
        for (code in listOf(500, 502, 503)) {
            val msg = MistralClient.httpErrorMessage(code, "boom")
            assertTrue(msg.contains("indisponible"))
            assertFalse(MistralClient.isKeyRefusedMessage(msg))
        }
    }

    @Test
    fun autreCodeHttp_garde_leDetailDuServeur() {
        val msg = MistralClient.httpErrorMessage(400, "Invalid model")
        assertTrue(msg.contains("400"))
        assertTrue(msg.contains("Invalid model"))
        assertFalse(MistralClient.isKeyRefusedMessage(msg))
    }

    @Test
    fun reseauCoupe_parleDeConnexionInternet() {
        assertTrue(MistralClient.networkErrorMessage(UnknownHostException("api.mistral.ai")).contains("Internet"))
        assertTrue(MistralClient.networkErrorMessage(ConnectException("refused")).contains("Internet"))
    }

    @Test
    fun delaiDepasse_estDistinctDuReseauCoupe() {
        val msg = MistralClient.networkErrorMessage(SocketTimeoutException("timeout"))
        assertTrue(msg.contains("trop de temps"))
    }

    @Test
    fun autreErreurIO_etErreurInattendue_ontUnMessage() {
        assertTrue(MistralClient.networkErrorMessage(IOException("reset")).contains("interrompue"))
        assertTrue(MistralClient.networkErrorMessage(IOException()).contains("interrompue"))
        assertTrue(MistralClient.networkErrorMessage(IllegalStateException("x")).contains("inattendue"))
    }
}
