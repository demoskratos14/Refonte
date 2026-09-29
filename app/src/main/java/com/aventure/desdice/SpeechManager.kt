package com.aventure.desdice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeechManager(context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRENCH
            isReady = true
        }
    }

    /**
     * Lit le texte à voix haute. Le texte est d'abord épuré (astérisques, émojis, marques
     * markdown : voir SpeechText) : l'affichage à l'écran, lui, n'est pas modifié.
     * Les longues narrations sont découpées : Android refuse un seul bloc trop long
     * (TextToSpeech.getMaxSpeechInputLength(), environ 4000 caractères) et ne lit alors rien.
     */
    fun speak(text: String) {
        if (!isReady) return
        val chunks = splitForSpeech(SpeechText.clean(text))
        if (chunks.isEmpty()) return
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "utteranceId-$index")
        }
    }

    fun stopSpeaking() {
        if (isReady) {
            tts.stop()
        }
    }

    fun shutdown() {
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
