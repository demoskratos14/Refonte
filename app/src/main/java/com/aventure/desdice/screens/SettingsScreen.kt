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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.BackgroundStore
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.FontOption
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.listAssetFonts
import com.aventure.desdice.ui.rememberAppFonts
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
 * Écran Réglages en 2 pages qu'on fait glisser (ou qu'on change en touchant
 * l'onglet latéral) :
 *   - Polices : police du texte de l'appli et police des titres, parmi les
 *     fichiers de app/src/main/assets/fonts ;
 *   - Photos : images de fond des pages de l'appli.
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

    val pagerState = rememberPagerState(pageCount = { 2 })
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

            // --- 2 pages, à faire glisser : 0 = polices, 1 = images de fond ---
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
                                start = if (page == 1) 38.dp else 16.dp,
                                end = if (page == 0) 38.dp else 16.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (page == 0) {
                        // --- Polices ---
                        SettingsCard(title = "\uD83D\uDD24 Polices", fonts = fonts) {
                            if (fontOptions.isEmpty()) {
                                Text(
                                    text = "Aucune police (.ttf / .otf) trouvée dans app/src/main/assets/fonts.",
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontFamily = fonts.body,
                                    style = TextStyle(shadow = STextShadow)
                                )
                            } else {
                                FontPicker(
                                    label = "Police du texte de l'application",
                                    options = fontOptions,
                                    fontPrefs = fontPrefs,
                                    selectedFile = fontPrefs.bodyFontFile,
                                    defaultFile = FontPrefs.DEFAULT_BODY_FILE,
                                    previewText = "Le dé roule sur la table… Tu avances dans la forêt sombre, " +
                                        "et quelque chose bouge entre les arbres.",
                                    previewSize = 15.sp,
                                    fonts = fonts,
                                    onSelect = { fontPrefs.setBodyFont(it) }
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp)
                                        .height(2.dp)
                                        .background(SLineColor)
                                )
                                FontPicker(
                                    label = "Police des titres (histoires, boutons)",
                                    options = fontOptions,
                                    fontPrefs = fontPrefs,
                                    selectedFile = fontPrefs.titleFontFile,
                                    defaultFile = FontPrefs.DEFAULT_TITLE_FILE,
                                    previewText = "Le Livre des Mille Histoires",
                                    previewSize = 26.sp,
                                    fonts = fonts,
                                    onSelect = { fontPrefs.setTitleFont(it) }
                                )
                            }
                        }
                        } else {
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
                        }

                        SettingsButton(text = "Retour", onClick = onBack, fonts = fonts, secondary = true)
                        Spacer(Modifier.height(8.dp))
                    }

                    // Onglet sur le côté : indique l'autre page (toucher = y aller).
                    if (page == 0) {
                        SideTab(
                            text = "Photos",
                            arrow = "\u203A",
                            rotation = 90f,
                            fonts = fonts,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp)
                        )
                    } else {
                        SideTab(
                            text = "Polices",
                            arrow = "\u2039",
                            rotation = 270f,
                            fonts = fonts,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 4.dp)
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

/** Sélecteur de police : liste déroulante (chaque police s'affiche dans son propre style) + aperçu. */
@Composable
private fun FontPicker(
    label: String,
    options: List<FontOption>,
    fontPrefs: FontPrefs,
    selectedFile: String?,
    defaultFile: String,
    previewText: String,
    previewSize: TextUnit,
    fonts: AppFonts,
    onSelect: (String?) -> Unit
) {
    val entries = remember(options) {
        options.map { DropdownEntry(it.file, it.label, fontPrefs.familyFor(it.file) ?: FontFamily.Default) }
    }
    // Sans choix enregistré, on affiche la police par défaut si elle est présente dans le dossier.
    val effectiveFile = selectedFile ?: defaultFile
    val current = entries.firstOrNull { it.value == effectiveFile }

    Text(
        text = label,
        fontFamily = fonts.bodyBold,
        color = Color.White,
        style = TextStyle(shadow = STextShadow),
        modifier = Modifier.padding(bottom = 8.dp)
    )
    SettingsDropdown(
        entries = entries,
        selectedLabel = current?.label ?: "Police du système",
        selectedFamily = current?.family ?: FontFamily.Default,
        onSelect = { file -> onSelect(file) }
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = previewText,
        fontFamily = current?.family ?: FontFamily.Default,
        fontSize = previewSize,
        color = Color.White,
        style = TextStyle(shadow = STextShadow),
        modifier = Modifier.fillMaxWidth()
    )
    if (selectedFile != null) {
        Spacer(Modifier.height(10.dp))
        SettingsButton(
            text = "Rétablir la police par défaut",
            onClick = { onSelect(null) },
            fonts = fonts,
            secondary = true
        )
    }
}

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
