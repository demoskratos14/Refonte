// StoryRegistry.kt
// Registre des histoires. Les clés JSON de custom_stories.json restent
// stables, pour rester compatible avec des histoires déjà créées/exportées :
//   - slugification des titres, CRUD des histoires personnalisées
//     (createCustomStory / deleteCustomStory / isCustomStory)
//   - reconstruction d'une StoryEntry à partir des métadonnées (buildStoryEntry)
//   - listing (allStories / allStoryOrder)
//   - export/import d'identité (exportStoryIdentity / parseIdentityImport)
//
// STORIES / STORY_ORDER restent volontairement vides : il n'y a pas d'histoire
// codée en dur, tout passe par le mécanisme personnalisé.
//
// Ce que ce fichier NE couvre PAS (délibérément — c'est le rôle du
// GameEngine / GameViewModel, pas de StoryRegistry) :
//   - switchStory() de GameEngine : bascule PIP_SYMBOLS/DEFAULT_PIP_SYMBOL
//     (objets top-level de DiceSession.kt), choisit le bon fichier de
//     sauvegarde, instancie DiceSession et pose le totem de départ au tout
//     premier lancement — orchestration qui touche à la fois StoryRegistry
//     ET DiceSession, donc au ViewModel de les faire parler ensemble :
//       val entry = storyRegistry.allStories()[slug] ?: return
//       PIP_SYMBOLS.clear(); PIP_SYMBOLS.putAll(entry.pipSymbols)
//       val saveFile = File(baseDir, entry.saveFile)
//       val session = DiceSession(saveFile)
//       val wasLoaded = session.load()
//       if (!wasLoaded && entry.defaultTotem?.label?.isNotEmpty() == true) {
//           val key = session.addCustomTotem(
//               entry.defaultTotem.label,
//               powersText = entry.defaultTotem.powersText,
//               special = entry.defaultTotem.special,
//               imageFilename = entry.defaultTotem.imageFilename
//           )
//           if (key != null) session.setPipSymbol(key)
//       }
//   - listStories / selectStory / deleteStory / createStory /
//     applyImportedProgress de GameEngine : de simples wrappers qui
//     combinent StoryRegistry + DiceSession + sauvegarde de l'image de totem
//     (saveTotemImageBytes, qui vit côté ImageUtils/totem_images — pas propre
//     à une histoire).
//
// NB : createCustomStory() appelle ImageUtils.resizeBgBytes(bytes): ByteArray
// avant d'écrire l'image de fond sur le disque.

package com.aventure.desdice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ------------------------------------------------------------------------
// Registre des histoires codées en dur — volontairement vide.
// Toute histoire passe par createCustomStory() / buildStoryEntry().
// ------------------------------------------------------------------------

val STORIES: Map<String, StoryEntry> = emptyMap()
val STORY_ORDER: List<String> = emptyList()

// L'image de fond est toujours réencodée en JPEG par ImageUtils.resizeBgBytes :
// cette liste n'est donc pas utilisée pour filtrer l'extension.
val ALLOWED_BG_IMAGE_EXTS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

// ------------------------------------------------------------------------
// Structures de données
// ------------------------------------------------------------------------

/**
 * Sérialise une liste de prénoms de héros : nouvelle clé "protagonist_names" (tableau), plus
 * "protagonist_name" (le premier, ou vide) pour qu'une version antérieure de l'appli -- qui ne
 * connaît qu'un seul héros -- retrouve quand même un nom lisible dans le même fichier.
 */
private fun putProtagonistNames(o: JSONObject, names: List<String>) {
    o.put("protagonist_name", names.firstOrNull().orEmpty())
    o.put("protagonist_names", JSONArray(names))
}

/**
 * Lit une liste de prénoms de héros : le tableau "protagonist_names" s'il est présent (histoires
 * à plusieurs héros), sinon repli sur l'ancien champ unique "protagonist_name" (histoires créées
 * avant l'ajout de ce champ).
 */
