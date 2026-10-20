// PromptBuilder.kt
// Construction des textes envoyés à l'IA narratrice : contexte de mécaniques (message
// "system" initial), rappels recalculés à chaque appel (progression, jauges, pouvoirs des
// totems, fin de chapitre, quête en attente), prompt complet du mode manuel, message de
// démarrage et texte d'événement d'un lancer.
//
// Extrait de GameEngine.kt. Tout ce code est pur : il lit la session et l'histoire qu'on lui
// passe et renvoie du texte, sans rien modifier ni appeler Mistral. Les balises que l'IA
// ajoute à ses réponses ([[FIN_CHAPITRE]], [[QUETE_LANCEE]], [[HISTOIRE_TERMINEE]]) sont
// définies ici, parce que les consignes qui les demandent sont ici ; c'est GameEngine
// (runAiNarrator) qui les retire des réponses.

package com.aventure.desdice

object PromptBuilder {

    private val successCompact: Map<Int, String> = mapOf(
        1 to "Échec critique, rattrapable (jamais la fin de l'histoire)",
        2 to "Échec ou réussite très dure",
        3 to "Réussite partielle",
        4 to "Bonne réussite",
        5 to "Très bonne réussite",
        6 to "Réussite héroïque"
    )

    private val fateCompact: Map<String, String> = mapOf(
        "coeur" to "allié/protection : un ami ou héros intervient, guérison, lien renforcé, ou miracle",
        "question" to "quête secondaire débloquée (nouvel ami ou nouvel objet/totem), à faire quand on veut",
        "soleil" to "bénédiction : énergie positive, pouvoir renforcé/stabilisé, protection, amélioration durable",
        "etoile" to "chance exceptionnelle : opportunité rare, découverte précieuse, ou récompense spéciale",
        "exclamation" to "aide arrive : une connaissance, ou un animal lié à un totem, intervient",
        "spirale" to "chaos/transformation : effet imprévisible, mutation, ou conséquence inattendue"
    )

    /** "Alix" / "Alix et Nao" / "Alix, Nao et Théo" -- vide -> "le joueur". */
    private fun joinProtagonistNames(names: List<String>): String {
        val n = names.map { it.trim() }.filter { it.isNotEmpty() }
        return when (n.size) {
            0 -> "le joueur"
            1 -> n[0]
            else -> n.dropLast(1).joinToString(", ") + " et " + n.last()
        }
    }

    /**
     * "à Alix" / "de Alix et Nao" : le nom (ou la liste) précédé de la préposition demandée ("à"
     * ou "de"). Sans prénom, on dit "au joueur" / "du joueur" -- et non "à le joueur" / "de le
     * joueur", ce que donnerait joinProtagonistNames() collé derrière la préposition.
     */
    private fun withPreposition(preposition: String, names: List<String>): String {
        if (names.any { it.isNotBlank() }) return "$preposition ${joinProtagonistNames(names)}"
        return if (preposition == "à") "au joueur" else "du joueur"
    }

    /**
     * Consigne d'adressage à donner à l'IA : "tu" pour un seul héros ; pour plusieurs, un mélange
     * de vouvoiement collectif (action commune) et de tutoiement par prénom (action ou choix
     * distinct d'un héros) -- en particulier au moment des choix et des lancers de dés, où
     * chaque héros doit pouvoir agir et lancer les dés indépendamment des autres.
     */
    private fun protagonistAddressing(names: List<String>): String {
        val n = names.map { it.trim() }.filter { it.isNotEmpty() }
        return when (n.size) {
            0 -> "tu t'adresses toujours au joueur en 'tu'."
            1 -> "tu t'adresses toujours à ${n[0]} en 'tu'."
            else -> "les héros de cette histoire sont ${joinProtagonistNames(n)} : utilise le " +
                "vouvoiement collectif (\"vous\") quand leur action est commune, et bascule sur le " +
                "tutoiement en t'adressant par le prénom de chacun (\"${n[0]}, tu...\") dès qu'un " +
                "héros agit ou choisit séparément des autres -- en particulier au moment des choix " +
                "et des lancers de dés, où chaque héros doit pouvoir faire son propre choix et son " +
                "propre lancer, indépendamment des autres : demande à chacun son lancer à tour de rôle, " +
                "et quand tu proposes des actions sous forme de lignes OPTION:, commence chacune par le " +
                "prénom du héros concerné (par exemple \"OPTION: ${n[0]} ouvre la porte\")."
        }
    }

