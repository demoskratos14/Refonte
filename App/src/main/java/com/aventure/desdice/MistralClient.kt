// MistralClient.kt
// Portage Kotlin de mistral_client.py.
//
// Fonction volontairement synchrone (comme la fonction Python) : elle est
// toujours appelée depuis GameEngine à l'intérieur d'un
// viewModelScope.launch(Dispatchers.IO) côté GameViewModel, jamais
// directement sur le thread principal. Utilise HttpURLConnection (aucune
// dépendance externe), comme urllib côté Python.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object MistralClient {

    private const val API_URL = "https://api.mistral.ai/v1/chat/completions"
    const val DEFAULT_MODEL = "mistral-small-2603"
    private const val DEFAULT_TIMEOUT_MS = 40_000
    private const val DEFAULT_MAX_TOKENS = 1400

    /** (identifiant exact pour l'API, libellé affiché) — miroir de MODEL_CHOICES. */
    val MODEL_CHOICES: List<Pair<String, String>> = listOf(
        "ministral-8b-2512" to "Ministral 8B — très économique, style plus simple",
        "mistral-small-2603" to "Mistral Small — rapide et économique (recommandé)",
        "mistral-medium-latest" to "Mistral Medium — histoires plus riches, un peu plus cher"
    )

    /**
     * Envoie une conversation à l'API Mistral. Ne lève jamais d'exception :
     * toute erreur réseau/HTTP/format est convertie en message clair.
     * @return (texte, erreur) — succès : (texte, null) ; échec : (null, message lisible).
     */
    fun chat(
        apiKey: String,
        messages: List<AiMessage>,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        promptCacheKey: String? = null
    ): Pair<String?, String?> {
        if (apiKey.isEmpty()) return null to "Aucune clé API Mistral configurée."
        if (messages.isEmpty()) return null to "Rien à envoyer à l'IA."

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray(messages.map { it.toJson() }))
            put("temperature", 0.9)
            put("max_tokens", maxTokens)
            if (!promptCacheKey.isNullOrEmpty()) put("prompt_cache_key", promptCacheKey)
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code in 200..299) {
                val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseChatResponse(raw)
            } else {
                val errorRaw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                val detailMsg = parseErrorDetail(errorRaw) ?: (connection.responseMessage ?: "erreur inconnue")
                when (code) {
                    401 -> null to "Clé API Mistral refusée (401) : vérifie qu'elle est correcte."
                    429 -> null to (
                        "Limite Mistral atteinte (429) : $detailMsg " +
                            "Réessaie dans un instant ou vérifie ton plan sur console.mistral.ai."
                        )
                    else -> null to "Erreur Mistral ($code) : $detailMsg"
                }
            }
        } catch (e: SocketTimeoutException) {
            null to "Impossible de joindre l'API Mistral (délai dépassé) : ${e.message}"
        } catch (e: IOException) {
            null to "Impossible de joindre l'API Mistral (réseau ?) : ${e.message}"
        } catch (e: Exception) {
            null to "Erreur inattendue en contactant Mistral : ${e.message}"
        } finally {
            connection?.disconnect()
        }
    }

    /** Certaines erreurs Mistral imbriquent le message utile dans un sous-objet {"error": {"message": ...}}. */
    private fun parseErrorDetail(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            when (val msg = obj.opt("message") ?: obj.opt("error")) {
                is JSONObject -> msg.optString("message", msg.toString())
                null -> raw
                else -> msg.toString()
            }
        } catch (e: Exception) {
            raw
        }
    }

    private fun parseChatResponse(raw: String): Pair<String?, String?> {
        return try {
            val data = JSONObject(raw)
            val text = data.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .optString("content", "").trim()
            if (text.isEmpty()) null to "Réponse vide renvoyée par l'API Mistral." else text to null
        } catch (e: Exception) {
            null to "Réponse de l'API Mistral illisible (format inattendu)."
        }
    }
}
