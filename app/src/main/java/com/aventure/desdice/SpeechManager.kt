package com.aventure.desdice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class SpeechManager(context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false

    /** Numéro de la lecture en cours : ignore les signaux tardifs d'une lecture déjà remplacée ou arrêtée. */
    @Volatile
    private var generation = 0

    /**
     * Texte (tel que donné à speak) en cours de lecture, null quand la voix est silencieuse.
     * Observable par Compose : l'interface s'en sert pour transformer « Écouter » en « Arrêter ».
     */
    var speakingText by mutableStateOf<String?>(null)
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRENCH
            tts.setOnUtteranceProgressListener(progressListener)
            isReady = true
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) = finish(utteranceId, force = false)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finish(utteranceId, force = true)

        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, force = true)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, force = true)
    }

    /** Identifiants de bloc : "g<lecture>-<n>", avec le suffixe "-last" sur le dernier bloc. */
    private fun finish(utteranceId: String?, force: Boolean) {
        val id = utteranceId ?: return
        val gen = id.removePrefix("g").substringBefore('-').toIntOrNull() ?: return
        if (gen != generation) return
        if (force || id.endsWith("-last")) speakingText = null
    }

    /**
     * Lit le texte à voix haute. Le texte est d'abord épuré (astérisques, émojis, marques
     * markdown, séparateurs "---" lus « À toi ! » : voir SpeechText) ; l'affichage à l'écran,
     * lui, n'est pas modifié. Les longues narrations sont découpées : Android refuse un seul
     * bloc trop long (TextToSpeech.getMaxSpeechInputLength(), environ 4000 caractères) et ne
     * lit alors rien.
     */
    fun speak(text: String) {
        if (!isReady) return
        val chunks = splitForSpeech(SpeechText.clean(text))
        if (chunks.isEmpty()) return
        val gen = ++generation
        speakingText = text
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val suffix = if (index == chunks.lastIndex) "-last" else ""
            tts.speak(chunk, mode, null, "g$gen-$index$suffix")
        }
    }

    /** Coupe la lecture immédiatement (bouton « Arrêter »). */
    fun stopSpeaking() {
        generation++
        speakingText = null
        if (isReady) {
            tts.stop()
        }
    }

    fun shutdown() {
        generation++
        speakingText = null
        tts.stop()
        tts.shutdown()
    }

    /** Découpe aux fins de phrase, en blocs qui respectent la limite du moteur vocal. */
    private fun splitForSpeech(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        if (text.length <= limit) return listOf(text)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+|\\n+"))) {
            var s = sentence.trim()
            if (s.isEmpty()) continue
            if (current.isNotEmpty() && current.length + s.length + 1 > limit) {
                chunks += current.toString()
                current.clear()
            }
            while (s.length > limit) { // phrase démesurée : coupe forcée
                chunks += s.take(limit)
                s = s.drop(limit)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
