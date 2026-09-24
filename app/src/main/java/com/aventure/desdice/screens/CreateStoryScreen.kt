package com.aventure.desdice.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// Palette et reglages identiques aux autres ecrans restyles (Des classiques,
// Cle API) pour que l'appli reste coherente d'une page a l'autre.
private val Ink = Color(0xFF14161A)
private val Red = Color(0xFFE0263C)
private val PageBg = Color(0xFF1B140C)
private val ErrorOnPhoto = Color(0xFFFFC9C9)
private val DividerColor = Color(0x4DFFFFFF)
// Fond des champs de saisie et des boutons "secondaires" (ex-blancs) :
// transparent pour laisser voir l'image. Si le texte noir devient difficile
// a lire sur l'image, remplace par un voile leger, par exemple
// Color.White.copy(alpha = 0.25f).
private val FieldFill = Color.Transparent
private val SecondaryButtonFill = Color(0x4014161A) // voile sombre a 25 % (0x40) ; Color.Transparent = totalement transparent
// Ombre portee des textes poses directement sur l'image.
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1.5f, 2f), 6f)
// Voile sombre par-dessus l'image (haut -> 30 % -> bas). L'image est deja
// sombre (bibliotheque de nuit) : un voile leger suffit. Plus l'alpha (les 2
// premiers chiffres apres 0x) est petit, plus l'image se voit.
private val ScrimTop = Color(0x14000000)
private val ScrimMid = Color(0x33000000)
private val ScrimBottom = Color(0x73000000)
// Dimensions de res/drawable/bg_create_story.jpg (sert a placer le titre sous la fenetre).
private const val BgAspect = 1062f / 699f // hauteur / largeur

/**
 * Equivalent Compose de render_create_story_page (dice_web.py). Reconnecte
 * a GameViewModel (createStoryAwait/addCustomTotemAwait/
 * applyImportedProgress/importIdentity, toutes suspendues) : plus aucun
 * appel a Python/Chaquopy. On utilise les variantes suspendues (plutot que
 * les versions fire-and-forget de GameViewModel) car on a besoin
 * d'attendre la fin de chaque etape avant la suivante, et la fin de tout
 * avant de naviguer (onCreated) ; GameViewModel publie lui-meme
 * sessionState a chaque etape, donc plus besoin de suivre une session
 * "latest" a la main comme du temps de l'appel direct a game_api.
 *
 * Inclut le bloc "importer une identite exportee" (viewModel.importIdentity,
 * qui analyse sans rien appliquer) : une image importee (bg ou totem) est
 * prioritaire tant que l'utilisateur n'en choisit pas explicitement une
 * autre via les selecteurs -- re-choisir une image l'efface au profit de
 * la nouvelle.
 *
 * Habillage : fond plein ecran fixe (res/drawable/bg_create_story.jpg), sections
 * sans carte, champs blancs a bordure noire, boutons "BD" rouges/blancs,
 * polices Bangers / Nunito.
 */
