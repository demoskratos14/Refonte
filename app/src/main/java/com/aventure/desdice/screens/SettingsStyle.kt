package com.aventure.desdice.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow

// Palette identique aux autres ecrans, partagee par tous les fichiers des reglages.
// Noms prefixes "S" pour ne pas entrer en conflit avec les vals privees de
// ConfigureKeyScreen / StorySelectorScreen (meme package).

internal val SInk = Color(0xFF14161A)
internal val SRed = Color(0xFFE0263C)
internal val SPageBg = Color(0xFF1B140C)
internal val SCardBg = Color(0xBF14100C)          // carte sombre translucide (le fond est chargé)
internal val SCardBorder = Color(0x4DFFFFFF)
internal val SLineColor = Color(0x4DFFFFFF)
internal val STextShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1.5f, 2f), 6f)