private fun readProtagonistNames(o: JSONObject): List<String> {
    val arr = o.optJSONArray("protagonist_names")
    if (arr != null) {
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotEmpty() } }
    }
    return o.optString("protagonist_name", "").trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
}

/** Métadonnées brutes d'une histoire personnalisée, telles que stockées dans custom_stories.json. */
data class StoryMeta(
    val slug: String,
    val title: String,
    val subtitle: String,
    val loreText: String,
    val bgImageFile: String?,
    val totemLabel: String,
    val totemImageFilename: String?,
    val totemPowers: String,
    val totemSpecial: String,
    val protagonistNames: List<String>,
    val saveFile: String,
    // "short" (~10 échanges), "medium" (20-30) ou "long" (illimité, en
    // chapitres) -- voir PromptBuilder.buildStoryLengthInstructions(). "long"
    // par défaut (y compris pour toute histoire créée avant l'ajout de ce
    // champ, absent de son JSON) : c'est la valeur la plus proche du
    // comportement d'origine (aucune limite), chapitres en plus.
    val storyLength: String = "long",
    // Thème/valeur optionnel autour duquel construire le récit (patience,
    // fair-play, partage...) -- voir PromptBuilder.buildMechanicsContext().
    // Chaîne vide par défaut (y compris pour toute histoire créée avant
    // l'ajout de ce champ) : aucun objectif moral n'est alors transmis à
    // l'IA, comportement identique à avant l'ajout de ce champ.
    val moralGoal: String = "",
    // Âge du plus jeune joueur (voir AgeTier.kt) : choisit le cadre de contenu donné à l'IA.
    // 0 (PlayerAge.UNKNOWN) par défaut, y compris pour toute histoire créée avant l'ajout
    // de ce champ : palier enfant, le plus prudent.
    val playerAge: Int = PlayerAge.UNKNOWN
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slug", slug)
        put("title", title)
        put("subtitle", subtitle)
        put("lore_text", loreText)
        put("bg_image_file", bgImageFile ?: JSONObject.NULL)
        put("totem_label", totemLabel)
        put("totem_image_filename", totemImageFilename ?: JSONObject.NULL)
        put("totem_powers", totemPowers)
        put("totem_special", totemSpecial)
        putProtagonistNames(this, protagonistNames)
        put("save_file", saveFile)
        put("story_length", storyLength)
        put("moral_goal", moralGoal)
        put("player_age", playerAge)
    }
    companion object {
        /** null si "slug" est absent/vide. */
        fun fromJson(o: JSONObject): StoryMeta? {
            val slug = o.optString("slug", "")
            if (slug.isEmpty()) return null
            return StoryMeta(
                slug = slug,
                title = o.optString("title", ""),
                subtitle = o.optString("subtitle", ""),
                loreText = o.optString("lore_text", ""),
                bgImageFile = if (o.isNull("bg_image_file") || !o.has("bg_image_file")) null else o.optString("bg_image_file"),
                totemLabel = o.optString("totem_label", ""),
                totemImageFilename = if (o.isNull("totem_image_filename") || !o.has("totem_image_filename")) null else o.optString("totem_image_filename"),
                totemPowers = o.optString("totem_powers", ""),
                totemSpecial = o.optString("totem_special", ""),
                protagonistNames = readProtagonistNames(o),
                saveFile = o.optString("save_file", "dice_state_$slug.json"),
                storyLength = o.optString("story_length", "long"),
                moralGoal = o.optString("moral_goal", ""),
                playerAge = PlayerAge.normalize(o.optInt("player_age", PlayerAge.UNKNOWN))
            )
        }
    }
}

/** Totem de départ posé automatiquement au tout premier lancement d'une histoire (voir switch_story). */
data class DefaultTotem(
    val label: String,
    val imageFilename: String?,
    val powersText: String,
    val special: String
)

/**
 * Entrée d'histoire prête à l'affichage/à l'usage, construite par
 * buildStoryEntry() (histoires personnalisées) ou par une entrée codée en
 * dur de STORIES (toujours vide ici, voir plus haut).
 */