@Composable
fun CreateStoryScreen(
    viewModel: GameViewModel,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val fonts = rememberAppFonts()

    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var loreText by remember { mutableStateOf("") }
    var protagonistName by remember { mutableStateOf("") }
    // "short" (~10 echanges), "medium" (20-30) ou "long" (illimite, en
    // chapitres) -- voir GameEngine.buildStoryLengthInstructions(). "medium"
    // choisi par defaut pour une NOUVELLE histoire (contrairement au "long"
    // utilise en repli cote moteur pour les histoires deja existantes) :
    // c'est un choix delibere a faire a la creation, pas une simple valeur
    // de secours.
    var storyLength by remember { mutableStateOf("medium") }
    var totemLabel by remember { mutableStateOf("") }
    var totemPowers by remember { mutableStateOf("") }
    var totemSpecial by remember { mutableStateOf("") }

    var bgUri by remember { mutableStateOf<Uri?>(null) }
    var totemUri by remember { mutableStateOf<Uri?>(null) }
    // Images issues d'un import d'identite (voir importIdentity ci-dessous) :
    // utilisees a la place d'un Uri tant que l'utilisateur n'a pas
    // explicitement choisi sa propre image via les selecteurs.
    var importedBgBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importedTotemBytes by remember { mutableStateOf<ByteArray?>(null) }

    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Totems supplementaires (au-dela du totem de depart), quetes
    // secondaires et journal issus d'un import d'identite : le totem de
    // depart reste seul pose par do_create_story (voir totemLabel/... plus
    // haut) -- tout ceci est applique A PART, juste apres la creation de
    // l'histoire (voir submit() plus bas), via do_add_custom_totem et
    // do_apply_imported_progress.
    var importedExtraTotems by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var importedSideQuests by remember { mutableStateOf<JSONArray?>(null) }
    var importedNextQuestId by remember { mutableStateOf(1) }
    var importedStoryLog by remember { mutableStateOf<JSONArray?>(null) }
    var importedStorySummary by remember { mutableStateOf("") }

    val pickBgImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) { bgUri = uri; importedBgBytes = null } }

    val pickTotemImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) { totemUri = uri; importedTotemBytes = null } }

    fun uriExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("gif") -> "gif"
            else -> "jpg"
        }
    }

    fun uriBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    fun importIdentity() {
        if (importText.isBlank()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock { importing = true; error = null }
            try {
                val parsed = viewModel.importIdentity(importText)
                mutex.withLock {
                    title = parsed.optString("title", "")
                    subtitle = parsed.optString("subtitle", "")
                    loreText = parsed.optString("lore_text", "")
                    protagonistName = parsed.optString("protagonist_name", "")
                    totemLabel = parsed.optString("totem_label", "")
                    totemPowers = parsed.optString("totem_powers", "")
                    totemSpecial = parsed.optString("totem_special", "")
                    storyLength = parsed.optString("story_length", "long")

                    val bgB64 = parsed.optString("bg_image_b64", "")
                    if (bgB64.isNotEmpty()) {
                        importedBgBytes = Base64.decode(bgB64, Base64.DEFAULT)
                        bgUri = null
                    }
                    val totemB64 = parsed.optString("totem_image_b64", "")
                    if (totemB64.isNotEmpty()) {
                        importedTotemBytes = Base64.decode(totemB64, Base64.DEFAULT)
                        totemUri = null
                    }

                    val extraArr = parsed.optJSONArray("extra_totems")
                    val extras = mutableListOf<JSONObject>()
                    if (extraArr != null) {
                        for (i in 0 until extraArr.length()) extras.add(extraArr.getJSONObject(i))
                    }
                    importedExtraTotems = extras

                    importedSideQuests = parsed.optJSONArray("side_quests")
                    importedNextQuestId = parsed.optInt("next_quest_id", 1)
                    importedStoryLog = parsed.optJSONArray("story_log")
                    importedStorySummary = parsed.optString("story_summary", "")

                    if (bgB64.isEmpty() && title.isEmpty()) {
                        error = "Le texte collé ne ressemble pas à une identité exportée valide."
                    }
                }
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur d'import : ${e.message}" }
            } finally {
                mutex.withLock { importing = false }
            }
        }
    }

    // GetContent() plutot que OpenDocument() : mieux supporte par les
    // gestionnaires de fichiers des surcouches constructeur (MIUI...) qui
    // n'implementent pas toujours completement le protocole DocumentsProvider
    // qu'exige OpenDocument(). Declaree apres importIdentity() (dont elle a
    // besoin) : une fonction locale Kotlin ne peut pas etre appelee avant sa
    // declaration dans le meme bloc.
    val pickIdentityFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: ""
                importText = text
                // Import automatique : contrairement au collage manuel (ou
                // l'utilisateur peut vouloir relire/corriger avant de valider),
                // un fichier choisi via l'explorateur est deja le contenu final --
                // pas besoin d'un appui supplementaire sur "Importer".
                if (text.isNotBlank()) importIdentity()
            } catch (e: Exception) {
                error = "Impossible de lire le fichier choisi : ${e.message}"
            }
        }
    }

    fun submit() {
        if (title.isBlank()) {
            error = "Le titre est obligatoire."
            return
        }
        val bg = bgUri
        val importedBg = importedBgBytes
        if (bg == null && importedBg == null) {
            error = "Choisis une image de fond (ou importe une identité qui en contient une)."
            return
        }
        scope.launch(Dispatchers.IO) {
            mutex.withLock { saving = true; error = null }
            try {
                // L'image explicitement choisie (bgUri) est toujours
                // prioritaire sur celle d'un import -- coherent avec le
                // fait que la choisir efface deja importedBgBytes plus haut.
                // bgExt n'est plus necessaire : GameEngine.createStory ne
                // le prend plus en parametre (l'image de fond est de toute
                // facon toujours reencodee en JPEG par StoryRegistry).
                val bgBytes = bg?.let { uriBytes(it) } ?: importedBg!!
                val totemBytes = totemUri?.let { uriBytes(it) }
                    ?: importedTotemBytes ?: ByteArray(0)
                val totemFilename = when {
                    totemUri != null -> "totem.${uriExtension(totemUri!!)}"
                    importedTotemBytes != null -> "totem.png"
                    else -> ""
                }

                // Variante suspendue (plutot que viewModel.createStory, qui
                // est "fire-and-forget" -- lance sa propre coroutine sans
                // moyen d'attendre sa fin) : on a besoin de savoir que la
                // creation est terminee avant de naviguer (onCreated).
                // GameEngine garde la session courante en interne, donc pas
                // besoin de recuperer/repasser un JSON de session a la main
                // comme du temps de l'appel direct a game_api.
                viewModel.createStoryAwait(
                    title = title,
                    subtitle = subtitle,
                    loreText = loreText,
                    totemLabel = totemLabel,
                    totemPowers = totemPowers,
                    totemSpecial = totemSpecial,
                    bgImageBytes = bgBytes,
                    totemImageBytes = totemBytes,
                    totemImageFilename = totemFilename,
                    protagonistName = protagonistName,
                    storyLength = storyLength
                )
                // Le totem de depart est deja pose par createStoryAwait (un
                // seul, comme toujours). Les totems importes en plus (acquis
                // en cours de partie sur l'appareil d'origine) sont ajoutes
                // ensuite, un par un, exactement comme TotemManagerDialog le
                // fait en cours de partie -- ca ne cree jamais un deuxieme
                // totem "de depart".
                for (extra in importedExtraTotems) {
                    val extraLabel = extra.optString("label", "")
                    if (extraLabel.isBlank()) continue
                    val powersArr = extra.optJSONArray("powers")
                    val powersText = buildList {
                        if (powersArr != null) {
                            for (i in 0 until powersArr.length()) add(powersArr.optString(i))
                        }
                    }.joinToString(", ")
                    val extraImgB64 = extra.optString("image_b64", "")
                    val extraImgBytes = if (extraImgB64.isNotEmpty()) {
                        Base64.decode(extraImgB64, Base64.DEFAULT)
                    } else {
                        ByteArray(0)
                    }
                    val extraImgFilename = if (extraImgBytes.isNotEmpty()) "totem.png" else ""
                    viewModel.addCustomTotemAwait(
                        label = extraLabel,
                        powers = powersText,
                        special = extra.optString("special", ""),
                        emoji = extra.optString("emoji", ""),
                        imageBytes = extraImgBytes,
                        imageFilename = extraImgFilename
                    )
                }

                // Quetes secondaires et journal importes : appliques en un
                // seul appel, apres les totems, pour que la session finale
                // charge dans le ViewModel contienne deja tout.
                val questsArr = importedSideQuests
                val logArr = importedStoryLog
                if ((questsArr != null && questsArr.length() > 0) ||
                    (logArr != null && logArr.length() > 0) ||
                    importedStorySummary.isNotBlank()
                ) {
                    viewModel.applyImportedProgress(
                        questsArr ?: JSONArray(),
                        importedNextQuestId,
                        logArr ?: JSONArray(),
                        importedStorySummary
                    )
                }

                // createStoryAwait/addCustomTotemAwait/applyImportedProgress
                // publient deja chacun sessionState au fil de l'eau (voir
                // GameViewModel) : la version la plus a jour (totems/quetes/
                // journal importes compris) est donc deja chargee avant de
                // rafraichir la liste, pour que l'ecran de jeu n'affiche pas
                // l'etat de l'histoire precedente.
                viewModel.loadStories()
                onCreated()
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur : ${e.message}" }
            } finally {
                mutex.withLock { saving = false }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        // Hauteur a laquelle l'image est affichee en mode Crop : la lune et le haut
        // de la fenetre sont dans le premier tiers de cette hauteur.
        val imageHeight = maxOf(maxHeight, maxWidth * BgAspect)

        // --- Fond plein ecran (fixe, le contenu defile par-dessus) ---
        Image(
            painter = rememberBackgroundPainter(BackgroundSlot.CREATE_STORY),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Calee en haut et decalee vers la droite pour garder la lune visible.
            alignment = BiasAlignment(0.5f, -1f),
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to ScrimTop,
                            0.3f to ScrimMid,
                            1.0f to ScrimBottom
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding() // le clavier ne cache pas le champ en cours de saisie
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // On laisse la fenetre et la lune visibles en haut de l'ecran.
            Spacer(Modifier.height(imageHeight * 0.34f))

            Text(
                text = "\uD83D\uDCD6 Nouvelle histoire",
                fontFamily = fonts.display,
                color = Color.White,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 3f),
                        blurRadius = 10f
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 14.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Import d'une identite exportee ---
                SectionTitle("Importer une identité exportée (optionnel)", fonts)
                ComicButton(
                    text = if (importing) "Import en cours…" else "Choisir un fichier",
                    onClick = { pickIdentityFile.launch("*/*") },
                    fonts = fonts,
                    secondary = true,
                    enabled = !importing && !saving
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(2.dp)
                        .background(DividerColor)
                )

                // --- Titre / sous-titre / univers ---
                ComicTextField(
                    label = "Titre",
                    value = title,
                    onValueChange = { title = it },
                    fonts = fonts,
                    enabled = !saving,
                    singleLine = true
                )
                ComicTextField(
                    label = "Sous-titre",
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    fonts = fonts,
                    enabled = !saving,
                    singleLine = true
                )
                ComicTextField(
                    label = "Description de l'univers (envoyée à l'IA narratrice)",
                    value = loreText,
                    onValueChange = { loreText = it },
                    fonts = fonts,
                    enabled = !saving,
                    minHeight = 110.dp
                )
                ComicTextField(
                    label = "Prénom du héros (optionnel — laisser vide si le joueur doit le donner en jeu)",
                    value = protagonistName,
                    onValueChange = { protagonistName = it },
                    fonts = fonts,
                    enabled = !saving,
                    singleLine = true
                )

                // --- Longueur de l'histoire ---
                SectionTitle("Longueur de l'histoire", fonts)
                Text(
                    text = "Détermine combien de temps dure l'aventure, et si l'IA doit " +
                        "la découper en chapitres.",
                    fontFamily = fonts.body,
                    fontSize = 13.sp,
                    color = Color.White,
                    style = TextStyle(shadow = TextShadow)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ComicButton(
                        text = "Courte\n(~10 échanges)",
                        onClick = { storyLength = "short" },
                        fonts = fonts,
                        secondary = storyLength != "short",
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                    ComicButton(
                        text = "Moyenne\n(20 à 30)",
                        onClick = { storyLength = "medium" },
                        fonts = fonts,
                        secondary = storyLength != "medium",
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                    ComicButton(
                        text = "Longue\n(chapitres)",
                        onClick = { storyLength = "long" },
                        fonts = fonts,
                        secondary = storyLength != "long",
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (storyLength == "long") {
                    Text(
                        text = "Format long : pas de limite totale, mais l'IA racontera " +
                            "l'histoire en chapitres (chacun avec une vraie fin) et te " +
                            "demandera avant de commencer le suivant.",
                        fontFamily = fonts.body,
                        fontSize = 12.sp,
                        color = Color.White,
                        style = TextStyle(shadow = TextShadow)
                    )
                }

                // --- Image de fond ---
                SectionTitle("Image de fond", fonts)
                ComicButton(
                    text = if (bgUri == null && importedBgBytes == null) "Choisir une image" else "Changer l'image",
                    onClick = {
                        pickBgImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    fonts = fonts,
                    secondary = true,
                    enabled = !saving
                )
                bgUri?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(3.dp, Ink, RoundedCornerShape(10.dp))
                    )
                }
                if (bgUri == null) {
                    importedBgBytes?.let { bytes ->
                        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(3.dp, Ink, RoundedCornerShape(10.dp))
                            )
                        }
                    }
                }

                // --- Totem de depart ---
                SectionTitle("Totem de départ", fonts)
                ComicTextField(
                    label = "Nom du totem",
                    value = totemLabel,
                    onValueChange = { totemLabel = it },
                    fonts = fonts,
                    enabled = !saving,
                    singleLine = true
                )
                ComicTextField(
                    label = "Pouvoirs (séparés par des virgules)",
                    value = totemPowers,
                    onValueChange = { totemPowers = it },
                    fonts = fonts,
                    enabled = !saving,
                    minHeight = 60.dp
                )
                ComicTextField(
                    label = "Capacité spéciale (optionnel)",
                    value = totemSpecial,
                    onValueChange = { totemSpecial = it },
                    fonts = fonts,
                    enabled = !saving,
                    minHeight = 60.dp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ComicButton(
                        text = if (totemUri == null && importedTotemBytes == null) {
                            "Image du totem (optionnel)"
                        } else {
                            "Changer l'image"
                        },
                        onClick = {
                            pickTotemImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        fonts = fonts,
                        secondary = true,
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                    totemUri?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(2.dp, Ink, RoundedCornerShape(8.dp))
                        )
                    }
                    if (totemUri == null) {
                        importedTotemBytes?.let { bytes ->
                            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, Ink, RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        color = ErrorOnPhoto,
                        fontFamily = fonts.body,
                        style = TextStyle(shadow = TextShadow)
                    )
                }

                Spacer(Modifier.height(4.dp))
                if (saving) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    ComicButton(
                        text = "Créer l'histoire",
                        onClick = { submit() },
                        fonts = fonts
                    )
                }
            }
        }
    }
}

/** Intitule de section : Bangers blanc pose directement sur l'image. */
@Composable
private fun SectionTitle(text: String, fonts: AppFonts) {
    Text(
        text = text,
        fontFamily = fonts.display,
        fontSize = 19.sp,
        letterSpacing = 0.5.sp,
        color = Color.White,
        style = TextStyle(shadow = TextShadow),
        modifier = Modifier.padding(top = 6.dp)
    )
}

/**
 * Champ de saisie "BD" : libelle en blanc au-dessus, champ blanc a bordure
 * noire. `minHeight` > 0 donne un champ multiligne (sinon `singleLine`).
 */
@Composable
private fun ComicTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minHeight: Dp = 0.dp,
    // Quand renseigne, le champ ne grandit plus au-dela de cette hauteur :
    // le texte defile A L'INTERIEUR de la zone (comme un <textarea> HTML
    // avec "resize: none"), plutot que de pousser le reste de la page vers
    // le bas -- utile pour le collage d'un JSON potentiellement tres long.
    maxHeight: Dp = Dp.Unspecified
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontFamily = fonts.bodyBold,
            fontSize = 14.sp,
            color = Color.White,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        val heightModifier = if (maxHeight != Dp.Unspecified) {
            Modifier.heightIn(min = minHeight, max = maxHeight)
        } else {
            Modifier.heightIn(min = minHeight)
        }
        val scrollModifier = if (maxHeight != Dp.Unspecified) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            enabled = enabled,
            textStyle = TextStyle(
                fontFamily = fonts.body,
                fontSize = 15.sp,
                color = Color.White,
                shadow = TextShadow
            ),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.6f)
                .then(heightModifier)
                .clip(RoundedCornerShape(8.dp))
                .background(FieldFill)
                .border(2.dp, Ink, RoundedCornerShape(8.dp))
                .then(scrollModifier)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

/** Reproduit .btn / .btn.secondary de l'ancien CSS : fond plat, bordure
 * epaisse, "ombre" facon BD (rectangle decale, pas de flou). */
@Composable
private fun ComicButton(
    text: String,
    onClick: () -> Unit,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = if (secondary) SecondaryButtonFill else Red
    val textColor = Color.White
    val alpha = if (enabled) 1f else 0.5f

    Box(modifier = modifier.fillMaxWidth()) {
        // "Ombre" decalee facon BD : uniquement si le bouton a un fond. Sur un
        // bouton transparent, elle serait visible A TRAVERS le bouton (rectangle
        // noir derriere le texte noir).
        if (bg.alpha >= 0.99f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink.copy(alpha = alpha))
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                // bg.alpha * alpha (et non alpha seul) : copy(alpha = 1f) sur un fond
                // transparent le rendait noir opaque.
                .background(bg.copy(alpha = bg.alpha * alpha))
                .border(3.dp, Ink.copy(alpha = alpha), RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = fonts.display,
                color = textColor.copy(alpha = alpha),
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                // Meme ombre que les titres des champs : le texte ressort sur l'image.
                style = if (secondary) TextStyle(shadow = TextShadow) else TextStyle.Default
            )
        }
    }
}
