// AiNarrator.kt
// Appels à l'IA narratrice (Mistral) : envoi d'un message de jeu avec les rappels
// recalculés (runAiNarrator), nettoyage des balises de la réponse, et résumé du journal
// en chapitres + résumé long terme (maybeUpdateStoryDigest, appel "fantôme" qui n'écrit
// jamais dans la conversation).
//
// Extrait de GameEngine.kt. Contrairement à PromptBuilder, ce code modifie la session
// qu'on lui passe (messages IA, quêtes, digest) ; mais il ne garde aucun état de partie :
// session, histoire courante et slug sont des paramètres. La clé et le modèle viennent de
// MistralConfig. GameEngine garde ses fonctions privées runAiNarrator() et
// maybeUpdateStoryDigest(sess, ...) qui lui délèguent l'appel avec l'état courant.

package com.aventure.desdice

class AiNarrator(private val mistralConfig: MistralConfig) {

    // Balises que l'IA ajoute à ses réponses : définies avec les consignes qui les demandent
    // (PromptBuilder.kt), retirées des réponses par runAiNarrator().
    private val questLaunchedTag = PromptBuilder.questLaunchedTag
    private val storyEndedTag = PromptBuilder.storyEndedTag
    private val chapterEndedTag = PromptBuilder.chapterEndedTag

