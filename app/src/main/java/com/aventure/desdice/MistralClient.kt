// MistralClient.kt
// Client HTTP de l'API Mistral (IA narratrice).
//
// Fonction volontairement synchrone : elle est
// toujours appelée depuis GameEngine à l'intérieur d'un
// viewModelScope.launch(Dispatchers.IO) côté GameViewModel, jamais
// directement sur le thread principal. Utilise HttpURLConnection (aucune
// dépendance externe).
//
// Réponses coupées : si Mistral s'arrête parce que max_tokens est atteint
// (finish_reason = "length"), chat() relance automatiquement l'IA pour qu'elle
// termine son texte (jusqu'à MAX_CONTINUATIONS fois) et recolle les morceaux.
// Le reste de l'appli ne voit donc qu'une réponse complète (fin de chapitre,
// lignes "OPTION:", balise de fin, résumés du journal...).
//
// Réessai automatique : un appel qui échoue sur une erreur réseau (coupure, délai dépassé...)
// ou sur une limite de débit (429) est retenté jusqu'à MAX_ATTEMPTS fois, avec une attente
// croissante (ou celle demandée par l'en-tête Retry-After, si elle reste raisonnable).
// Les autres erreurs (clé refusée, modèle invalide, panne 5xx...) ne sont jamais retentées.
// Si tous les essais échouent, le message d'erreur est renvoyé tel quel : c'est à
// l'interface de proposer un bouton « Réessayer » (voir GameEngine.retryAiRequest).

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

object MistralClient {

    private const val API_URL = "https://api.mistral.ai/v1/chat/completions"
    const val DEFAULT_MODEL = "mistral-small-2603"
    private const val DEFAULT_TIMEOUT_MS = 40_000
    private const val DEFAULT_MAX_TOKENS = 1400

    /** Nombre maximum de relances pour terminer une réponse coupée (garde-fou de coût). */
    private const val MAX_CONTINUATIONS = 3

    /** Nombre maximum d'essais pour un même appel (le premier compris) sur erreur réseau ou 429. */
    private const val MAX_ATTEMPTS = 3

    /** Après un délai dépassé, un seul réessai : chaque essai peut déjà durer [DEFAULT_TIMEOUT_MS]. */
    private const val MAX_ATTEMPTS_AFTER_TIMEOUT = 2

    /** Attente avant le 2e essai ; doublée à chaque essai suivant. */
    private const val BASE_RETRY_DELAY_MS = 1_500L

    /** Au-delà, on n'attend pas : l'erreur est rendue au joueur, qui réessaiera à son rythme. */
    private const val MAX_RETRY_WAIT_MS = 10_000L