    fun buildMechanicsContext(sess: DiceSession, story: StoryEntry?, autoMode: Boolean): String {
        val addressedTo = withPreposition("à", story?.protagonistNames ?: emptyList())
        val lines = mutableListOf<String>()
        lines += "=== CONTEXTE IA NARRATRICE ==="
        lines += if (autoMode) {
            "Narrateur d'une histoire héroïque interactive, 2e personne ('tu'), adressée " +
                "$addressedTo. Mécaniques ci-dessous ; l'histoire se poursuit directement dans " +
                "cette conversation, événement par événement."
        } else {
            "Narrateur d'une histoire héroïque interactive, 2e personne ('tu'), adressée " +
                "$addressedTo. Mécaniques ci-dessous, puis l'histoire déjà vécue."
        }
        lines += ""
        lines += "DÉ DE RÉUSSITE (1-6, jamais de fin d'histoire même sur un 1, toujours moyen de se rattraper) :"
        lines += (1..6).joinToString(" | ") { v -> "$v=${successCompact[v]}" }
        lines += ""
        val storyLen = normalizeStoryLength(story?.storyLength ?: "long")
        val questionRule = when (storyLen) {
            LENGTH_SHORT -> "le ? déclenche un ÉVÉNEMENT SOUDAIN inattendu dans la scène en cours, " +
                "jamais une quête annexe"
            LENGTH_LONG -> "le ? ne retombe pas tant qu'une quête secondaire n'est pas terminée, une seule " +
                "à la fois ; cette quête, COURTE, se lance automatiquement à la fin du chapitre"
            else -> "le ? ne retombe pas tant qu'une quête secondaire ouverte n'est pas terminée, une seule " +
                "active à la fois ; elle se construit en parallèle de l'histoire principale"
        }
        val questionCompact = when (storyLen) {
            LENGTH_SHORT -> "événement soudain et inattendu dans la scène en cours, à gérer immédiatement (pas de quête annexe)"
            LENGTH_LONG -> "quête secondaire courte (nouvel ami ou nouvel objet/totem), lancée automatiquement à la fin du chapitre"
            else -> "quête secondaire (nouvel ami ou nouvel objet/totem) construite en parallèle de l'histoire principale"
        }
        lines += "DÉ DU DESTIN (6 symboles ; $questionRule) :"
        lines += FATE_FACES.joinToString(" | ") { f ->
            "${f.emoji}${f.label}=${if (f.key == "question") questionCompact else fateCompact[f.key]}"
        }
        lines += ""
        lines += "TOTEMS/ALLIÉS (symbole choisi sur le dé de réussite) :"
        for (t in sess.customTotems) {
            val icon = t.emoji.ifEmpty { "\uD83D\uDC3E" }
            val powersTxt = if (t.powers.isNotEmpty()) t.powers.joinToString(", ") else "(pouvoirs non précisés)"
            val specialTxt = if (t.special.isNotEmpty()) " — spé: ${t.special}" else ""
            lines += "$icon${t.label}: $powersTxt$specialTxt"
        }
        val fixedAlliesLine = story?.fixedAlliesLine ?: ""
        if (fixedAlliesLine.isNotEmpty()) lines += fixedAlliesLine
        if (sess.customTotems.isEmpty()) lines += "(aucun pour l'instant -- tout reste à découvrir en jouant)"
        lines += "Jauge par totem/allié : +score obtenu (symbole unique) ou +1/symbole (mélange) à " +
            "chaque lancer concerné -> pleine à $TOTEM_ENERGY_THRESHOLD points, alors utilisable " +
            "(capacité ou aide correspondante)."
        lines += ""
        lines += "JAUGE DE MENACE : +3 sur un 1, -1 sur un 5 ou 6. À $THREAT_THRESHOLD points, " +
            "complication secondaire inattendue puis retombe à 0."
        lines += ""
        lines += "RÈGLE ABSOLUE SUR LES JAUGES : les jauges (totems, alliés, menace) sont calculées et " +
            "tenues à jour UNIQUEMENT par l'application, à partir des lancers réels du joueur. Tu ne " +
            "les modifies JAMAIS : tu n'ajoutes ni ne retires aucun point, tu n'annonces aucun nouveau " +
            "niveau (pas de « ta jauge passe à... », pas de « +2 » pour un totem), et tu ne déduis pas " +
            "de points d'un lancer. Le niveau actuel de chaque jauge t'est fourni à chaque message : " +
            "sers-t'en seulement pour la narration (une jauge pleine = le pouvoir est utilisable)."
        lines += ""
        lines += buildStoryLengthInstructions(story)
        if (!autoMode) {
            // Mode manuel : buildFullPrompt()/buildFullPromptText() recalcule
            // tout ce texte à chaque clic sur "copier le prompt", donc un
            // compte à jour ici reste toujours exact. En mode auto, ce même
            // calcul existe (buildStoryProgressNote), mais est ajouté à part
            // dans runAiNarrator à CHAQUE appel API, jamais ici : ce texte-ci
            // n'est écrit qu'UNE SEULE fois dans le message "system" figé de
            // la conversation (voir plus bas), un compte à rebours y serait
            // juste devenu faux dès l'échange suivant.
            buildStoryProgressNote(sess, story)?.let {
                lines += ""
                lines += it
            }
        }
        for (paragraph in story?.loreParagraphs ?: emptyList()) {
            lines += ""
            lines += paragraph
        }
        val moralGoal = story?.moralGoal?.trim().orEmpty()
        if (moralGoal.isNotEmpty()) {
            lines += ""
            lines += "OBJECTIF MORAL DU RÉCIT (en plus de l'univers ci-dessus, qui reste la trame " +
                "de l'histoire -- ne le remplace jamais) : fais vivre, à travers les situations, " +
                "les personnages et les choix proposés au joueur, la notion suivante : " +
                "\"$moralGoal\". Ne transforme jamais ça en leçon de morale explicite ni en discours " +
                "moralisateur adressé au joueur -- l'objectif se ressent dans le déroulement de " +
                "l'histoire, jamais énoncé tel quel."
        }
        lines += ""
        lines += buildContentFrame(story?.playerAge ?: PlayerAge.UNKNOWN)
        lines += ""
        lines += if (autoMode) {
            "ATTENDU DE TOI : histoire collaborative et immersive intégrant directement les " +
                "événements de jeu que je te transmets (lancers de dés, pouvoirs utilisés, quêtes...) ; " +
                "à chaque action/incertitude tu me demandes explicitement de lancer le dé de réussite, " +
                "le dé du destin, ou les deux, et tu attends le résultat suivant avant de continuer ; " +
                protagonistAddressing(story?.protagonistNames ?: emptyList())
        } else {
            "ATTENDU DE TOI : histoire collaborative et immersive intégrant mes lancers ; à chaque " +
                "action/incertitude tu me demandes de lancer réussite/destin/les deux et attends mon " +
                "résultat avant de continuer ; si j'utilise une jauge pleine ou qu'un ?/! survient je " +
                "te colle un petit bloc généré par l'appli pour te le signaler précisément ; à la fin " +
                "de chaque chapitre tu me donnes un résumé à coller dans l'appli pour garder une trace " +
                "permanente ; " + protagonistAddressing(story?.protagonistNames ?: emptyList())
        }
        if (autoMode) {
            lines += ""
            lines += "PROPOSITIONS D'ACTIONS CLIQUABLES : quand cela a du sens (pas systématiquement " +
                "-- seulement quand plusieurs suites raisonnables se présentent), termine ta réponse " +
                "par 2 à 4 lignes, chacune au format EXACT 'OPTION: <action très courte>' (quelques " +
                "mots, sans numérotation ni ponctuation finale), une par ligne, rien d'autre après. " +
                "L'application les transforme en boutons cliquables pour le joueur. Il garde toujours " +
                "la possibilité de répondre librement au lieu d'en choisir une -- ne l'oblige jamais à " +
                "s'y limiter -- et n'utilise ce format QUE pour proposer des actions, jamais pour autre " +
                "chose."
            lines += ""
            lines += "FIN DÉFINITIVE DE L'HISTOIRE : quand tu écris la toute dernière réponse de cette " +
                "histoire -- celle qui clôt vraiment l'aventure, pas simplement la fin d'un chapitre " +
                "parmi d'autres -- termine ce message par la balise exacte $storyEndedTag, seule sur " +
                "sa propre ligne, juste après le texte de conclusion. Ne l'utilise que pour cette toute " +
                "dernière réponse, jamais avant."
        }
        return lines.joinToString("\n")
    }

