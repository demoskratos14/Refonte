// StoryRegistry.kt
// Portage Kotlin de stories.py (registre des histoires) — ÉTAPE 3, partie 1.
//
// game_api.py n'existe plus qu'en Python de référence : il n'y a plus de
// dice_web.py séparé, sa logique a été absorbée dans game_api.py (confirmé
// côté appli). Ce fichier couvre stories.py dans son intégralité, à
// l'identique (mêmes clés JSON dans custom_stories.json, pour rester
// compatible avec des histoires déjà créées/exportées) :
//   - slugification des titres, CRUD des histoires personnalisées
//     (createCustomStory / deleteCustomStory / isCustomStory)
//   - reconstruction d'une StoryEntry à partir des métadonnées (buildStoryEntry)
//   - listing (allStories / allStoryOrder)
//   - export/import d'identité (exportStoryIdentity / parseIdentityImport)
//
// STORIES / STORY_ORDER restent volontairement vides : comme en Python, il
// n'y a plus d'histoire codée en dur, tout passe par le mécanisme personnalisé
// (voir le commentaire en tête de stories.py).
//
// Ce que ce fichier NE couvre PAS (délibérément — c'est le rôle du
// GameViewModel, étape 3 partie 2, pas de StoryRegistry) :
//   - switch_story() de game_api.py : bascule PIP_SYMBOLS/DEFAULT_PIP_SYMBOL
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
//   - list_stories() / index() / select_story() / do_delete_story() /
//     do_create_story() / do_apply_imported_progress() de game_api.py : ce
//     sont de simples wrappers qui combinent StoryRegistry + DiceSession +
//     sauvegarde de l'image de totem (_save_totem_image, qui vit côté
//     ImageUtils/totem_images — pas propre à une histoire) → GameViewModel.
//
// NB image_utils : stories.py appelle image_utils.resize_bg_bytes() avant
// d'écrire l'image de fond sur le disque. ImageUtils.kt étant déjà préparé
// côté appli, createCustomStory() ci-dessous appelle
// ImageUtils.resizeBgBytes(bytes): ByteArray — à ajuster si sa signature
// réelle diffère.

package com.aventure.desdice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

// ------------------------------------------------------------------------
// Registre des histoires codées en dur — volontairement vide (voir
// stories.py). Toute histoire passe par createCustomStory() / buildStoryEntry().
// ------------------------------------------------------------------------

val STORIES: Map<String, StoryEntry> = emptyMap()
val STORY_ORDER: List<String> = emptyList()

// Conservée pour mémoire (miroir de ALLOWED_BG_IMAGE_EXTS) : dans
// stories.py comme ici, l'image de fond est toujours réencodée en JPEG par
// resize_bg_bytes/ImageUtils, donc cette liste n'est pas réellement utilisée
// pour filtrer l'extension.
val ALLOWED_BG_IMAGE_EXTS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")

// ------------------------------------------------------------------------
// Structures de données
// ------------------------------------------------------------------------

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
    val protagonistName: String,
    val saveFile: String,
    // "short" (~10 échanges), "medium" (20-30) ou "long" (illimité, en
    // chapitres) -- voir GameEngine.buildStoryLengthInstructions(). "long"
    // par défaut (y compris pour toute histoire créée avant l'ajout de ce
    // champ, absent de son JSON) : c'est la valeur la plus proche du
    // comportement d'origine (aucune limite), chapitres en plus.
    val storyLength: String = "long"
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
        put("protagonist_name", protagonistName)
        put("save_file", saveFile)
        put("story_length", storyLength)
    }
    companion object {
        /** null si "slug" est absent/vide — miroir du filtre de _load_custom_meta(). */
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
                protagonistName = o.optString("protagonist_name", ""),
                saveFile = o.optString("save_file", "dice_state_$slug.json"),
                storyLength = o.optString("story_length", "long")
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
 * Entrée d'histoire prête à l'affichage/à l'usage — miroir exact du dict
 * renvoyé par _build_story_entry() (histoires personnalisées) ou par une
 * entrée codée en dur de STORIES (toujours vide ici, voir plus haut).
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
    val protagonistRef: String,
    val fixedAlliesLine: String = "",
    val allyHelpText: Map<String, String> = emptyMap(),
    val isCustom: Boolean,
    val loreParagraphs: List<String>,
    val seedStateFile: String? = null,
    val defaultTotem: DefaultTotem?,
    val storyLength: String = "long"
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
    val protagonistName: String,
    val bgImageB64: String,
    val totemImageB64: String,
    val extraTotems: List<ExtraTotemImport>,
    val sideQuests: JSONArray,
    val nextQuestId: Int,
    val storyLog: JSONArray,
    val storySummary: String,
    val storyLength: String
)