data class StoryEntry(
    val slug: String,
    val title: String,
    val headerTitle: String,
    val subtitle: String,
    val saveFile: String,
    val bgImageB64: String,
    val thumbnailB64: String? = null,
    val pipSymbols: Map<String, PipSymbolInfo> = emptyMap(),
    val totems: List<JSONObject> = emptyList(), // toujours vide pour une histoire personnalisée
    val defaultPipSymbol: String = "",
    val protagonistNames: List<String> = emptyList(),
    val fixedAlliesLine: String = "",
    val allyHelpText: Map<String, String> = emptyMap(),
    val isCustom: Boolean,
    val loreParagraphs: List<String>,
    val seedStateFile: String? = null,
    val defaultTotem: DefaultTotem?,
    val storyLength: String = "long",
    val moralGoal: String = "",
    val playerAge: Int = PlayerAge.UNKNOWN
)

/** Un totem acquis en cours de partie, tel qu'embarqué dans un export d'identité. */
data class ExtraTotemImport(
    val label: String,
    val emoji: String,
    val powers: List<String>,
    val special: String,
    val imageB64: String
)

/**
 * Résultat tolérant de parseIdentityImport() : sideQuests/storyLog restent
 * des JSONArray pour pouvoir être passés tels quels à
 * DiceSession.importProgress(), qui attend déjà ce type.
 */
data class StoryIdentityImport(
    val title: String,
    val subtitle: String,
    val loreText: String,
    val totemLabel: String,
    val totemPowers: String,
    val totemSpecial: String,
    val protagonistNames: List<String>,
    val bgImageB64: String,
    val totemImageB64: String,
    val extraTotems: List<ExtraTotemImport>,
    val sideQuests: JSONArray,
    val nextQuestId: Int,
    val storyLog: JSONArray,
    val storySummary: String,
    val storyLength: String,
    val moralGoal: String,
    val playerAge: Int = PlayerAge.UNKNOWN
)

// ------------------------------------------------------------------------
// StoryRegistry
// ------------------------------------------------------------------------

/**
 * baseDir doit être le même répertoire que celui utilisé pour les fichiers
 * dice_state_<slug>.json (context.filesDir). custom_stories.json,
 * custom_story_bg/ et totem_images/ sont tous des sous-chemins de baseDir.
 */
class StoryRegistry(private val baseDir: File) {

    private val customStoriesFile = File(baseDir, "custom_stories.json")
    private val customStoryBgDir = File(baseDir, "custom_story_bg")
    // Pas géré par ce fichier (voir GameEngine.saveTotemImageBytes) :
    // StoryRegistry ne fait ici que LIRE dedans, pour l'export
    // d'identité — l'écriture d'une nouvelle image de totem reste au ViewModel.
    private val totemImagesDir = File(baseDir, "totem_images")

    // ---------- persistance des métadonnées ----------