    /** Le cadre de contenu tel qu'il apparaît dans un message déjà construit (lignes jointes par un saut de ligne). */
    fun contentFrameText(playerAge: Int): String = buildContentFrame(playerAge).joinToString("\n")

    /**
     * Note transmise UNE fois à l'IA juste après un changement de palier en cours d'histoire :
     * les anciens messages de la conversation sont dans l'ancien ton, et l'IA aurait tendance
     * à les imiter plutôt qu'à suivre le nouveau cadre.
     */
    fun buildFrameChangeNote(newAge: Int): String {
        val tier = when (PlayerAge.tierOf(newAge)) {
            AgeTier.CHILD -> "enfant"
            AgeTier.TEEN -> "adolescent"
            AgeTier.ADULT -> "adulte"
        }
        return "CHANGEMENT DE CADRE : le palier de contenu de cette histoire vient d'être modifié, " +
            "il est maintenant « $tier ». Applique le TON DU RÉCIT et le CADRE du contexte ci-dessus " +
            "dès ta prochaine réponse : le niveau de peur, de violence, le vocabulaire et les emojis " +
            "des réponses précédentes ne sont plus un modèle à suivre. Reste dans la continuité de " +
            "l'intrigue, sans mentionner ce changement au joueur."
    }

    /**
     * Ton du récit + cadre de contenu, selon le palier d'âge du plus jeune joueur (AgeTier.kt) :
     * enfant (moins de 12 ans, ou âge inconnu), adolescent (12 à 17 ans), adulte (18 ans et plus).
     * Renvoie des lignes, la première étant vide (séparateur) ; "TON DU RÉCIT" et le titre du
     * cadre y figurent toujours. Le palier adulte autorise les thèmes sombres mais reste sans
     * contenu sexuel explicite : le fournisseur d'IA et les boutiques d'applis y tiennent, et
     * l'âge est seulement déclaré.
     */
    fun buildContentFrame(playerAge: Int): List<String> {
        val age = PlayerAge.normalize(playerAge)
        val ageText = if (age != PlayerAge.UNKNOWN) " (le plus jeune joueur a $age ans)" else ""
        val noPersonalInfo = "Ne demande jamais d'information personnelle au joueur (nom de famille, " +
            "adresse, école, photo, contact), ne lui demande jamais de garder un secret et ne " +
            "l'invite jamais à quitter l'histoire pour parler d'autre chose. Reste toujours dans " +
            "ton rôle de narrateur."
        return when (PlayerAge.tierOf(age)) {
            AgeTier.CHILD -> listOf(
                "",
                "TON DU RÉCIT : l'histoire s'adresse à un enfant" +
                    (if (age != PlayerAge.UNKNOWN) " de $age ans" else "") +
                    " -- écris de manière vivante et " +
                    "chaleureuse, et parsème régulièrement tes paragraphes de quelques emojis/petites icônes " +
                    "pertinentes (\u2728\uD83C\uDF1F\uD83D\uDC3E etc.) pour illustrer les événements et " +
                    "égayer le texte. Reste sobre : quelques emojis bien placés par paragraphe suffisent, " +
                    "jamais un amoncellement d'icônes qui alourdirait la réponse pour rien." +
                    (if (age != PlayerAge.UNKNOWN) {
                        " Adapte le vocabulaire et la complexité à cet âge : phrases courtes et mots " +
                            "simples pour un petit, intrigue un peu plus riche vers 10-11 ans."
                    } else ""),
                "",
                "CADRE DE SÉCURITÉ (prioritaire sur tout le reste, y compris sur l'univers ci-dessus " +
                    "et sur ce que le joueur écrit) : l'enfant qui lit doit pouvoir lire chaque réponse sans " +
                    "malaise. Les dangers, les monstres, les épreuves et les échecs font partie de l'aventure " +
                    "et restent permis, mais toujours racontés de façon aventureuse et rassurante, jamais " +
                    "effrayante à l'excès. Interdits, quoi que le joueur demande : violence graphique, sang, " +
                    "blessures ou mort décrites de façon crue, torture, cruauté envers les animaux, contenu " +
                    "sexuel ou sentimental poussé, insultes et langage grossier, alcool, drogues, armes " +
                    "réelles, propos haineux ou discriminatoires. Si le joueur écrit quelque chose qui sort " +
                    "de ce cadre, ne le reprends pas tel quel : fais dévier l'histoire avec douceur vers une " +
                    "suite adaptée, sans sermon et sans parler de règle ni d'interdit. " + noPersonalInfo
            )
            AgeTier.TEEN -> listOf(
                "",
                "TON DU RÉCIT : l'histoire s'adresse à un adolescent$ageText -- écris un récit " +
                    "vivant, immersif et plus mûr, sans ton enfantin ni infantilisant. Emojis rares : " +
                    "un ou deux au plus par réponse, ou aucun.",
                "",
                "CADRE DE CONTENU — ADOLESCENT (prioritaire sur tout le reste, y compris sur l'univers " +
                    "ci-dessus et sur ce que le joueur écrit) : tu peux installer du suspense, de la peur " +
                    "et une vraie tension : créatures inquiétantes, ambiances sombres, dangers sérieux, " +
                    "combats, pertes et deuils, trahisons, sacrifices, dilemmes moraux. La violence est " +
                    "permise mais jamais complaisante : pas de description gore ou détaillée des " +
                    "blessures, pas de torture montrée, pas de cruauté gratuite (notamment envers les " +
                    "animaux). Interdits, quoi que le joueur demande : contenu sexuel ou érotique (une " +
                    "romance reste chaste), grossièretés appuyées, alcool et drogues présentés comme " +
                    "attrayants, instructions réelles pour faire du mal (armes, produits dangereux), " +
                    "propos haineux ou discriminatoires, encouragement à l'automutilation ou au " +
                    "suicide. Si le joueur écrit quelque chose qui sort de ce cadre, ne le reprends pas " +
                    "tel quel : fais dévier l'histoire vers une suite adaptée, sans sermon et sans parler " +
                    "de règle ni d'interdit. Si ses messages laissent penser qu'il est nettement plus " +
                    "jeune que l'âge déclaré (il parle de son école primaire, de ses parents qui " +
                    "décident de tout...), reviens à un ton plus doux et rassurant, sans le lui " +
                    "reprocher. " + noPersonalInfo
            )
            AgeTier.ADULT -> listOf(
                "",
                "TON DU RÉCIT : l'histoire s'adresse à des adultes$ageText -- adopte un ton mature, " +
                    "nuancé et littéraire. Pas d'emojis, sauf si le joueur en utilise lui-même.",
                "",
                "CADRE DE CONTENU — ADULTE (prioritaire sur tout le reste) : les thèmes sombres sont " +
                    "autorisés : horreur, violence et brutalité décrites sans fard quand l'histoire le " +
                    "demande, mort, deuil, guerre, folie, personnages cruels, dilemmes moraux lourds, " +
                    "langage cru dans la bouche des personnages quand c'est cohérent, alcool et drogues " +
                    "comme éléments du monde. Limites qui restent, quoi que le joueur demande : aucun " +
                    "contenu sexuel explicite ni pornographique (la romance et la sensualité restent " +
                    "suggérées, les scènes intimes passent en ellipse) ; aucune sexualisation de mineurs, " +
                    "jamais ; aucun mode d'emploi réel pour fabriquer des armes, des explosifs ou des " +
                    "drogues, ni pour pirater ; aucun encouragement à l'automutilation ou au suicide ; " +
                    "un personnage odieux peut exister, mais le récit ne cautionne pas les propos " +
                    "haineux visant de vraies catégories de personnes. Si le joueur écrit quelque " +
                    "chose qui sort de ce cadre, fais dévier l'histoire sans sermon. Si ses messages " +
                    "laissent penser qu'il est nettement plus jeune que l'âge déclaré (il parle de son " +
                    "école primaire, de ses parents qui décident de tout...), reviens à un ton prudent " +
                    "adapté à un jeune public, sans le lui reprocher. " + noPersonalInfo
            )
        }
    }

