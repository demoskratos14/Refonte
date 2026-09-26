package com.aventure.desdice.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppIcon
import com.aventure.desdice.ui.AppIconStore
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.BackgroundStore
import com.aventure.desdice.ui.DiceSoundPlayer
import com.aventure.desdice.ui.MusicPlayer
import com.aventure.desdice.ui.SoundPrefs
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.FontOption
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.listAssetFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.ui.TextStyleChoice
import kotlinx.coroutines.launch

// Palette identique aux autres écrans. Noms préfixés "S" et privés pour ne pas
// entrer en conflit avec les vals privées de ConfigureKeyScreen / StorySelectorScreen.
private val SInk = Color(0xFF14161A)
private val SRed = Color(0xFFE0263C)
private val SPageBg = Color(0xFF1B140C)
private val SCardBg = Color(0xBF14100C)          // carte sombre translucide (le fond est chargé)
private val SCardBorder = Color(0x4DFFFFFF)
private val SLineColor = Color(0x4DFFFFFF)
private val STextShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1.5f, 2f), 6f)

/** Titres des pages, dans l'ordre. */
private val PAGE_TITLES = listOf("Polices", "Photos", "Icône", "Sons")
private const val PAGE_COUNT = 4

/**
 * Bouton d'accès aux Réglages : icône engrenage (res/drawable/ic_settings.png).
 * À poser dans un Box, p. ex. avec Modifier.align(Alignment.TopEnd).
 */
