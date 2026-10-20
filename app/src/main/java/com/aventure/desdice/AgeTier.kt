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

    /** Lit un âge saisi à la main (champ texte) : [UNKNOWN] si vide ou illisible. */
    fun parse(text: String): Int = normalize(text.trim().toIntOrNull() ?: UNKNOWN)
}