    /**
     * Explication du format choisi (StoryEntry.storyLength) -- texte fixe,
     * ne dépend PAS du nombre d'échanges déjà écoulés (voir
     * buildStoryProgressNote() pour le compte à jour, séparé). Intégrée une
     * seule fois dans le message "system" en mode auto (figé pour le reste
     * de la conversation), recalculée à chaque clic "copier le prompt" en
     * mode manuel -- dans les deux cas ce texte reste identique du début à
     * la fin de l'histoire, donc aucun souci de fraîcheur ici.
     */
    private fun buildStoryLengthInstructions(story: StoryEntry?): String {
        return when (story?.storyLength ?: "long") {
            "short" -> "FORMAT DE L'HISTOIRE : COURT -- une dizaine d'échanges au total. " +
                "Construis une histoire complète qui se conclut naturellement aux alentours du " +
                "dixième échange : ni bâclée avant, ni étirée artificiellement après. Amorce le " +
                "dénouement dès que tu sens qu'on approche de la fin prévue (voir le rappel de " +
                "progression ci-dessous, mis à jour à chaque échange)."
            "medium" -> "FORMAT DE L'HISTOIRE : MOYEN -- entre 20 et 30 échanges au total. " +
                "Construis une histoire complète qui se conclut naturellement dans cette " +
                "fourchette : ni bâclée avant, ni étirée artificiellement après. Amorce le " +
                "dénouement dès que tu sens qu'on approche de la fin prévue (voir le rappel de " +
                "progression ci-dessous, mis à jour à chaque échange)."
            else -> "FORMAT DE L'HISTOIRE : LONG, en CHAPITRES, sans limite totale d'échanges. " +
                "Chaque chapitre doit poser une vraie problématique puis la résoudre avant sa " +
                "fin -- jamais une coupure arbitraire en plein milieu de l'action. Dès qu'un " +
                "chapitre se termine (problématique résolue), tu t'arrêtes : propose une vraie " +
                "conclusion de chapitre, puis demande explicitement au joueur s'il souhaite " +
                "commencer le chapitre suivant. Tu n'enchaînes JAMAIS automatiquement sur un " +
                "nouveau chapitre tant qu'il n'a pas répondu positivement à cette question."
        }
    }

