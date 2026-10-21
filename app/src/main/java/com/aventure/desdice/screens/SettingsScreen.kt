package com.aventure.desdice.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.R
import com.aventure.desdice.ThreatExplosionOverlay
import com.aventure.desdice.TotemUseOverlay
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppIconStore
import com.aventure.desdice.ui.Audience
import com.aventure.desdice.ui.AudiencePrefs
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.BackgroundStore
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.iconsFor
import com.aventure.desdice.ui.listAssetFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.ui.rememberBackgroundPainter
import kotlinx.coroutines.launch


/** Titres des pages, dans l'ordre. */
private val PAGE_TITLES = listOf("Polices", "Photos", "Icône", "Sons", "Profil")
private const val PAGE_COUNT = 5

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
 * Écran Réglages en 5 pages qu'on fait glisser (ou qu'on change en touchant
 * l'onglet latéral) :
 *   - Polices : police du texte de l'appli et police des titres, parmi les
 *     fichiers de app/src/main/assets/fonts ;
 *   - Photos : images de fond des pages de l'appli ;
 *   - Icône : icône de l'appli parmi 9 proposées, un clic suffit -- lesquelles
 *     sont proposées dépend du public visé, réglé sur la page « Profil » ;
 *   - Sons : couper ou baisser le bruit des dés (res/raw/dice_roll.mp3) et la
 *     musique de fond (fichiers res/raw/music_*) ;
 *   - Profil : public visé (filles / garçons / les 2), qui détermine les icônes
 *     ci-dessus et le fond par défaut de la page « Nouvelle histoire » du
 *     carrousel (voir AudiencePrefs.kt) -- choisi une première fois dans
 *     AudienceChoiceScreen, avant même cet écran.
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
                        } else if (page == 3) {
                            SoundCard(fonts = fonts)
                            VibrationCard(fonts = fonts)
                            MusicCard(fonts = fonts)
                        } else {
                            ProfileCard(fonts = fonts)
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
    
        // Pour le bouton « Essayer » de la carte des effets de menace.
        ThreatExplosionOverlay()
        TotemUseOverlay()
    }
}

// ------------------------------------------------------------------
// Composants
// ------------------------------------------------------------------

@Composable
internal fun SettingsCard(
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


/**
 * Page « Profil » : public visé (filles / garçons / les 2), choisi une première
 * fois dans AudienceChoiceScreen et modifiable ici. Détermine les icônes
 * proposées (page « Icône », voir AppIconStore.iconsFor) et le fond par défaut
 * de la page « Nouvelle histoire » du carrousel (voir AudiencePrefs.kt).
 */
@Composable
private fun ProfileCard(fonts: AppFonts) {
    val context = LocalContext.current
    val audiencePrefs = remember(context) { AudiencePrefs.get(context) }
    val current = audiencePrefs.audience

    SettingsCard(title = "\uD83D\uDC65 Public visé", fonts = fonts) {
        Text(
            text = "Cette histoire est faite pour… Ce choix change les icônes proposées " +
                "dans la page « Icône » et le fond de la page « Nouvelle histoire » du " +
                "carrousel.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        Audience.values().forEachIndexed { index, audience ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            SettingsButton(
                text = if (audience == current) "\u2713 ${audience.label}" else audience.label,
                onClick = { audiencePrefs.updateAudience(audience) },
                fonts = fonts,
                secondary = audience != current
            )
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

/** Même rendu que ComicButton (ConfigureKeyScreen) : fond plat, bordure épaisse, ombre décalée façon BD. */
@Composable
internal fun SettingsButton(
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