    /**
     * Envoie eventText à l'IA (en amorçant la conversation avec le message
     * système si elle est vide), ajoute la réponse à la conversation, et
     * tente une mise à jour du digest journal/résumé..
     *
     * Le message "system" initial (mécaniques + FORMAT DE L'HISTOIRE) n'est
     * écrit qu'une fois, puis reste figé tel quel dans sess.aiConversation
     * pour le reste de la partie (voir plus haut) -- un compte à rebours
     * qui y serait inclus deviendrait donc faux dès l'échange suivant. Le
     * rappel de progression (buildStoryProgressNote) est pour cette raison
     * recalculé et ajouté à part ICI, à CHAQUE appel, sur la copie de
     * messages effectivement envoyée à l'API -- jamais sur sess.aiConversation
     * elle-même, donc jamais sauvegardé ni affiché au joueur (AiPanel.kt
     * n'affiche que ce qui est dans aiConversation).
     *
     * @param visible false : le message "user" envoyé (lancer de dés, prompt de mécaniques...)
     *   ne sera jamais montré dans AiPanel.kt (mais reste envoyé à l'IA et sauvegardé).
     * @param displayText si `visible` est vrai et ce paramètre non vide : c'est lui qui est
     *   affiché au joueur à la place du texte technique complet (ex. son propre message libre).
     * @return (texte de la réponse, erreur) — comme MistralClient.chat().
     */
    fun runAiNarrator(
        sess: DiceSession,
        currentStory: StoryEntry?,
        currentStorySlug: String?,
        eventText: String,
        visible: Boolean = true,
        displayText: String = "",
        extraNote: String? = null
    ): Pair<String?, String?> {
        if (!mistralConfig.hasMistralKey() || eventText.isEmpty()) return null to null
        if (sess.aiConversation.isEmpty()) {
            sess.addAiMessage("system", PromptBuilder.buildMechanicsContext(sess, currentStory, autoMode = true), visible = false)
        }
        // Quête lancée par l'ajout manuel d'un chapitre (addStoryEntry) : consigne transmise une seule fois.
        val announcement = sess.consumeQuestAnnouncement()
        val fullText = if (announcement != null) "$eventText\n$announcement" else eventText
        sess.addAiMessage("user", fullText, visible = visible, displayContent = displayText)
        val messagesToSend = sess.aiMessagesToSend().toMutableList()
        PromptBuilder.buildStoryProgressNote(sess, currentStory)?.let { note ->
            // Juste avant le dernier message (le "user" qu'on vient
            // d'ajouter), pour rester proche de la convention "la
            // conversation envoyée se termine par un message user".
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        messagesToSend.add(
            (messagesToSend.size - 1).coerceAtLeast(0),
            AiMessage("system", PromptBuilder.buildGaugeStateNote(sess))
        )
        PromptBuilder.buildPendingQuestNote(sess, currentStory)?.let { note ->
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        PromptBuilder.buildChapterEndNote(currentStory)?.let { note ->
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        // Note ponctuelle (ex. changement de palier de contenu) : envoyée à l'API, jamais enregistrée.
        extraNote?.let { note ->
            messagesToSend.add((messagesToSend.size - 1).coerceAtLeast(0), AiMessage("system", note))
        }
        val (text, error) = MistralClient.chat(
            mistralConfig.getMistralKey(),
            messagesToSend,
            model = mistralConfig.getMistralModel(),
            promptCacheKey = currentStorySlug
        )
        if (error != null) return null to error
        var replyText = text ?: ""
        // Fin d'un chapitre (pas de l'histoire entière) : le journal est digéré tout de suite,
        // même s'il reste moins de 6 échanges depuis le dernier résumé.
        var chapterEnded = false
        if (replyText.contains(chapterEndedTag)) {
            replyText = replyText.replace(chapterEndedTag, "").trim()
            chapterEnded = true
        }
        if (replyText.contains(questLaunchedTag)) {
            // L'IA a conclu le chapitre et lancé la quête courte : on retire la balise et on ouvre la quête.
            // C'est aussi une fin de chapitre, même si elle a oublié la balise dédiée.
            replyText = replyText.replace(questLaunchedTag, "").trim()
            chapterEnded = true
            if (sess.launchPendingQuest(announce = false)) sess.save()
        }
        // Fin définitive de l'histoire (pas juste un chapitre) : le journal ne doit pas rester
        // en attente d'un 6e échange qui ne viendra jamais -- le reliquat, même court, est
        // digéré tout de suite pour ne rien perdre du dernier chapitre.
        var storyEnded = false
        if (replyText.contains(storyEndedTag)) {
            replyText = replyText.replace(storyEndedTag, "").trim()
            storyEnded = true
        }
        sess.addAiMessage("assistant", replyText)
        maybeUpdateStoryDigest(sess, currentStorySlug, finalChapter = storyEnded, chapterEnd = chapterEnded)
        return replyText to null
    }

    private val digestChapterTag = "###CHAPITRE###"
    private val digestSummaryTag = "###RESUME###"

    /**
     * Met à jour le digest de l'histoire — appel "fantôme" : n'écrit jamais dans aiConversation.
     * @param currentStorySlug sert seulement de clé de cache de prompt côté Mistral ("<slug>-digest").
     * @param finalChapter true quand l'IA vient de signaler la fin définitive de l'histoire
     *   ($storyEndedTag) : le reliquat d'échanges pas encore digéré (moins de 6, éventuellement)
     *   est alors digéré quand même, comme dernier chapitre, au lieu d'attendre un 6e échange
     *   qui n'arrivera jamais.
     * @param chapterEnd true quand l'IA vient de conclure un chapitre ($chapterEndedTag) : même
     *   principe, tout le reliquat est digéré tout de suite pour que la fin du chapitre ne reste
     *   pas coincée dans la tranche suivante (mélangée au début du chapitre d'après).
     */
    fun maybeUpdateStoryDigest(
        sess: DiceSession,
        currentStorySlug: String?,
        finalChapter: Boolean = false,
        chapterEnd: Boolean = false,
        flushAll: Boolean = false
    ) {
        val digestTrigger = 6
        // Chapitre (~130 mots) + résumé long terme avec fiches personnages (~350 mots).
        val digestMaxTokens = 1200
        if (!mistralConfig.hasMistralKey()) return
        // flushAll : digère tout le reliquat sans prétendre que l'extrait clôt un chapitre
        // (utilisé juste avant un export, en plein milieu d'un chapitre).
        val flush = finalChapter || chapterEnd || flushAll
        while (true) {
            val pending = sess.pendingDigestMessages()
            val fullChunk = pending.size >= digestTrigger
            if (!fullChunk && (!flush || pending.isEmpty())) return
            val chunk = pending.take(digestTrigger)
            // Dernière tranche = celle qui vide le reliquat (même si elle fait pile 6 messages).
            val isLastChunk = (finalChapter || chapterEnd) && pending.size <= digestTrigger
            val isFlushEnd = flush && pending.size <= digestTrigger
            val block = chunk.joinToString("\n") { m -> "${if (m.role == "user") "Joueur" else "Narrateur"} : ${m.content}" }
            val existingSummary = sess.storySummary

            val digestSystem = "Tu reçois " +
                (if (isLastChunk && finalChapter) "le tout dernier extrait (celui qui clôt définitivement l'histoire) "
                else if (isLastChunk) "la fin d'un chapitre (l'extrait se termine sur la conclusion du chapitre) "
                else "un extrait récent ") +
                "d'une histoire de jeu de rôle (en français) et dois produire DEUX textes " +
                "bien distincts, chacun introduit par sa balise exacte sur sa propre ligne, rien " +
                "d'autre avant/après/entre :\n" +
                "$digestChapterTag\n" +
                "Un chapitre de journal racontant cet extrait : à la troisième personne, fluide et " +
                "narratif (pas une liste de faits, pas de dialogue au style direct), 80 à 130 mots" +
                (if (isLastChunk && finalChapter) ", qui se termine sur la conclusion de l'histoire.\n"
                else if (isLastChunk) ", qui se termine sur la conclusion du chapitre.\n"
                else ".\n") +
                "Présente chaque nouveau personnage important (humain, créature, animal, esprit...) au " +
                "moment où il apparaît pour la première fois : en une courte phrase, dis qui il est, son " +
                "apparence et son rôle, comme pour un lecteur qui le découvre. Un personnage est nouveau " +
                "s'il ne figure pas déjà dans la partie PERSONNAGES du résumé existant ; ne parle jamais " +
                "d'un personnage nouveau comme s'il était déjà connu du lecteur.\n" +
                "$digestSummaryTag\n" +
                "Le résumé long terme mis à jour, en DEUX parties. D'abord la ligne « PERSONNAGES : » " +
                "suivie d'une ligne par personnage (« - Nom : apparence, caractère, rôle, lien avec le " +
                "joueur, signe distinctif »). Puis la ligne « FAITS : » suivie des faits marquants " +
                "(objets/totems, lieux, quêtes, événements à retenir). Fusionne le résumé existant (s'il y " +
                "en a un) et les nouveautés de cet extrait. Ne supprime JAMAIS un personnage déjà listé : " +
                "garde ses traits, ne les modifie que si l'histoire les a réellement changés, et ajoute " +
                "les nouveaux personnages. Si le résumé existant n'a pas de partie PERSONNAGES, crée-la " +
                "avec tous les personnages connus. Style neutre et factuel, sans tournures narratives. " +
                "350 mots maximum : si la place manque, raccourcis les FAITS en priorité, jamais les " +
                "personnages."
            val userPrompt = buildString {
                if (existingSummary.isNotEmpty()) append("Résumé existant :\n$existingSummary\n\n")
                append("Extrait de l'histoire (échanges joueur/narrateur) :\n$block\n\n")
                append("Réponds avec les deux textes demandés, balises $digestChapterTag et $digestSummaryTag incluses.")
            }
            val (text, error) = MistralClient.chat(
                mistralConfig.getMistralKey(),
                listOf(AiMessage("system", digestSystem), AiMessage("user", userPrompt)),
                model = mistralConfig.getMistralModel(),
                maxTokens = digestMaxTokens,
                promptCacheKey = currentStorySlug?.let { "$it-digest" }
            )
            if (error != null || text.isNullOrEmpty()) return

            val chapterPos = text.indexOf(digestChapterTag)
            val summaryPos = text.indexOf(digestSummaryTag)
            if (chapterPos == -1 || summaryPos == -1 || summaryPos <= chapterPos) return
            val chapter = text.substring(chapterPos + digestChapterTag.length, summaryPos).trim()
            if (chapter.isEmpty()) return
            val updatedSummary = text.substring(summaryPos + digestSummaryTag.length).trim().ifEmpty { existingSummary }
            sess.applyStoryDigest(chapter, updatedSummary, chunk.size)
            if (isFlushEnd) return
        }
    }
}
