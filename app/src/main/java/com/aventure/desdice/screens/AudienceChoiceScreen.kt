package com.aventure.desdice.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.Audience
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts

// Palette identique aux autres écrans (voir SettingsScreen.kt) -- noms préfixés
// pour ne pas entrer en conflit avec les vals privées des autres fichiers.
private val QInk = Color(0xFF14161A)
private val QRed = Color(0xFFE0263C)
private val QPageBg = Color(0xFF1B140C)
private val QTextShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1.5f, 2f), 6f)

/**
 * Premier écran de l'appli, avant même ConfigureKeyScreen (voir AppNavigation dans
 * MainActivity.kt) : choix du public visé (filles / garçons / les 2). Ce choix
 * détermine les icônes proposées dans Réglages et le fond par défaut de la page
 * « Nouvelle histoire » du carrousel (voir AudiencePrefs.kt).
 *
 * Pas de bouton retour : un choix est obligatoire pour continuer, mais reste
 * modifiable ensuite depuis Réglages > Profil (ProfileCard dans SettingsScreen.kt).
 */
@Composable
fun AudienceChoiceScreen(onChosen: (Audience) -> Unit, modifier: Modifier = Modifier) {
    val fonts = rememberAppFonts()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2B1D10), QPageBg))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
        ) {
            Text(
                text = "\uD83D\uDCD6 Cette histoire est faite pour…",
                fontFamily = fonts.display,
                color = Color.White,
                fontSize = 26.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = QTextShadow)
            )
            Text(
                text = "Ce choix détermine les icônes proposées et l'ambiance de l'appli. " +
                    "Tu pourras le changer plus tard depuis Réglages.",
                fontFamily = fonts.body,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = QTextShadow),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Audience.values().forEach { audience ->
                AudienceButton(
                    text = audience.label,
                    onClick = { onChosen(audience) },
                    fonts = fonts
                )
            }
        }
    }
}

/** Même rendu « BD » que les boutons des autres écrans (ComicButton / SettingsButton). */
@Composable
private fun AudienceButton(
    text: String,
    onClick: () -> Unit,
    fonts: AppFonts
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = 3.dp, y = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(QInk)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(QRed)
                .border(3.dp, QInk, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = fonts.display,
                color = Color.White,
                fontSize = 20.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
