// JournalExporter.kt
// Portage Kotlin de journal_export.py.
//
// Contrairement à Python (dépendance optionnelle fpdf2, avec repli sur du
// texte brut si absente), Android fournit nativement
// android.graphics.pdf.PdfDocument depuis l'API 19 : la génération PDF est
// donc TOUJOURS disponible ici, pas d'équivalent de fpdf_available().
// GameEngine.exportJournal() garde malgré tout le même repli texte brut
// que le Python, pour le cas où story_log est vide (voir generateJournalPdf
// qui renvoie null dans ce cas, comme generate_journal_pdf côté Python).

package com.aventure.desdice

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

object JournalExporter {

    // A4 en points (72 dpi), comme FPDF(format="A4").
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val LINE_HEIGHT_TITLE = 26f
    private const val LINE_HEIGHT_SUBTITLE = 18f
    private const val LINE_HEIGHT_CHAPTER = 20f
    private const val LINE_HEIGHT_BODY = 15f

    /**
     * Génère le PDF du journal, un chapitre par entrée de storyLog (dans
     * l'ordre). Renvoie null s'il n'y a rien à exporter — l'appelant doit
     * alors se rabattre sur un export texte (voir GameEngine.exportJournal).
     */
    fun generateJournalPdf(title: String, subtitle: String, storyLog: List<String>): ByteArray? {
        if (storyLog.isEmpty()) return null

        val titlePaint = Paint().apply {
            textSize = 20f
            isFakeBoldText = true
            color = Color.BLACK
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            color = Color.rgb(90, 90, 90)
            isAntiAlias = true
        }
        val chapterPaint = Paint().apply {
            textSize = 14f
            isFakeBoldText = true
            color = Color.BLACK
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            textSize = 11f
            color = Color.BLACK
            isAntiAlias = true
        }

        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN + LINE_HEIGHT_TITLE

        fun newPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN + LINE_HEIGHT_BODY
        }

        fun ensureSpace(height: Float) {
            if (y + height > PAGE_HEIGHT - MARGIN) newPage()
        }

        canvas.drawText(title.ifEmpty { "Journal de l'histoire" }, MARGIN, y, titlePaint)
        y += LINE_HEIGHT_TITLE
        if (subtitle.isNotEmpty()) {
            ensureSpace(LINE_HEIGHT_SUBTITLE)
            canvas.drawText(subtitle, MARGIN, y, subtitlePaint)
            y += LINE_HEIGHT_SUBTITLE
        }
        y += 8f

        storyLog.forEachIndexed { index, entry ->
            ensureSpace(LINE_HEIGHT_CHAPTER)
            canvas.drawText("Chapitre ${index + 1}", MARGIN, y, chapterPaint)
            y += LINE_HEIGHT_CHAPTER
            for (paragraph in entry.split("\n")) {
                for (line in wrapText(paragraph, bodyPaint, contentWidth)) {
                    ensureSpace(LINE_HEIGHT_BODY)
                    canvas.drawText(line, MARGIN, y, bodyPaint)
                    y += LINE_HEIGHT_BODY
                }
            }
            y += 10f
        }

        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    /** Découpe un paragraphe en lignes tenant dans maxWidth (PdfDocument n'a pas de retour à la ligne automatique). */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