@Composable
fun SettingsGearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp) // zone tactile confortable
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_settings),
            contentDescription = "Réglages",
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * Écran Réglages en 4 pages qu'on fait glisser (ou qu'on change en touchant
 * l'onglet latéral) :
 *   - Polices : police du texte de l'appli et police des titres, parmi les
 *     fichiers de app/src/main/assets/fonts ;
 *   - Photos : images de fond des pages de l'appli ;
 *   - Icône : icône de l'appli parmi 9 (l'originale + 8), un clic suffit ;
 *   - Sons : couper ou baisser le bruit des dés (res/raw/dice_roll.mp3) et la
 *     musique de fond (fichiers res/raw/music_*).
 *   Couleur et taille du texte des échanges avec l'IA (bulles de conversation et
 *   champ de saisie du joueur, voir AiPanel.kt) se règlent aussi ici, sur la
 *   page « Polices ».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val fonts = rememberAppFonts()
    val fontPrefs = remember(context) { FontPrefs.get(context) }
    val fontOptions = remember(context) { listAssetFonts(context) }
    val bgStore = remember(context) { BackgroundStore.get(context) }

    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SPageBg)
    ) {
        Image(
            painter = rememberBackgroundPainter(BackgroundSlot.SETTINGS),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Voile sombre : le fond (engrenages) est très détaillé, il faut un contraste solide.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x8C000000),
                            1.0f to Color(0xB8000000)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // --- En-tête (fixe) ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onBack)
                ) {
                    Text(
                        text = "\u2190",
                        color = Color.White,
                        fontSize = 28.sp,
                        style = TextStyle(shadow = STextShadow)
                    )
                }
                Text(
                    text = "Réglages",
                    fontFamily = fonts.display,
                    color = Color.White,
                    fontSize = 30.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 3f), 10f)),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(44.dp)) // équilibre visuel avec le bouton retour
            }

            // --- 4 pages, à faire glisser : 0 = polices, 1 = images de fond, 2 = icône, 3 = sons ---
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // On garde une marge plus large du côté de l'onglet latéral.
                            .padding(
                                start = if (page >= 1) 38.dp else 16.dp,
                                end = if (page < PAGE_COUNT - 1) 38.dp else 16.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (page == 0) {
                        // --- Écriture : police, couleur et taille, une section par catégorie ---
                        if (fontOptions.isEmpty()) {
                            SettingsCard(title = "\uD83D\uDD24 Polices", fonts = fonts) {
                                Text(
                                    text = "Aucune police (.ttf / .otf) trouvée dans app/src/main/assets/fonts. " +
                                        "Tu peux quand même régler la couleur et la taille ci-dessous.",
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontFamily = fonts.body,
                                    style = TextStyle(shadow = STextShadow)
                                )
                            }
                        }
                        TextStyleSection(
                            icon = "\uD83D\uDD24",
                            title = "Titres",
                            description = "Police, couleur et taille des titres (histoires, en-têtes, boutons) " +
                                "dans toute l'application.",
                            choice = fontPrefs.title,
                            fontOptions = fontOptions,
                            fontPrefs = fontPrefs,
                            defaultFontFile = FontPrefs.DEFAULT_TITLE_FILE,
                            previewText = "Le Livre des Mille Histoires",
                            previewOnDark = true,
                            fonts = fonts
                        )
                        TextStyleSection(
                            icon = "\uD83D\uDCDD",
                            title = "Texte courant",
                            description = "Police, couleur et taille du texte de l'interface (descriptions, " +
                                "boutons, écrans de réglages…) et de la narration.",
                            choice = fontPrefs.body,
                            fontOptions = fontOptions,
                            fontPrefs = fontPrefs,
                            defaultFontFile = FontPrefs.DEFAULT_BODY_FILE,
                            previewText = "Le dé roule sur la table… Tu avances dans la forêt sombre, " +
                                "et quelque chose bouge entre les arbres.",
                            previewOnDark = true,
                            fonts = fonts
                        )
                        TextStyleSection(
                            icon = "\uD83D\uDD8B\uFE0F",
                            title = "Réponses de l'IA",
                            description = "Police, couleur et taille du texte des échanges avec l'IA " +
                                "narratrice (bulles de conversation et champ où tu écris tes réponses).",
                            choice = fontPrefs.reply,
                            fontOptions = fontOptions,
                            fontPrefs = fontPrefs,
                            defaultFontFile = FontPrefs.DEFAULT_BODY_FILE,
                            previewText = "Tu avances prudemment dans la forêt sombre, une brindille craque " +
                                "sous ton pied.",
                            previewOnDark = false,
                            fonts = fonts
                        )
                        } else if (page == 1) {
                        // --- Images de fond ---
                        SettingsCard(title = "\uD83D\uDDBC\uFE0F Images de fond", fonts = fonts) {
                            Text(
                                text = "Choisis une image de ta galerie pour chaque page. Une image en paysage " +
                                    "est complétée par un fond flouté pour remplir l'écran en portrait.",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.95f),
                                fontFamily = fonts.body,
                                style = TextStyle(shadow = STextShadow),
                                modifier = Modifier.padding(bottom = 14.dp)
                            )
                            BackgroundSlot.values().forEachIndexed { index, slot ->
                                if (index > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 14.dp)
                                            .height(2.dp)
                                            .background(SLineColor)
                                    )
                                }
                                BackgroundRow(slot = slot, store = bgStore, fonts = fonts)
                            }
                        }
                        } else if (page == 2) {
                            AppIconCard(fonts = fonts)
                        } else {
                            SoundCard(fonts = fonts)
                            MusicCard(fonts = fonts)
                        }

                        SettingsButton(text = "Retour", onClick = onBack, fonts = fonts, secondary = true)
                        Spacer(Modifier.height(8.dp))
                    }

                    // Onglets sur les côtés : indiquent les pages voisines (toucher = y aller).
                    if (page >= 1) {
                        SideTab(
                            text = PAGE_TITLES[page - 1],
                            arrow = "\u2039",
                            rotation = 270f,
                            fonts = fonts,
                            onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 4.dp)
                        )
                    }
                    if (page < PAGE_COUNT - 1) {
                        SideTab(
                            text = PAGE_TITLES[page + 1],
                            arrow = "\u203A",
                            rotation = 90f,
                            fonts = fonts,
                            onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Composants
// ------------------------------------------------------------------

@Composable
private fun SettingsCard(
    title: String,
    fonts: AppFonts,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SCardBg)
            .border(2.dp, SCardBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            fontFamily = fonts.display,
            color = Color.White,
            fontSize = 22.sp,
            letterSpacing = 1.sp,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

/** Une ligne « page » : miniature du fond actuel + choisir une image / revenir à l'image par défaut. */
@Composable
private fun BackgroundRow(slot: BackgroundSlot, store: BackgroundStore, fonts: AppFonts) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                message = "Chargement…"
                message = if (store.setFromUri(slot, uri)) null else "Image illisible, essaie-en une autre."
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = rememberBackgroundPainter(slot),
            contentDescription = slot.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 60.dp, height = 100.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, SCardBorder, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slot.label,
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SettingsButton(
                text = "Choisir une image",
                onClick = { launcher.launch("image/*") },
                fonts = fonts
            )
            if (store.hasCustom(slot)) {
                Spacer(Modifier.height(8.dp))
                SettingsButton(
                    text = "Image par défaut",
                    onClick = { store.reset(slot); message = null },
                    fonts = fonts,
                    secondary = true
                )
            }
            message?.let {
                Text(
                    text = it,
                    fontSize = 13.sp,
                    color = Color(0xFFFFC9C9),
                    fontFamily = fonts.body,
                    style = TextStyle(shadow = STextShadow),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** Page « Icône » : grille 3 x 3 des icônes proposées, un clic applique l'icône. */
@Composable
private fun AppIconCard(fonts: AppFonts) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIconStore.current(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    // L'icône d'origine est dessinée depuis la ressource du lanceur (icône adaptative comprise).
    val originalPreview = remember(context) {
        try {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(256, 256)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    SettingsCard(title = "\uD83C\uDFA8 Icône de l'application", fonts = fonts) {
        Text(
            text = "Touche une icône pour l'appliquer. Selon ton téléphone, le changement " +
                "peut mettre quelques secondes à apparaître sur l'écran d'accueil.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        AppIcon.values().toList().chunked(3).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { icon ->
                    IconTile(
                        icon = icon,
                        selected = icon == selected,
                        originalPreview = originalPreview,
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                AppIconStore.apply(context, icon)
                                selected = icon
                                error = null
                            } catch (e: Exception) {
                                error = "Impossible de changer l'icône : les alias du manifeste " +
                                    "ne sont pas en place."
                            }
                        }
                    )
                }
            }
        }
        error?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = Color(0xFFFFC9C9),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** Page « Sons », 2e carte : couper ou baisser la musique de fond des menus. */
@Composable
private fun MusicCard(fonts: AppFonts) {
    val context = LocalContext.current
    val soundPrefs = remember(context) { SoundPrefs.get(context) }
    val hasTracks = remember { MusicPlayer.hasTracks() }
    val muted = soundPrefs.musicMuted
    val volume = soundPrefs.musicVolume

    SettingsCard(title = "\uD83C\uDFB5 Musique", fonts = fonts) {
        Text(
            text = "Un morceau au hasard se lance à l'ouverture de l'appli et s'arrête quand tu entres " +
                "dans une histoire. Dans la page de jeu, tu peux la relancer si tu en as envie.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        if (!hasTracks) {
            Text(
                text = "Aucun morceau trouvé. Ajoute des fichiers nommés music_quelquechose.mp3 (ou .ogg) " +
                    "dans app/src/main/res/raw.",
                fontSize = 13.sp,
                color = Color(0xFFFFC9C9),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (muted) "Musique coupée" else "Musique activée",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !muted,
                onCheckedChange = {
                    soundPrefs.changeMusicMuted(!it)
                    MusicPlayer.onMusicMutedChanged(context)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SRed,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .height(2.dp)
                .background(SLineColor)
        )

        Text(
            text = "Volume : ${(volume * 100).toInt()} %",
            fontFamily = fonts.bodyBold,
            color = Color.White.copy(alpha = if (muted) 0.5f else 1f),
            style = TextStyle(shadow = STextShadow)
        )
        Slider(
            value = volume,
            onValueChange = {
                soundPrefs.changeMusicVolume(it)
                MusicPlayer.updateVolume(context)
            },
            valueRange = 0f..1f,
            enabled = !muted,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledThumbColor = Color.White.copy(alpha = 0.5f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (hasTracks && !muted) {
            Spacer(Modifier.height(6.dp))
            SettingsButton(
                text = if (MusicPlayer.isPlaying) "Autre morceau" else "Lancer la musique",
                onClick = { MusicPlayer.startRandom(context) },
                fonts = fonts,
                secondary = true
            )
        }
    }
}

/** Page « Sons » : couper ou baisser le bruit des dés. */
@Composable
private fun SoundCard(fonts: AppFonts) {
    val context = LocalContext.current
    val soundPrefs = remember(context) { SoundPrefs.get(context) }
    val muted = soundPrefs.diceMuted
    val volume = soundPrefs.diceVolume

    SettingsCard(title = "\uD83D\uDD0A Bruit des dés", fonts = fonts) {
        Text(
            text = "Coupe le bruit des dés, ou baisse-le pour jouer plus discrètement. " +
                "Le volume choisi est conservé quand tu coupes le son.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (muted) "Son coupé" else "Son activé",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !muted,
                onCheckedChange = { soundPrefs.setMuted(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SRed,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .height(2.dp)
                .background(SLineColor)
        )

        Text(
            text = "Volume : ${(volume * 100).toInt()} %",
            fontFamily = fonts.bodyBold,
            color = Color.White.copy(alpha = if (muted) 0.5f else 1f),
            style = TextStyle(shadow = STextShadow)
        )
        Slider(
            value = volume,
            onValueChange = { soundPrefs.setVolume(it) },
            valueRange = 0f..1f,
            enabled = !muted,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledThumbColor = Color.White.copy(alpha = 0.5f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        SettingsButton(
            text = "Écouter",
            onClick = { DiceSoundPlayer.play(context) },
            fonts = fonts,
            secondary = true
        )
        if (muted) {
            Text(
                text = "Le son est coupé : rien ne sera joué tant que tu ne le réactives pas.",
                fontSize = 13.sp,
                color = Color(0xFFFFC9C9),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Section « police + couleur + taille » réglable pour UNE catégorie de texte
 * ([FontPrefs.title], [FontPrefs.body] ou [FontPrefs.reply]). Entièrement autonome : les
 * trois cartes de Réglages > Écriture sont trois appels à cette même fonction, chacune liée à
 * une catégorie indépendante — changer l'une ne touche pas aux deux autres.
 *
 * [previewOnDark] choisit le fond de l'aperçu : sur fond sombre pour les titres et le texte
 * courant (comme le reste de la carte de réglages), sur fond clair pour les réponses IA
 * (comme les bulles de conversation où ce style s'applique réellement).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextStyleSection(
    icon: String,
    title: String,
    description: String,
    choice: TextStyleChoice,
    fontOptions: List<FontOption>,
    fontPrefs: FontPrefs,
    defaultFontFile: String,
    previewText: String,
    previewOnDark: Boolean,
    fonts: AppFonts
) {
    val entries = remember(fontOptions) {
        fontOptions.map { DropdownEntry(it.file, it.label, fontPrefs.familyFor(it.file) ?: FontFamily.Default) }
    }
    // Sans choix enregistré, on affiche la police par défaut de la catégorie si présente.
    val effectiveFile = choice.fontFile ?: defaultFontFile
    val current = entries.firstOrNull { it.value == effectiveFile }
    val previewFamily = current?.family ?: FontFamily.Default

    SettingsCard(title = "$icon $title", fonts = fonts) {
        Text(
            text = description,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        if (fontOptions.isNotEmpty()) {
            Text(
                text = "Police",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SettingsDropdown(
                entries = entries,
                selectedLabel = current?.label ?: "Police du système",
                selectedFamily = previewFamily,
                onSelect = { file -> choice.setFont(file) }
            )
            if (choice.fontFile != null) {
                Spacer(Modifier.height(10.dp))
                SettingsButton(
                    text = "Rétablir la police par défaut",
                    onClick = { choice.setFont(null) },
                    fonts = fonts,
                    secondary = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(2.dp)
                    .background(SLineColor)
            )
        }

        Text(
            text = "Couleur",
            fontFamily = fonts.bodyBold,
            color = Color.White,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ColorSwatch(
                color = null,
                label = "Auto",
                selected = choice.color == null,
                onClick = { choice.setColor(null) }
            )
            FontPrefs.TEXT_COLOR_PRESETS.forEach { (label, swatch) ->
                ColorSwatch(
                    color = swatch,
                    label = label,
                    selected = choice.color == swatch,
                    onClick = { choice.setColor(swatch) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .height(2.dp)
                .background(SLineColor)
        )

        Text(
            text = "Taille : ${choice.sizeSp.toInt()} sp",
            fontFamily = fonts.bodyBold,
            color = Color.White,
            style = TextStyle(shadow = STextShadow)
        )
        Slider(
            value = choice.sizeSp,
            onValueChange = { choice.setSize(it) },
            valueRange = choice.minSizeSp..choice.maxSizeSp,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        if (previewOnDark) {
            Text(
                text = "Aperçu :",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = previewText,
                fontFamily = previewFamily,
                fontSize = choice.sizeSp.sp,
                color = choice.color ?: Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = "Aperçu — sur fond clair, comme dans les bulles de conversation :",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    // Couleur par défaut des bulles de l'IA (surfaceVariant M3, thème clair) :
                    // sert uniquement à prévisualiser la lisibilité de la couleur choisie.
                    .background(Color(0xFFE7E0EC))
                    .padding(10.dp)
            ) {
                Text(
                    text = previewText,
                    fontFamily = previewFamily,
                    fontSize = choice.sizeSp.sp,
                    color = choice.color ?: Color(0xFF1D1B20) // couleur de texte par défaut sur ce fond, si "Auto"
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color?, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(color ?: Color.Transparent)
                .border(
                    width = if (selected) 3.dp else 1.5.dp,
                    color = if (selected) SRed else Color.White.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (color == null) {
                Text(text = "?", color = Color.White, fontSize = 16.sp)
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.85f),
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun IconTile(
    icon: AppIcon,
    selected: Boolean,
    originalPreview: androidx.compose.ui.graphics.ImageBitmap?,
    fonts: AppFonts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    val res = icon.previewRes
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else SCardBorder, shape)
            .clickable(onClick = onClick)
    ) {
        if (res != null) {
            Image(
                painter = painterResource(id = res),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (originalPreview != null) {
            Image(
                bitmap = originalPreview,
                contentDescription = "Icône d'origine",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (icon == AppIcon.ORIGINAL) {
            Text(
                text = "Origine",
                fontFamily = fonts.body,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SRed)
            ) {
                Text(text = "\u2713", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** Texte tourné de `degrees` (90 = lit de haut en bas, 270 = de bas en haut), avec la mise en page adaptée. */
private fun Modifier.verticalText(degrees: Float): Modifier =
    this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.height, placeable.width) {
                placeable.place(
                    x = -(placeable.width / 2 - placeable.height / 2),
                    y = -(placeable.height / 2 - placeable.width / 2)
                )
            }
        }
        .rotate(degrees)

/** Petit onglet collé au bord de l'écran : flèche + nom de l'autre page écrit à la verticale. */
@Composable
private fun SideTab(
    text: String,
    arrow: String,
    rotation: Float,
    fonts: AppFonts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Text(
            text = arrow,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 22.sp,
            style = TextStyle(shadow = STextShadow)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            fontFamily = fonts.display,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 15.sp,
            letterSpacing = 1.sp,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.verticalText(rotation)
        )
    }
}

private class DropdownEntry(val value: String, val label: String, val family: FontFamily)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    entries: List<DropdownEntry>,
    selectedLabel: String,
    selectedFamily: FontFamily,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(2.dp, SInk, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Text(
                text = selectedLabel,
                fontFamily = selectedFamily,
                color = SInk,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(text = "\u25BE", fontSize = 18.sp, color = SInk)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = entry.label,
                            fontFamily = entry.family,
                            color = SInk,
                            fontSize = 16.sp
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(entry.value)
                    }
                )
            }
        }
    }
}

/** Même rendu que ComicButton (ConfigureKeyScreen) : fond plat, bordure épaisse, ombre décalée façon BD. */
@Composable
private fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    val bg = if (secondary) Color.White else SRed
    val textColor = if (secondary) SInk else Color.White

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 3.dp, y = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SInk)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(3.dp, SInk, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = fonts.display,
                color = textColor,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
