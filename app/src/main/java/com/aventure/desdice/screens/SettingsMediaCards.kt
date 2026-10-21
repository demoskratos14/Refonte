package com.aventure.desdice.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppIcon
import com.aventure.desdice.ui.AppIconStore
import com.aventure.desdice.ui.AudiencePrefs
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.BackgroundStore
import com.aventure.desdice.ui.iconsFor
import com.aventure.desdice.ui.rememberBackgroundPainter
import kotlinx.coroutines.launch

// Page « Photos » et page « Icône » des réglages : une ligne par image de fond,
// la grille des icônes de l'application et sa tuile. Voir SettingsScreen.kt.

/** Une ligne « page » : miniature du fond actuel + choisir une image / revenir à l'image par défaut. */
@Composable
internal fun BackgroundRow(slot: BackgroundSlot, store: BackgroundStore, fonts: AppFonts) {
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

/**
 * Page « Icône » : grille 3 x 3 des icônes proposées, un clic applique l'icône.
 * Les icônes proposées dépendent du public visé (page « Profil », voir
 * AudiencePrefs.kt et AppIconStore.iconsFor) : toujours 9 au total.
 */
@Composable
internal fun AppIconCard(fonts: AppFonts) {
    val context = LocalContext.current
    val audiencePrefs = remember(context) { AudiencePrefs.get(context) }
    val icons = remember(audiencePrefs.audience) { iconsFor(audiencePrefs.audience) }
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
                "peut mettre quelques secondes à apparaître sur l'écran d'accueil. Les icônes " +
                "proposées dépendent du public visé, réglable sur la page « Profil ».",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        icons.chunked(3).forEachIndexed { rowIndex, row ->
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
    // Nom lu par TalkBack : « Icône d'origine », ou « Icône 3, filles » d'après le nom de l'entrée.
    val label = if (icon == AppIcon.ORIGINAL) {
        "Icône d'origine"
    } else {
        val parts = icon.name.split("_")
        val number = parts.getOrNull(1).orEmpty()
        val audience = when (parts.getOrNull(2)) {
            "FILLES" -> ", filles"
            "GARCONS" -> ", garçons"
            "DUO" -> ", duo"
            else -> ""
        }
        "Icône $number$audience"
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else SCardBorder, shape)
            .clickable(role = Role.RadioButton, onClickLabel = "Choisir cette icône", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (selected) "sélectionnée" else "non sélectionnée"
            }
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
                contentDescription = null,
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
                    .clearAndSetSemantics { }
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
                Text(text = "\u2713", color = Color.White, fontSize = 14.sp, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