// ------------------------------------------------------------------------
// StoryRegistry
// ------------------------------------------------------------------------

/**
 * baseDir doit être le même répertoire que celui utilisé pour les fichiers
 * dice_state_<slug>.json (côté Python : context.getFilesDir(), après le
 * chdir fait par init_app_dir()). custom_stories.json, custom_story_bg/ et
 * totem_images/ sont tous des sous-chemins de baseDir, comme en Python
 * (chemins relatifs) — même schéma, transposé en chemins absolus explicites.
 */
class StoryRegistry(private val baseDir: File) {

    private val customStoriesFile = File(baseDir, "custom_stories.json")
    private val customStoryBgDir = File(baseDir, "custom_story_bg")
    // Pas géré par ce fichier (voir image_utils/game_api._save_totem_image
    // côté Python) : StoryRegistry ne fait ici que LIRE dedans, pour l'export
    // d'identité — l'écriture d'une nouvelle image de totem reste au ViewModel.
    private val totemImagesDir = File(baseDir, "totem_images")

    // ---------- persistance des métadonnées ----------

    private fun loadCustomMeta(): List<StoryMeta> {
        if (!customStoriesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(customStoriesFile.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { StoryMeta.fromJson(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomMeta(metaList: List<StoryMeta>) {
        customStoriesFile.parentFile?.mkdirs()
        customStoriesFile.writeText(JSONArray(metaList.map { it.toJson() }).toString(2))
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
        return Base64.getEncoder().encodeToString(file.readBytes())
    }

    // ---------- CRUD histoires personnalisées ----------

    /**
     * Crée une nouvelle histoire : enregistre son image de fond sur le
     * disque (via ImageUtils.resizeBgBytes — toujours réencodée en JPEG,
     * comme en Python) et ajoute une entrée dans custom_stories.json.
     * Renvoie le slug attribué. Miroir de create_custom_story().
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
        protagonistName: String = "",
        storyLength: String = "long"
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
            protagonistName = protagonistName.trim(),
            saveFile = "dice_state_$slug.json",
            storyLength = storyLength
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
     * échéant. Sans effet (renvoie false) si le slug est inconnu. Miroir de
     * delete_custom_story().
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
        if (saveFile.exists()) saveFile.delete()

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

    /** Vrai si slug correspond à une histoire personnalisée existante. */
    fun isCustomStory(slug: String): Boolean = loadCustomMeta().any { it.slug == slug }

    /**
     * Reconstruit une StoryEntry à partir des métadonnées d'une histoire
     * personnalisée — l'image de fond est relue et réencodée en base64 à
     * chaque appel (jamais mise en cache), comme en Python. Miroir de
     * _build_story_entry().
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
            protagonistRef = meta.protagonistName.trim().ifEmpty { "le personnage principal" },
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
            storyLength = meta.storyLength
        )
    }

    /**
     * Fusionne STORIES (registre codé en dur, vide) et les histoires
     * créées/réimportées par le joueur. À utiliser partout où l'app a
     * besoin de lister/retrouver une histoire. Miroir de all_stories().
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
     * GameViewModel avant l'appel. Miroir de export_story_identity().
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
            put("protagonist_name", meta.protagonistName)
            put("bg_image_b64", bgB64)
            put("totem_image_b64", totemB64)
            put("extra_totems", extraTotems ?: JSONArray())
            put("side_quests", sideQuests ?: JSONArray())
            put("next_quest_id", nextQuestId ?: 1)
            put("story_log", storyLog ?: JSONArray())
            put("story_summary", storySummary ?: "")
            put("story_length", meta.storyLength)
        }
    }

    /**
     * Inverse d'exportStoryIdentity() : relit le JSON collé par le joueur et
     * renvoie un résultat aux champs vides plutôt que de lever une exception
     * en cas de JSON invalide/incomplet — l'appelant n'a pas à se soucier du
     * cas d'erreur. Miroir de parse_identity_import().
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
            protagonistName = data.optString("protagonist_name", "").trim(),
            bgImageB64 = data.optString("bg_image_b64", ""),
            totemImageB64 = data.optString("totem_image_b64", ""),
            extraTotems = extraTotems,
            sideQuests = sideQuests,
            nextQuestId = nextQuestId,
            storyLog = storyLog,
            storySummary = data.optString("story_summary", "").trim(),
            storyLength = data.optString("story_length", "long")
        )
    }
}
