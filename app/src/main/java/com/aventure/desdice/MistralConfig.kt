// MistralConfig.kt
// Configuration Mistral (clé API et modèle), stockée dans app_config.json.
//
// La clé n'est jamais écrite en clair : le fichier ne contient que son jeton
// chiffré (CONFIG_KEY_TOKEN), produit par SecretCipher (KeystoreSecretCipher en
// production, SecretCipher.None par défaut dans les tests unitaires). Les anciens
// fichiers à clé en clair sont relus puis réécrits chiffrés.
//
// Extrait de GameEngine.kt ; GameEngine garde les mêmes méthodes publiques
// (getMistralKey, setMistralKey, clearMistralKey, hasMistralKey, getMistralModel,
// setMistralModel, getConfigScreenState) et leur délègue simplement l'appel.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Champ d'app_config.json qui contient la clé API chiffrée. */
private const val CONFIG_KEY_TOKEN = "mistral_api_key_enc"

/**
 * @param secretCipher chiffre la clé API Mistral dans app_config.json.
 */
class MistralConfig(
    baseDir: File,
    private val secretCipher: SecretCipher = SecretCipher.None
) {

    private val appConfigFile = File(baseDir, "app_config.json")
    // Même constante que le client, pour rester aligné sur la liste de modèles
    // proposée dans ConfigureKeyScreen.
    private val defaultMistralModel = MistralClient.DEFAULT_MODEL

    // Le fichier ne contient jamais la clé en clair : seulement son jeton chiffré
    // (CONFIG_KEY_TOKEN). En mémoire, la config garde la clé déchiffrée sous "mistral_api_key".
    private fun configOf(key: String, model: String) =
        JSONObject().apply { put("mistral_api_key", key); put("mistral_model", model) }

    private fun loadAppConfig(): JSONObject {
        if (!appConfigFile.exists()) return configOf("", defaultMistralModel)
        return try {
            val data = JSONObject(appConfigFile.readText())
            val model = data.optString("mistral_model", defaultMistralModel).ifEmpty { defaultMistralModel }
            val token = data.optString(CONFIG_KEY_TOKEN, "")
            // Ancien format : clé en clair. On la lit puis on la réécrit chiffrée.
            val legacyKey = data.optString("mistral_api_key", "")
            if (token.isNotEmpty()) {
                // Jeton illisible (clé du Keystore perdue) : comme si aucune clé n'était configurée.
                configOf(secretCipher.decrypt(token).orEmpty(), model)
            } else {
                configOf(legacyKey, model).also { config ->
                    if (legacyKey.isNotEmpty()) {
                        try { saveAppConfig(config) } catch (e: Exception) { /* réessayé au prochain chargement */ }
                    }
                }
            }
        } catch (e: Exception) {
            configOf("", defaultMistralModel)
        }
    }

    private fun saveAppConfig(config: JSONObject) {
        val key = config.optString("mistral_api_key", "")
        val onDisk = JSONObject().apply {
            put(CONFIG_KEY_TOKEN, if (key.isEmpty()) "" else secretCipher.encrypt(key))
            put("mistral_model", config.optString("mistral_model", defaultMistralModel))
        }
        appConfigFile.writeText(onDisk.toString(2))
    }

    fun getMistralKey(): String = loadAppConfig().optString("mistral_api_key", "")

    fun setMistralKey(key: String) {
        val config = loadAppConfig()
        config.put("mistral_api_key", key.trim())
        saveAppConfig(config)
    }

    fun clearMistralKey() = setMistralKey("")

    fun hasMistralKey(): Boolean = getMistralKey().isNotEmpty()

    fun getMistralModel(): String = loadAppConfig().optString("mistral_model", defaultMistralModel)

    fun setMistralModel(model: String) {
        val config = loadAppConfig()
        config.put("mistral_model", model.trim().ifEmpty { defaultMistralModel })
        saveAppConfig(config)
    }

    fun getConfigScreenState(): JSONObject {
        val key = getMistralKey()
        val maskedKey = if (key.isNotEmpty()) "*".repeat(maxOf(0, key.length - 4)) + key.takeLast(4) else ""
        return JSONObject().apply {
            put("has_key", hasMistralKey())
            put("masked_key", maskedKey)
            put("model", getMistralModel())
            put(
                "model_choices",
                JSONArray(MistralClient.MODEL_CHOICES.map { (value, label) -> JSONArray(listOf(value, label)) })
            )
        }
    }
}