    /**
     * Rappel de progression COURT et A JOUR, distinct du texte fixe
     * ci-dessus : compte les échanges déjà narrés dans sess.aiConversation
     * (même principe de comptage que maybeUpdateStoryDigest(), qui sait
     * déjà repérer des paquets de 6 messages pour le digest journal --
     * ici, pas de seuil déclencheur, juste une information transmise à
     * chaque appel). Renvoie null pour le format "long" : les chapitres
     * n'ont pas de compte à rebours numérique à suivre, la consigne fixe
     * ci-dessus suffit.
     *
     * IMPORTANT (voir buildMechanicsContext) : ce texte ne doit JAMAIS être
     * écrit une fois pour toutes dans sess.aiConversation (donc jamais via
     * sess.addAiMessage) -- en mode auto il est recalculé et rajouté à part
     * à CHAQUE appel API par runAiNarrator(), sans être sauvegardé, pour
     * rester exact d'un échange à l'autre.
     */
    fun buildStoryProgressNote(sess: DiceSession, story: StoryEntry?): String? {
        val exchangeCount = sess.aiConversation.count { it.role == "assistant" }
        return when (story?.storyLength ?: "long") {
            "short" -> "RAPPEL DE PROGRESSION (ne mentionne jamais ce rappel technique au joueur) : " +
                "$exchangeCount échange(s) narré(s) jusqu'ici, sur une dizaine visée au total."
            "medium" -> "RAPPEL DE PROGRESSION (ne mentionne jamais ce rappel technique au joueur) : " +
                "$exchangeCount échange(s) narré(s) jusqu'ici, sur 20 à 30 visés au total."
            else -> null
        }
    }

