package com.aventure.desdice.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.FontOption
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.TextStyleChoice
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Page "Polices" des reglages : styles de texte, roue chromatique, menu deroulant.

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
internal fun TextStyleSection(
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
        ColorWheelPicker(
            color = choice.color,
            onColorSelected = { choice.setColor(it) },
            onReset = { choice.setColor(null) },
            fonts = fonts
        )

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

/**
 * Roue chromatique (teinte + saturation) avec un curseur de luminosité en dessous, pour
 * choisir une couleur de texte librement plutôt que parmi des pastilles prédéfinies.
 * Taper ou faire glisser le doigt sur la roue choisit la teinte (angle) et la saturation
 * (distance au centre) ; le rond "Auto" à côté revient à la couleur automatique de l'écran.
 */
@Composable
private fun ColorWheelPicker(
    color: Color?,
    onColorSelected: (Color) -> Unit,
    onReset: () -> Unit,
    fonts: AppFonts
) {
    // Teinte/saturation/luminosité de départ : dérivées de la couleur déjà choisie, ou d'un
    // point neutre par défaut si "Auto" (le curseur n'a pas encore de position propre).
    val startHsv = remember(color) {
        val c = color ?: Color(0xFFFFC94D)
        val out = FloatArray(3)
        AndroidColor.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), out)
        out
    }
    var hue by remember(color) { mutableFloatStateOf(startHsv[0]) }
    var sat by remember(color) { mutableFloatStateOf(startHsv[1]) }
    var value by remember(color) { mutableFloatStateOf(startHsv[2].coerceAtLeast(0.3f)) }

    fun currentColor() = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)))

    fun pickAt(x: Float, y: Float, sizePx: Float) {
        val radius = sizePx / 2f
        val dx = x - radius
        val dy = y - radius
        val dist = hypot(dx, dy).coerceAtMost(radius)
        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0) angle += 360f
        hue = angle
        sat = if (radius > 0f) (dist / radius).coerceIn(0f, 1f) else 0f
        onColorSelected(currentColor())
    }

    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val wheelSize = 190.dp
        Box(
            modifier = Modifier
                .size(wheelSize)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> pickAt(offset.x, offset.y, size.width.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        pickAt(change.position.x, change.position.y, size.width.toFloat())
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                // Teinte en anneau (sweep), puis blanc->transparent du centre vers le bord pour
                // la saturation : au centre, toutes les teintes se rejoignent en blanc, comme
                // sur un cercle chromatique classique.
                drawCircle(
                    brush = Brush.sweepGradient(
                        (0..360 step 30).map { deg ->
                            Color(AndroidColor.HSVToColor(floatArrayOf(deg.toFloat(), 1f, 1f)))
                        }
                    )
                )
                drawCircle(brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = radius))
                if (value < 1f) {
                    // Assombrit l'ensemble pour représenter la luminosité choisie.
                    drawCircle(color = Color.Black.copy(alpha = 1f - value))
                }
                if (color != null) {
                    val angleRad = Math.toRadians(hue.toDouble())
                    val r = sat * radius
                    val point = Offset(
                        x = center.x + (r * cos(angleRad)).toFloat(),
                        y = center.y + (r * sin(angleRad)).toFloat()
                    )
                    drawCircle(color = Color.White, radius = 8.dp.toPx(), center = point, style = Stroke(width = 3.dp.toPx()))
                    drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = 8.dp.toPx(), center = point, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorSwatch(
                color = null,
                label = "Auto",
                selected = color == null,
                onClick = onReset
            )
            ColorSwatch(
                color = Color.Black,
                label = "Noir",
                selected = color == Color.Black,
                onClick = { onColorSelected(Color.Black) }
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        text = "Luminosité",
        fontFamily = fonts.bodyBold,
        color = Color.White,
        style = TextStyle(shadow = STextShadow)
    )
    Slider(
        value = value,
        onValueChange = { value = it; onColorSelected(currentColor()) },
        valueRange = 0.15f..1f,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = SRed,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
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
