// SpeechText.kt
// Nettoie le texte de l'IA avant de le donner à la synthèse vocale : le texte affiché
// à l'écran reste tel quel (astérisques, émojis...), seule la version LUE est épurée.

package com.aventure.desdice

object SpeechText {

    private val separatorRegex = Regex("(?m)^[ \\t]*(?:-{3,}|\\*{3,}|_{3,})[ \\t]*$")
    private val headingRegex = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bulletRegex = Regex("(?m)^\\s*[-•]\\s+")
    private val spacesRegex = Regex("[ \\t]{2,}")
    private val blankLinesRegex = Regex("\\n{3,}")

    /** Texte prêt à être lu à voix haute. */
    fun clean(raw: String): String {
        var t = raw
        // Séparateurs "---" : le dernier (celui qui précède / clôt l'appel à agir) est lu
        // « À toi ! », les autres sont simplement ignorés.
        separatorRegex.findAll(t).lastOrNull()?.let { last ->
            t = t.substring(0, last.range.first) + "À toi !" + t.substring(last.range.last + 1)
        }
        t = separatorRegex.replace(t, "")
        // Marqueurs de mise en forme : on garde le texte, on retire seulement les symboles.
        t = t.replace("*", "").replace("`", "")
        t = headingRegex.replace(t, "")
        t = bulletRegex.replace(t, "")
        t = removeEmojis(t)
        t = spacesRegex.replace(t, " ")
        t = blankLinesRegex.replace(t, "\n\n")
        return t.trim()
    }

    private fun removeEmojis(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!isEmojiLike(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun isEmojiLike(cp: Int): Boolean = when (cp) {
        0x200D, 0xFE0E, 0xFE0F, 0x20E3 -> true            // liaison, sélecteurs de variante, keycap
        in 0x1F300..0x1FAFF -> true                        // pictogrammes, visages, objets, drapeaux...
        in 0x1F1E6..0x1F1FF -> true                        // lettres de drapeaux
        in 0x2600..0x27BF -> true                          // symboles divers, dingbats (☀ ✨ ❤ ✔...)
        in 0x2B00..0x2BFF -> true                          // étoiles, carrés (⭐ ⬛...)
        in 0x2300..0x23FF -> true                          // ⌚ ⏰ ⏳...
        else -> false
    }
}