    /**
     * Rappel COURT et A JOUR de l'état réel des jauges (menace + énergie de
     * chaque totem/allié), distinct du texte fixe de buildMechanicsContext()
     * qui ne décrit que la RÈGLE. Sans ce rappel, l'IA n'a aucune valeur
     * chiffrée à sa disposition et finit par inventer où en sont les jauges.
     * Recalculé à CHAQUE appel API, jamais figé dans sess.aiConversation
     * (même principe que buildStoryProgressNote/buildPendingQuestNote).
     */
    /**
     * Pouvoirs ACTUELS des totems/alliés, renvoyés à chaque appel : le joueur peut les faire
     * évoluer après leur création, alors que la description initiale reste figée dans le
     * tout premier message de la conversation. Ce rappel a donc priorité.
     */
    private fun buildTotemPowersNote(sess: DiceSession): String {
        val described = sess.customTotems.filter { it.powers.isNotEmpty() || it.special.isNotEmpty() }
        if (described.isEmpty()) return ""
        val lines = described.joinToString(" | ") { t ->
            val powers = if (t.powers.isEmpty()) "" else t.powers.joinToString(", ")
            val special = if (t.special.isEmpty()) "" else " ; capacité spéciale : ${t.special}"
            "${t.label.ifEmpty { t.key }} -> $powers$special"
        }
        return " POUVOIRS ACTUELS DES TOTEMS (à jour, ils priment sur toute description donnée " +
            "plus tôt, car le joueur peut les faire évoluer) : $lines."
    }

