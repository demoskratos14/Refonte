package com.aventure.desdice

/**
 * Palier de contenu de l'histoire, déduit de l'âge du PLUS JEUNE joueur (saisi à la création de
 * l'histoire). Il choisit le cadre de contenu donné à l'IA narratrice (voir PromptBuilder).
 */
enum class AgeTier { CHILD, TEEN, ADULT }

object PlayerAge {

    /** Âge non renseigné (histoires créées avant l'ajout du champ, imports anciens). */
    const val UNKNOWN = 0

    const val MIN_AGE = 3
    const val MAX_AGE = 110

    /** À partir de cet âge : palier adolescent (repère PEGI 12 / « -12 » du CSA). */
    const val TEEN_FROM = 12

    /** À partir de cet âge : palier adulte (repère PEGI 18 / « -18 »). */
    const val ADULT_FROM = 18

    /**
     * Âge utilisable : [UNKNOWN] si absent ou négatif, sinon ramené entre [MIN_AGE] et [MAX_AGE].
     */
    fun normalize(age: Int): Int = if (age <= 0) UNKNOWN else age.coerceIn(MIN_AGE, MAX_AGE)

    /** Palier d'un âge. Âge inconnu = palier enfant, le plus prudent. */
    fun tierOf(age: Int): AgeTier {
        val a = normalize(age)
        return when {
            a >= ADULT_FROM -> AgeTier.ADULT
            a >= TEEN_FROM -> AgeTier.TEEN
            else -> AgeTier.CHILD
        }
    }

    /**
     * Seuil de la jauge de menace selon le palier : plus l'âge est élevé, plus la complication
     * arrive souvent (en tirages normaux du dé de réussite : en moyenne tous les ~42 lancers pour
     * un enfant, ~31 pour un adolescent, ~20 pour un adulte). THREAT_THRESHOLD reste la valeur
     * par défaut d'une session sans histoire.
     */
    fun threatThreshold(playerAge: Int): Int = when (tierOf(playerAge)) {
        AgeTier.CHILD -> THREAT_THRESHOLD
        AgeTier.TEEN -> 8
        AgeTier.ADULT -> 6
    }

    /** Résumé affiché au joueur pour un palier (création d'histoire, changement de palier). */
    fun describe(tier: AgeTier): String = when (tier) {
        AgeTier.CHILD -> "Palier : enfant. Peur faible, monstres rassurants, jamais d'échec définitif, problèmes simples."
        AgeTier.TEEN -> "Palier : adolescent. Peur moyenne, suspense, défaite définitive possible mais rare, problèmes plus élaborés, sans contenu sexuel."
        AgeTier.ADULT -> "Palier : adulte. Peur et danger élevés, défaite définitive possible si les choix et les dés sont mauvais, vraies énigmes, sans contenu sexuel explicite."
    }

    /** Lit un âge saisi à la main (champ texte) : [UNKNOWN] si vide ou illisible. */
    fun parse(text: String): Int = normalize(text.trim().toIntOrNull() ?: UNKNOWN)
}
