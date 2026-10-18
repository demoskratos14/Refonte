package com.aventure.desdice

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement d'un secret (la clé API Mistral) avant de l'écrire sur le disque.
 * GameEngine ne connaît que cette interface : les tests unitaires (JVM, sans Keystore)
 * utilisent [None] ou un faux chiffreur.
 */
interface SecretCipher {
    /** Texte chiffré, prêt à être écrit dans un fichier. */
    fun encrypt(plain: String): String

    /** Texte d'origine, ou null si le jeton est illisible (clé de chiffrement perdue, données altérées). */
    fun decrypt(token: String): String?

    /** Aucun chiffrement (tests). */
    object None : SecretCipher {
        override fun encrypt(plain: String): String = plain
        override fun decrypt(token: String): String? = token
    }
}

/**
 * AES-256 en mode GCM, avec une clé qui reste dans l'Android Keystore : elle n'est jamais
 * lisible, ni par l'appli, ni dans une sauvegarde. Si l'appli est restaurée sur un autre
 * téléphone, le jeton ne peut plus être déchiffré : la clé API est alors à ressaisir.
 */
class KeystoreSecretCipher(private val alias: String = "desdice_secret_v1") : SecretCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun doEncrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun encrypt(plain: String): String = try {
        doEncrypt(plain)
    } catch (e: Exception) {
        // Clé du Keystore devenue inutilisable : on la recrée une fois, puis on réessaie.
        keyStore().deleteEntry(alias)
        doEncrypt(plain)
    }

    override fun decrypt(token: String): String? = try {
        val bytes = Base64.decode(token, Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE)
            )
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