    fun buildGaugeStateNote(sess: DiceSession): String {
        val totemLines = if (sess.customTotems.isEmpty()) {
            "aucun totem/allié pour l'instant"
        } else {
            sess.customTotems.joinToString(" | ") { t ->
                val energy = sess.totemEnergy[t.key] ?: 0
                val label = t.label.ifEmpty { t.key }
                "$label: $energy/$TOTEM_ENERGY_THRESHOLD" +
                    if (energy >= TOTEM_ENERGY_THRESHOLD) " (PLEINE, utilisable)" else ""
            }
        }
        return "ÉTAT ACTUEL DES JAUGES (ne mentionne jamais ce rappel technique au joueur -- utilise-le " +
            "seulement pour savoir où en sont réellement les jauges, ne les invente jamais) : " +
            "Jauge de menace : ${sess.threatLevel}/$THREAT_THRESHOLD. " +
            "Jauges totémiques : $totemLines. " +
            "Ces niveaux sont calculés par l'application : tu n'as PAS le droit de les modifier, d'y " +
            "ajouter ou d'y retirer des points, ni d'annoncer un nouveau niveau ; ils ne changent " +
            "qu'après un lancer du joueur." + buildTotemPowersNote(sess)
    }

    val questLaunchedTag = "[[QUETE_LANCEE]]"
    val storyEndedTag = "[[HISTOIRE_TERMINEE]]"
    val chapterEndedTag = "[[FIN_CHAPITRE]]"

    /**
     * Histoire LONGUE uniquement : rappel recalculé et ajouté à CHAQUE appel API (jamais sauvegardé,
     * comme buildPendingQuestNote). L'IA signale la conclusion d'un chapitre par une balise que
     * runAiNarrator() retire du texte avant de l'afficher, et qui déclenche tout de suite le résumé
     * du journal, quel que soit le nombre d'échanges écoulés depuis le dernier résumé.
     * Passe par un rappel par appel (et non par le message "system" initial, figé) pour aussi
     * fonctionner sur les histoires déjà commencées.
     */
    fun buildChapterEndNote(story: StoryEntry?): String? {
        if (normalizeStoryLength(story?.storyLength ?: "long") != LENGTH_LONG) return null
        return "RAPPEL TECHNIQUE (ne le mentionne jamais au joueur) : quand tu conclus un chapitre " +
            "(problématique résolue, message où tu proposes la suite au joueur), termine ce message " +
            "par la balise exacte $chapterEndedTag, seule sur sa propre ligne, à la toute fin du " +
            "message. N'utilise cette balise que pour la conclusion d'un chapitre, jamais en cours " +
            "de chapitre."
    }