    private fun loadCustomMeta(): List<StoryMeta> {
        // Fichier abîmé : on essaie la copie de la version précédente (.bak) ; sinon on le met
        // de côté (.corrupt) pour qu'un prochain enregistrement ne l'écrase pas.
        for (candidate in SafeFile.candidates(customStoriesFile)) {
            try {
                val arr = JSONArray(candidate.readText())
                return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { StoryMeta.fromJson(it) } }
            } catch (e: Exception) {
                // on passe au candidat suivant
            }
        }
        SafeFile.quarantine(customStoriesFile)
        return emptyList()
    }

    private fun saveCustomMeta(metaList: List<StoryMeta>) {
        SafeFile.writeAtomic(customStoriesFile, JSONArray(metaList.map { it.toJson() }).toString(2)) {
            JSONArray(it); true
        }
    }

    private fun slugifyStoryTitle(title: String): String {
        var base = title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (base.isEmpty()) base = "histoire"
        val existing = STORIES.keys + loadCustomMeta().map { it.slug }.toSet()
        var slug = base
        var n = 2
        while (slug in existing) {
            slug = "${base}_$n"
            n += 1
        }
        return slug
    }

    private fun readB64(file: File): String {
        if (!file.exists()) return ""
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    // ---------- CRUD histoires personnalisées ----------

    /**
     * Crée une nouvelle histoire : enregistre son image de fond sur le
     * disque (via ImageUtils.resizeBgBytes — toujours réencodée en JPEG)
     * et ajoute une entrée dans custom_stories.json.
     * Renvoie le slug attribué.
     */
    fun createCustomStory(
        title: String,
        subtitle: String,
        loreText: String,
        bgImageBytes: ByteArray,
        totemLabel: String,
        totemImageFilename: String?,
        totemPowers: String = "",
        totemSpecial: String = "",
        protagonistNames: List<String> = emptyList(),
        storyLength: String = "long",
        moralGoal: String = "",
        playerAge: Int = PlayerAge.UNKNOWN
    ): String {
        val finalTitle = title.trim().ifEmpty { "Nouvelle histoire" }
        val slug = slugifyStoryTitle(finalTitle)

        customStoryBgDir.mkdirs()
        val resizedBytes = ImageUtils.resizeBgBytes(bgImageBytes)
        val bgFilename = "$slug.jpg"
        File(customStoryBgDir, bgFilename).writeBytes(resizedBytes)

        val meta = StoryMeta(
            slug = slug,
            title = finalTitle,
            subtitle = subtitle.trim(),
            loreText = loreText.trim(),
            bgImageFile = bgFilename,
            totemLabel = totemLabel.trim().ifEmpty { "Totem de départ" },
            totemImageFilename = totemImageFilename,
            totemPowers = totemPowers.trim(),
            totemSpecial = totemSpecial.trim(),
            protagonistNames = protagonistNames.map { it.trim() }.filter { it.isNotEmpty() },
            saveFile = "dice_state_$slug.json",
            storyLength = storyLength,
            moralGoal = moralGoal.trim(),
            playerAge = PlayerAge.normalize(playerAge)
        )
        val metaList = loadCustomMeta().toMutableList()
        metaList.add(meta)
        saveCustomMeta(metaList)
        return slug
    }

    /**
     * Supprime définitivement une histoire personnalisée : son entrée dans
     * custom_stories.json, son image de fond, sa sauvegarde de partie
     * (dice_state_<slug>.json) et l'image de son totem de départ le cas
     * échéant. Sans effet (renvoie false) si le slug est inconnu..
     */
    fun deleteCustomStory(slug: String): Boolean {
        val metaList = loadCustomMeta()
        val meta = metaList.find { it.slug == slug } ?: return false

        saveCustomMeta(metaList.filter { it.slug != slug })

        meta.bgImageFile?.let {
            val f = File(customStoryBgDir, it)
            if (f.exists()) f.delete()
        }

        val saveFile = File(baseDir, meta.saveFile)
        SafeFile.deleteAll(saveFile)

        // L'image du totem de départ vit dans totem_images/ (partagée avec
        // les totems ajoutés en cours de partie), pas dans custom_story_bg/
        // -- on la supprime aussi, puisqu'elle a été créée spécifiquement
        // pour le totem de départ de CETTE histoire.
        meta.totemImageFilename?.let {
            val f = File(totemImagesDir, it)
            if (f.exists()) f.delete()
        }

        return true
    }

    /**
     * Déplace une histoire personnalisée dans la liste : offset = -1 la fait passer avant sa
     * voisine (vers le début), +1 après (vers la fin). L'ordre est celui du tableau de
     * custom_stories.json, que allStoryOrder() relit tel quel. Renvoie false, sans rien écrire,
     * si le slug est inconnu ou si le déplacement sortirait de la liste.
     */
    fun moveCustomStory(slug: String, offset: Int): Boolean {
        val metaList = loadCustomMeta().toMutableList()
        val from = metaList.indexOfFirst { it.slug == slug }
        if (from < 0) return false
        val to = from + offset
        if (offset == 0 || to < 0 || to >= metaList.size) return false
        val moved = metaList.removeAt(from)
        metaList.add(to, moved)
        saveCustomMeta(metaList)
        return true
    }

    /** Vrai si slug correspond à une histoire personnalisée existante. */
    fun isCustomStory(slug: String): Boolean = loadCustomMeta().any { it.slug == slug }

    /**
     * Reconstruit une StoryEntry à partir des métadonnées d'une histoire
     * personnalisée — l'image de fond est relue et réencodée en base64 à
     * chaque appel (jamais mise en cache).
     */
    fun buildStoryEntry(meta: StoryMeta): StoryEntry {
        val bgB64 = meta.bgImageFile?.let { readB64(File(customStoryBgDir, it)) } ?: ""
        return StoryEntry(
            slug = meta.slug,
            title = meta.title.ifEmpty { meta.slug },
            headerTitle = meta.title.ifEmpty { meta.slug },
            subtitle = meta.subtitle,
            saveFile = meta.saveFile.ifEmpty { "dice_state_${meta.slug}.json" },
            bgImageB64 = bgB64,
            thumbnailB64 = null,
            pipSymbols = emptyMap(),
            totems = emptyList(),
            defaultPipSymbol = "",
            protagonistNames = meta.protagonistNames,
            fixedAlliesLine = "",
            allyHelpText = emptyMap(),
            isCustom = true,
            loreParagraphs = if (meta.loreText.isNotEmpty()) listOf(meta.loreText) else emptyList(),
            seedStateFile = null,
            defaultTotem = DefaultTotem(
                label = meta.totemLabel.ifEmpty { "Totem de départ" },
                imageFilename = meta.totemImageFilename,
                powersText = meta.totemPowers,
                special = meta.totemSpecial
            ),
            storyLength = meta.storyLength,
            moralGoal = meta.moralGoal,
            playerAge = meta.playerAge
        )
    }

    /**
     * Fusionne STORIES (registre codé en dur, vide) et les histoires
     * créées/réimportées par le joueur. À utiliser partout où l'app a
     * besoin de lister/retrouver une histoire..
     */
    fun allStories(): Map<String, StoryEntry> {
        val merged = LinkedHashMap<String, StoryEntry>(STORIES)
        for (meta in loadCustomMeta()) {
            merged[meta.slug] = buildStoryEntry(meta)
        }
        return merged
    }

    /** Comme STORY_ORDER, avec les histoires personnalisées à la suite, dans leur ordre de création. */
    fun allStoryOrder(): List<String> = STORY_ORDER + loadCustomMeta().map { it.slug }

    // ---------- export / import d'identité ----------

    /**
     * Empaquette tout ce qu'il faut pour recréer une histoire personnalisée
     * ailleurs, en un seul JSONObject — l'inverse exact de
     * parseIdentityImport(). Renvoie null si slug est inconnu. extraTotems /
     * sideQuests / storyLog viennent de la session en cours (StoryRegistry
     * ne connaît que les métadonnées figées de l'histoire) — à assembler côté
     * GameViewModel avant l'appel..
     */
    fun exportStoryIdentity(
        slug: String,
        extraTotems: JSONArray? = null,
        sideQuests: JSONArray? = null,
        nextQuestId: Int? = null,
        storyLog: JSONArray? = null,
        storySummary: String? = null
    ): JSONObject? {
        val meta = loadCustomMeta().find { it.slug == slug } ?: return null

        val bgB64 = meta.bgImageFile?.let { readB64(File(customStoryBgDir, it)) } ?: ""
        val totemB64 = meta.totemImageFilename?.let { readB64(File(totemImagesDir, it)) } ?: ""

        return JSONObject().apply {
            put("format", "des-aventure-identite-v1")
            put("title", meta.title)
            put("subtitle", meta.subtitle)
            put("lore_text", meta.loreText)
            put("totem_label", meta.totemLabel)
            put("totem_powers", meta.totemPowers)
            put("totem_special", meta.totemSpecial)
            putProtagonistNames(this, meta.protagonistNames)
            put("bg_image_b64", bgB64)
            put("totem_image_b64", totemB64)
            put("extra_totems", extraTotems ?: JSONArray())
            put("side_quests", sideQuests ?: JSONArray())
            put("next_quest_id", nextQuestId ?: 1)
            put("story_log", storyLog ?: JSONArray())
            put("story_summary", storySummary ?: "")
            put("story_length", meta.storyLength)
            put("moral_goal", meta.moralGoal)
            put("player_age", meta.playerAge)
        }
    }

    /**
     * Inverse d'exportStoryIdentity() : relit le JSON collé par le joueur et
     * renvoie un résultat aux champs vides plutôt que de lever une exception
     * en cas de JSON invalide/incomplet — l'appelant n'a pas à se soucier du
     * cas d'erreur..
     */
    fun parseIdentityImport(rawText: String): StoryIdentityImport {
        val data: JSONObject = try {
            JSONObject(rawText)
        } catch (e: Exception) {
            JSONObject()
        }

        val extraTotems = mutableListOf<ExtraTotemImport>()
        val rawExtra = data.optJSONArray("extra_totems") ?: JSONArray()
        for (i in 0 until rawExtra.length()) {
            val t = rawExtra.optJSONObject(i) ?: continue
            val label = t.optString("label", "").trim()
            if (label.isEmpty()) continue
            val powersArr = t.optJSONArray("powers")
            val powers = mutableListOf<String>()
            if (powersArr != null) {
                for (j in 0 until powersArr.length()) {
                    val p = powersArr.opt(j)
                    if (p is String && p.trim().isNotEmpty()) powers.add(p.trim())
                }
            }
            extraTotems.add(
                ExtraTotemImport(
                    label = label,
                    emoji = t.optString("emoji", "").trim(),
                    powers = powers,
                    special = t.optString("special", "").trim(),
                    imageB64 = t.optString("image_b64", "")
                )
            )
        }

        val rawSideQuests = data.optJSONArray("side_quests") ?: JSONArray()
        val sideQuests = JSONArray()
        var maxQuestId = 0
        for (i in 0 until rawSideQuests.length()) {
            val q = rawSideQuests.optJSONObject(i) ?: continue
            if (q.has("id") && q.has("kind") && q.has("status")) {
                sideQuests.put(q)
                maxQuestId = maxOf(maxQuestId, q.optInt("id", 0))
            }
        }
        val nextQuestId = if (data.has("next_quest_id")) {
            try {
                data.getInt("next_quest_id")
            } catch (e: Exception) {
                maxQuestId + 1
            }
        } else {
            maxQuestId + 1
        }

        val rawStoryLog = data.optJSONArray("story_log") ?: JSONArray()
        val storyLog = JSONArray()
        for (i in 0 until rawStoryLog.length()) {
            val s = rawStoryLog.opt(i)
            if (s is String && s.isNotBlank()) storyLog.put(s)
        }

        return StoryIdentityImport(
            title = data.optString("title", "").trim(),
            subtitle = data.optString("subtitle", "").trim(),
            loreText = data.optString("lore_text", "").trim(),
            totemLabel = data.optString("totem_label", "").trim(),
            totemPowers = data.optString("totem_powers", "").trim(),
            totemSpecial = data.optString("totem_special", "").trim(),
            protagonistNames = readProtagonistNames(data),
            bgImageB64 = data.optString("bg_image_b64", ""),
            totemImageB64 = data.optString("totem_image_b64", ""),
            extraTotems = extraTotems,
            sideQuests = sideQuests,
            nextQuestId = nextQuestId,
            storyLog = storyLog,
            storySummary = data.optString("story_summary", "").trim(),
            storyLength = data.optString("story_length", "long"),
            moralGoal = data.optString("moral_goal", "").trim(),
            playerAge = PlayerAge.normalize(data.optInt("player_age", PlayerAge.UNKNOWN))
        )
    }
}