    /** Pause entre deux essais ; remplaçable dans les tests pour ne pas attendre vraiment. */
    internal var sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) }

    private const val CONTINUE_PROMPT =
        "Ta réponse précédente a été coupée par une limite de longueur. Continue EXACTEMENT " +
            "là où tu t'es arrêté(e), sans rien répéter ni reformuler ce qui précède, " +
            "sans introduction, et termine proprement (en respectant les consignes de fin " +
            "déjà données : lignes OPTION, balise finale, etc.)."

    /** (identifiant exact pour l'API, libellé affiché). */
    val MODEL_CHOICES: List<Pair<String, String>> = listOf(
        "ministral-8b-2512" to "Ministral 8B — très économique, style plus simple",
        "mistral-small-2603" to "Mistral Small — rapide et économique (recommandé)",
        "mistral-medium-latest" to "Mistral Medium — histoires plus riches, un peu plus cher",
        "mistral-large-latest" to "Mistral Large — le plus capable, et moins cher que Medium"
    )

    /**
     * Identifiants de modèles refusés par Mistral (erreur 400 "Invalid model"), qui peuvent
     * rester enregistrés dans les réglages -> modèle par défaut à la place.
     */
    private val LEGACY_MODEL_IDS = mapOf(
        "mistral-small-creative-2512" to DEFAULT_MODEL,
        "labs-mistral-small-creative" to DEFAULT_MODEL
    )

    /** Résultat brut d'un seul appel HTTP. */
    private data class RawReply(
        val text: String?,
        val truncated: Boolean,
        val error: String?,
        /** true : l'échec vaut la peine d'être retenté (erreur réseau ou 429). */
        val retryable: Boolean = false,
        /** Attente demandée par le serveur (en-tête Retry-After), 0 si aucune. */
        val retryAfterMs: Long = 0L,
        /** true : l'échec est un délai dépassé (réessayé moins souvent, voir MAX_ATTEMPTS_AFTER_TIMEOUT). */
        val timedOut: Boolean = false
    )

    /**
     * Envoie une conversation à l'API Mistral. Ne lève jamais d'exception :
     * toute erreur réseau/HTTP/format est convertie en message clair.
     * Si la réponse est coupée par la limite de longueur, elle est complétée
     * automatiquement par des appels de continuation. Une erreur réseau ou un 429 est
     * retenté automatiquement (voir singleCallWithRetry).
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

        val first = singleCallWithRetry(apiKey, messages, model, maxTokens, timeoutMs, promptCacheKey)
        if (first.error != null) return null to first.error
        var full = first.text.orEmpty()
        var truncated = first.truncated

        var attempts = 0
        while (truncated && attempts < MAX_CONTINUATIONS) {
            attempts++
            val followUp = messages +
                AiMessage("assistant", full) +
                AiMessage("user", CONTINUE_PROMPT)
            val next = singleCallWithRetry(apiKey, followUp, model, maxTokens, timeoutMs, promptCacheKey)
            if (next.error != null) {
                // On garde ce qu'on a déjà plutôt que de tout perdre : mieux vaut un texte
                // un peu court qu'une erreur alors que 95 % de la réponse est là.
                break
            }
            full += next.text.orEmpty()
            truncated = next.truncated
        }

        val clean = full.trim()
        return if (clean.isEmpty()) null to "Réponse vide renvoyée par l'API Mistral." else clean to null
    }

    /**
     * singleCall() avec réessai automatique sur erreur réseau ou 429. Les autres erreurs sont
     * rendues immédiatement. Ne lève jamais d'exception.
     */
    private fun singleCallWithRetry(
        apiKey: String,
        messages: List<AiMessage>,
        model: String,
        maxTokens: Int,
        timeoutMs: Int,
        promptCacheKey: String?
    ): RawReply {
        var attempt = 1
        while (true) {
            val reply = singleCall(apiKey, messages, model, maxTokens, timeoutMs, promptCacheKey)
            if (!reply.retryable) return reply
            val maxAttempts = if (reply.timedOut) MAX_ATTEMPTS_AFTER_TIMEOUT else MAX_ATTEMPTS
            val wait = retryDelayMs(attempt, reply.retryAfterMs)
            if (attempt >= maxAttempts || wait < 0L || !pause(wait)) {
                return if (attempt > 1) {
                    reply.copy(error = reply.error + " ($attempt essais effectués.)")
                } else {
                    reply
                }
            }
            attempt++
        }
    }

    /** Attend [ms] millisecondes ; false si le thread a été interrompu (on abandonne alors les essais). */
    private fun pause(ms: Long): Boolean = try {
        sleeper(ms)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Seule la limite de débit (429) est retentée côté HTTP ; les autres codes sont définitifs. */
    internal fun isRetryableHttpCode(code: Int): Boolean = code == 429

    /** Les erreurs réseau (coupure, DNS, délai dépassé, connexion interrompue...) sont toutes des IOException. */
    internal fun isRetryableException(e: Exception): Boolean = e is IOException

    /**
     * Attente avant l'essai suivant. [attempt] : numéro de l'essai qui vient d'échouer (à partir de 1).
     * Attente croissante (1,5 s, 3 s...), au moins égale à celle demandée par le serveur ([retryAfterMs]).
     * @return la durée en ms, ou -1 si elle dépasserait MAX_RETRY_WAIT_MS (on renonce alors à réessayer).
     */
    internal fun retryDelayMs(attempt: Int, retryAfterMs: Long): Long {
        val backoff = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 10))
        val wait = maxOf(backoff, retryAfterMs)
        return if (wait > MAX_RETRY_WAIT_MS) -1L else wait
    }

    /** Valeur de l'en-tête Retry-After (en secondes) convertie en ms ; 0 si absente ou illisible. */
    internal fun parseRetryAfterMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return 0L
        return if (seconds <= 0L) 0L else minOf(seconds, 3_600L) * 1_000L
    }

    /** Un seul appel HTTP à l'API. Ne lève jamais d'exception. */
    private fun singleCall(
        apiKey: String,
        messages: List<AiMessage>,
        model: String,
        maxTokens: Int,
        timeoutMs: Int,
        promptCacheKey: String?
    ): RawReply {
        val payload = JSONObject().apply {
            put("model", LEGACY_MODEL_IDS[model] ?: model)
            put("messages", JSONArray(messages.map { it.toApiJson() }))
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
                RawReply(
                    null, false, httpErrorMessage(code, detailMsg),
                    retryable = isRetryableHttpCode(code),
                    retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                )
            }
        } catch (e: Exception) {
            RawReply(
                null, false, networkErrorMessage(e),
                retryable = isRetryableException(e),
                timedOut = e is SocketTimeoutException
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Message lisible pour un code HTTP d'erreur renvoyé par Mistral. */
    internal fun httpErrorMessage(code: Int, detail: String): String = when (code) {
        401 ->
            "Clé API Mistral refusée (401) : elle est incorrecte, expirée ou révoquée. " +
                "Vérifie-la ou remplace-la."
        403 ->
            "Accès refusé par Mistral (403) : ta clé n'a pas le droit d'utiliser ce modèle " +
                "ou ce service. Vérifie ta clé ou choisis un autre modèle."
        429 ->
            "Limite Mistral atteinte (429) : $detail " +
                "Réessaie dans un instant ou vérifie ton plan sur console.mistral.ai."
        in 500..599 ->
            "Le service Mistral est momentanément indisponible ($code). " +
                "Ce n'est pas lié à ta clé : réessaie dans quelques instants."
        else -> "Erreur Mistral ($code) : $detail"
    }

    /** Message lisible pour une exception levée pendant l'appel (réseau coupé, délai...). */
    internal fun networkErrorMessage(e: Exception): String = when (e) {
        is SocketTimeoutException ->
            "Mistral met trop de temps à répondre. Vérifie ta connexion Internet puis réessaie."
        is UnknownHostException, is ConnectException ->
            "Pas de connexion Internet : impossible de joindre Mistral. " +
                "Vérifie ton Wi-Fi ou tes données mobiles puis réessaie."
        is IOException ->
            "La connexion avec Mistral a été interrompue" +
                (e.message?.let { " ($it)" } ?: "") + ". Réessaie."
        else -> "Erreur inattendue en contactant Mistral : ${e.message}"
    }

    /**
     * true si le message d'erreur vient d'une clé refusée (401/403) : l'interface propose
     * alors un raccourci vers l'écran de la clé.
     */
    fun isKeyRefusedMessage(message: String): Boolean =
        message.contains("(401)") || message.contains("(403)")

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

    private fun parseChatResponse(raw: String): RawReply {
        return try {
            val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            // Pas de trim() ici : si la réponse est coupée en plein milieu d'un mot ou juste
            // avant une espace, la suite doit se recoller exactement. Le trim final est fait
            // une seule fois dans chat().
            val text = choice.getJSONObject("message").optString("content", "")
            val truncated = choice.optString("finish_reason", "") == "length"
            if (text.isBlank()) {
                RawReply(null, false, "Réponse vide renvoyée par l'API Mistral.")
            } else {
                RawReply(text, truncated, null)
            }
        } catch (e: Exception) {
            RawReply(null, false, "Réponse de l'API Mistral illisible (format inattendu).")
        }
    }
}