    /**
     * Histoire LONGUE uniquement : tant qu'une quête secondaire courte attend la fin du chapitre,
     * rappel recalculé et ajouté à CHAQUE appel API (jamais sauvegardé, comme buildStoryProgressNote).
     * L'IA lance la quête à la conclusion du chapitre et signale le lancement par une balise, que
     * runAiNarrator() retire du texte avant de l'afficher et qui fait passer la quête à "ouverte".
     */
    fun buildPendingQuestNote(sess: DiceSession, story: StoryEntry?): String? {
        if (normalizeStoryLength(story?.storyLength ?: "long") != LENGTH_LONG) return null
        val quest = sess.pendingSideQuests().firstOrNull() ?: return null
        val kindTxt = if (quest.kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou un nouveau totem)"
        return "RAPPEL TECHNIQUE (ne le mentionne jamais au joueur) : une quête secondaire COURTE " +
            "($kindTxt) attend la fin du chapitre en cours. Ne la développe pas avant. Dès que tu " +
            "conclus ce chapitre, lance-la tout de suite dans le même message, sans attendre la " +
            "réponse du joueur : objectif simple et clair, à résoudre en 2 ou 3 échanges, puis " +
            "reprends le fil principal (ce n'est pas un nouveau chapitre). Termine alors ce message " +
            "par la balise exacte $questLaunchedTag."
    }

    fun buildFullPrompt(sess: DiceSession, story: StoryEntry?): String {
        val lines = mutableListOf(
            buildMechanicsContext(sess, story, autoMode = false),
            "",
            buildGaugeStateNote(sess),
            "",
            "--- HISTOIRE DÉJÀ VÉCUE ---"
        )
        val log = sess.storyLogText()
        lines += log.ifEmpty { "(aucun chapitre enregistré pour l'instant, on commence une aventure toute neuve)" }
        return lines.joinToString("\n")
    }

    fun buildAiKickoffMessage(sess: DiceSession, story: StoryEntry?): String {
        val lines = mutableListOf(buildMechanicsContext(sess, story, autoMode = true))
        val log = sess.storyLogText()
        if (log.isNotEmpty()) {
            lines += ""
            lines += "--- HISTOIRE DÉJÀ VÉCUE ---"
            lines += log
            lines += ""
            lines += "Commence maintenant le prochain chapitre de l'histoire, dans la continuité " +
                "directe de ce qui précède."
        } else {
            val startOf = withPreposition("de", story?.protagonistNames ?: emptyList())
            lines += ""
            lines += "Aucun chapitre n'a encore été joué. Commence maintenant le tout premier " +
                "chapitre de cette aventure : plante le décor et présente la situation de départ " +
                "$startOf, puis demande-moi le premier lancer dès que la situation l'exige."
        }
        return lines.joinToString("\n")
    }

    /** Texte à ajouter pour un "?" (quête débloquée) ou "!" (aide). */
    private fun narratorNoteForRecord(record: RollRecord?): String {
        val fateKey = record?.fate ?: return ""
        // Le "?" est entièrement décrit par describeRecord() (dépend de la longueur d'histoire).
        if (fateKey != "exclamation") return ""
        val face = FATE_BY_KEY[fateKey] ?: return ""
        return "${face.emoji} ${face.label} : ${face.desc}"
    }

    /** Texte d'événement envoyé à l'IA pour un lancer. */
    fun buildAiEventText(sess: DiceSession, record: RollRecord): String {
        val parts = mutableListOf(sess.describeRecord(record))
        val note = narratorNoteForRecord(record)
        if (note.isNotEmpty()) parts += note
        return parts.joinToString("\n")
    }
}
