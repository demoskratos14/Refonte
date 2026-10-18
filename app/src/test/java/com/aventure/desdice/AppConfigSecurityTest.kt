package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * La clé Mistral ne doit jamais être écrite en clair dans app_config.json.
 * Un faux chiffreur remplace le Keystore Android (indisponible sur la JVM de test).
 */
class AppConfigSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val configFile get() = File(dir, "app_config.json")

    /** "Chiffre" en inversant le texte ; ne sait déchiffrer que ses propres jetons. */
    private class FakeCipher(var readable: Boolean = true) : SecretCipher {
        override fun encrypt(plain: String): String = "enc:" + plain.reversed()
        override fun decrypt(token: String): String? =
            if (readable && token.startsWith("enc:")) token.removePrefix("enc:").reversed() else null
    }

    @Before
    fun setUp() {
        dir = tmp.newFolder("data")
    }

    @Test
    fun key_isNeverWrittenInClear_andSurvivesReload() {
        val cipher = FakeCipher()
        GameEngine(dir, cipher).setMistralKey("sk-secret-1234")

        assertFalse(configFile.readText().contains("sk-secret-1234"))
        assertEquals("sk-secret-1234", GameEngine(dir, cipher).getMistralKey())
    }

    @Test
    fun legacyClearKey_isReadThenRewrittenEncrypted() {
        configFile.writeText("""{"mistral_api_key":"ancienne-cle","mistral_model":"mistral-large-latest"}""")
        val cipher = FakeCipher()

        val engine = GameEngine(dir, cipher)
        assertEquals("ancienne-cle", engine.getMistralKey())
        assertEquals("mistral-large-latest", engine.getMistralModel())

        assertFalse(configFile.readText().contains("ancienne-cle"))
        assertEquals("ancienne-cle", GameEngine(dir, cipher).getMistralKey())
        assertEquals("mistral-large-latest", GameEngine(dir, cipher).getMistralModel())
    }

    @Test
    fun unreadableToken_meansNoKey_butKeepsTheModel() {
        val cipher = FakeCipher()
        GameEngine(dir, cipher).apply {
            setMistralKey("sk-secret-1234")
            setMistralModel("mistral-medium-latest")
        }

        cipher.readable = false // ex. : restauration sur un autre téléphone
        val engine = GameEngine(dir, cipher)
        assertEquals("", engine.getMistralKey())
        assertFalse(engine.hasMistralKey())
        assertEquals("mistral-medium-latest", engine.getMistralModel())
    }

    @Test
    fun clearKey_leavesNothingOnDisk() {
        val cipher = FakeCipher()
        val engine = GameEngine(dir, cipher)
        engine.setMistralKey("sk-secret-1234")
        engine.clearMistralKey()

        assertFalse(GameEngine(dir, cipher).hasMistralKey())
        assertFalse(configFile.readText().contains("enc:"))
    }

    @Test
    fun changingTheModel_doesNotLoseTheKey() {
        val cipher = FakeCipher()
        val engine = GameEngine(dir, cipher)
        engine.setMistralKey("sk-secret-1234")
        engine.setMistralModel("mistral-medium-latest")

        assertTrue(GameEngine(dir, cipher).hasMistralKey())
        assertEquals("sk-secret-1234", GameEngine(dir, cipher).getMistralKey())
    }
}
