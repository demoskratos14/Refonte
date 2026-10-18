// StoryTransfer.kt
// Exports et imports d'une histoire : journal (PDF ou texte), identité
// (histoire personnalisée + totems + progression), progression seule, et
// analyse du JSON importé.
//
// Extrait de GameEngine.kt. Cette classe ne garde aucun état de partie : tout ce
// qu'elle doit lire (session, histoire courante, slug) lui est passé en paramètre.
// Le résumé des derniers échanges par l'IA (maybeUpdateStoryDigest) avant un export
// reste dans GameEngine : il fait partie de la narration IA, pas du format d'export.
// GameEngine garde les mêmes méthodes publiques (exportJournal, exportIdentity,
// exportProgress, importIdentity, applyImportedProgress).

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject

/**
 * @param totemImageToBase64 relit l'image d'un totem (dossier totem_images) et la renvoie en
 *   Base64, ou "" si elle est absente ou illisible.
 */
class StoryTransfer(
    private val storyRegistry: StoryRegistry,
    private val totemImageToBase64: (String?) -> String
) {

    /**
     * PDF (JournalExporter) si possible, sinon
     * texte brut (session.storyLogText()) — jamais d'erreur côté joueur.
     * @return (octets, nom de fichier, type MIME), ou triple vide si aucune session active.
     */
    fun exportJournal(sess: DiceSession?, currentStory: StoryEntry?, currentStorySlug: String?): Triple<ByteArray, String, String> {
        if (sess == null) return Triple(ByteArray(0), "", "")
        val title = currentStory?.title?.ifEmpty { "Journal de l'histoire" } ?: "Journal de l'histoire"
        val subtitle = currentStory?.subtitle ?: ""
        val slug = currentStorySlug ?: "histoire"
        val pdfBytes = JournalExporter.generateJournalPdf(title, subtitle, sess.storyLog)
        return if (pdfBytes != null) {
            Triple(pdfBytes, "journal_$slug.pdf", "application/pdf")
        } else {
            val text = sess.storyLogText().ifEmpty { "(aucun chapitre enregistré pour l'instant)" }
            Triple(text.toByteArray(Charsets.UTF_8), "journal_$slug.txt", "text/plain")
        }
    }

    /**
     * Construit l'export d'identité d'une histoire personnalisée : le totem de départ (posé par
     * switchStory) n'est pas ré-exporté comme "extra" — seuls ceux ajoutés ensuite par le joueur
     * le sont. L'appelant a déjà vérifié que l'histoire est personnalisée et résumé les derniers
     * échanges ; `sess` peut être null.
     */
    fun exportIdentity(slug: String, sess: DiceSession?): Triple<ByteArray, String, String> {
        val extraTotems = JSONArray()
        var sideQuests = JSONArray()
        var nextQuestId = 1
        var storyLog = JSONArray()
        var storySummary = ""
        if (sess != null) {
            for (t in sess.customTotems.drop(1)) {
                extraTotems.put(
                    JSONObject().apply {
                        put("label", t.label)
                        put("emoji", t.emoji)
                        put("powers", JSONArray(t.powers))
                        put("special", t.special)
                        put("image_b64", totemImageToBase64(t.image))
                    }
                )
            }
            sideQuests = JSONArray(sess.sideQuests.map { it.toJson() })
            nextQuestId = sess.nextQuestId
            storyLog = JSONArray(sess.storyLog)
            storySummary = sess.storySummary
        }
        val data = storyRegistry.exportStoryIdentity(
            slug,
            extraTotems = extraTotems,
            sideQuests = sideQuests,
            nextQuestId = nextQuestId,
            storyLog = storyLog,
            storySummary = storySummary
        ) ?: return Triple(ByteArray(0), "", "")
        val payload = data.toString(2)
        return Triple(payload.toByteArray(Charsets.UTF_8), "histoire_$slug.json", "application/json")
    }

    /**
     * Sauvegarde légère de la progression, pour reprendre une histoire après une désinstallation :
     * résumé long terme de l'IA, journal et quêtes secondaires (pas les images ni les totems).
     * Contrairement à exportIdentity(), marche pour TOUTES les histoires, pas seulement les
     * personnalisées ; se réimporte sur une histoire encore vierge (voir AiPanel). Les mêmes clés
     * que l'identité sont utilisées (story_summary, story_log, side_quests, next_quest_id) : un
     * fichier d'identité exportée est donc aussi accepté à l'import.
     * L'appelant a déjà résumé les derniers échanges.
     */
    fun exportProgress(sess: DiceSession, currentStory: StoryEntry?, currentStorySlug: String?): Triple<ByteArray, String, String> {
        val slug = currentStorySlug ?: "histoire"
        val data = JSONObject().apply {
            put("format", "histoires_multiples_progression")
            put("version", 1)
            put("story_title", currentStory?.title ?: "")
            put("story_summary", sess.storySummary)
            put("story_log", JSONArray(sess.storyLog))
            put("side_quests", JSONArray(sess.sideQuests.map { it.toJson() }))
            put("next_quest_id", sess.nextQuestId)
        }
        return Triple(data.toString(2).toByteArray(Charsets.UTF_8), "progression_$slug.json", "application/json")
    }

    /** Ne fait qu'analyser/valider le JSON importé, sans rien appliquer. */
    fun importIdentity(rawText: String): JSONObject {
        val parsed = storyRegistry.parseIdentityImport(rawText)
        return JSONObject().apply {
            put("title", parsed.title)
            put("subtitle", parsed.subtitle)
            put("lore_text", parsed.loreText)
            put("totem_label", parsed.totemLabel)
            put("totem_powers", parsed.totemPowers)
            put("totem_special", parsed.totemSpecial)
            put("protagonist_names", JSONArray(parsed.protagonistNames))
            // Base64, pas des bytes bruts, pour rester manipulable en JSON —
            // c'est CreateStoryScreen qui décode avant de rappeler createStory.
            put("bg_image_b64", parsed.bgImageB64)
            put("totem_image_b64", parsed.totemImageB64)
            // Totems supplémentaires : à recréer à part via addCustomTotem
            // juste après createStory — jamais transmis à createStory
            // lui-même, qui ne doit jamais poser plus d'un totem de départ.
            put(
                "extra_totems",
                JSONArray(
                    parsed.extraTotems.map { t ->
                        JSONObject().apply {
                            put("label", t.label)
                            put("emoji", t.emoji)
                            put("powers", JSONArray(t.powers))
                            put("special", t.special)
                            put("image_b64", t.imageB64)
                        }
                    }
                )
            )
            // Quêtes secondaires et journal : à appliquer à part via
            // applyImportedProgress, une fois l'histoire créée.
            put("side_quests", parsed.sideQuests)
            put("next_quest_id", parsed.nextQuestId)
            put("story_log", parsed.storyLog)
            put("story_summary", parsed.storySummary)
            put("story_length", parsed.storyLength)
            put("moral_goal", parsed.moralGoal)
        }
    }
}
