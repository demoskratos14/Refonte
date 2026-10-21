package com.aventure.desdice.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppTextStyles
import com.aventure.desdice.ui.ClassicDicePrefs
import com.aventure.desdice.ui.bodySp
import com.aventure.desdice.ui.titleSp

// ----------------------------------------------------------------------
// Briques visuelles "BD" de la page Des classiques : ombre decalee, sections, boutons, plateau
// ----------------------------------------------------------------------

/** Ombre pleine decalee vers le bas/droite, comme `box-shadow: Xpx Xpx 0 couleur` en CSS. */
internal fun Modifier.hardShadow(offset: Dp, radius: Dp, color: Color): Modifier =
    this.drawBehind {
        val o = offset.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(o, o),
            size = size,
            cornerRadius = CornerRadius(radius.toPx())
        )
    }

/** Bloc sans fond ni bordure (la photo reste visible), largeur limitee. */
@Composable
internal fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
internal fun ClassicComicButton(
    text: String,
    onClick: () -> Unit,
    background: Color,
    contentColor: Color,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.6f)
            .offset { IntOffset(if (pressed) 2.dp.roundToPx() else 0, if (pressed) 2.dp.roundToPx() else 0) }
            .hardShadow(if (pressed) 1.dp else 3.dp, 10.dp, ClassicInk)
            .background(background, shape)
            .border(3.dp, ClassicInk, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = textStyles.titleSp(17.6f),
                letterSpacing = 1.sp,
                color = contentColor
            )
        )
    }
}

/**
 * Le plateau : tous les des choisis, a plat (derniere valeur tiree) ou en train de rouler en 3D
 * (TumblingDie). Chaque de se lance seul d'un tap, et se retire avec la petite croix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiceTray(
    dice: List<Int>,
    lastResults: List<DieResult?>,
    fateFaces: List<FateFace>,
    spin: ClassicSpin?,
    progress: Animatable<Float, AnimationVector1D>,
    rolling: Boolean,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    onRollOne: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    if (dice.isEmpty()) {
        Text(
            text = "Aucun dé pour l'instant : ajoute-en un ci-dessous.",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                fontFamily = fonts.body,
                fontSize = textStyles.bodySp(15f),
                color = textStyles.bodyColor ?: Color.White,
                shadow = ClassicTextShadow
            )
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        dice.forEachIndexed { index, d ->
            val isFate = d == ClassicDicePrefs.FATE_DIE
            val rotation = if (index % 2 == 0) -1f else 1f
            val background = if (isFate) ClassicFateDieBackground else Color.White
            val last = lastResults.getOrNull(index)
            val spec = spin?.specs?.get(index)
            val caption = when {
                isFate -> "Dé du destin"
                d == 6 -> "Dé classique"
                else -> "Dé à $d faces"
            }
            Box {
                DieWithCaption(caption, fonts, textStyles) {
                    if (spec != null) {
                        Box(Modifier.size(150.dp).graphicsLayer { rotationZ = rotation }) {
                            TumblingDie(
                                spec, progress,
                                if (isFate) fateFaces else null,
                                fonts, textStyles, background,
                                faceNumbers = spin.numbers[index]
                            )
                        }
                    } else {
                        DieBox(
                            rotation = rotation,
                            background = background,
                            size = 150.dp,
                            onClick = { onRollOne(index) },
                            enabled = !rolling,
                            description = caption + " : " + when {
                                isFate -> last?.fate?.label ?: "pas encore lancé"
                                else -> last?.value?.toString() ?: "pas encore lancé"
                            }
                        ) {
                            when {
                                isFate -> FateDieFace(last?.fate, fonts, textStyles)
                                d == 6 -> SuccessDieFace(last?.value)
                                else -> NumberDieFace(last?.value, fonts)
                            }
                        }
                    }
                }
                if (!rolling) {
                    RemoveBadge(
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp),
                        onClick = { onRemove(index) }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Touche un dé pour le lancer seul, ✕ pour le retirer.",
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = fonts.body,
            fontSize = textStyles.bodySp(13f),
            color = textStyles.bodyColor ?: Color.White.copy(alpha = 0.9f),
            shadow = ClassicTextShadow
        )
    )
}

/** Petite pastille ronde "✕" posee sur le coin d'un de pour le retirer du plateau. */
@Composable
private fun RemoveBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color.White, CircleShape)
            .border(2.dp, ClassicInk, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Retirer ce dé" },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "✕", style = TextStyle(fontSize = 14.sp, color = ClassicDangerText))
    }
}

/**
 * Ajout d'un de au plateau : des courants en un tap (d4, classique a 6 faces, d8, d10, d12, d20),
 * le de du destin, ou "Autre" pour un nombre de faces libre (2 a 999). Chaque tap ajoute un de
 * (on peut donc en mettre plusieurs du meme type), jusqu'a ClassicDicePrefs.MAX_DICE.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddDieSection(
    count: Int,
    enabled: Boolean,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    onAdd: (Int) -> Unit
) {
    val canAdd = enabled && count < ClassicDicePrefs.MAX_DICE
    var customMode by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    Text(
        text = "Ajouter un dé ($count/${ClassicDicePrefs.MAX_DICE})",
        style = TextStyle(
            fontFamily = fonts.bodyBold,
            fontSize = textStyles.bodySp(16f),
            color = textStyles.bodyColor ?: Color.White,
            shadow = ClassicTextShadow
        )
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ClassicDicePrefs.COMMON_FACES.forEach { n ->
            FaceChip(
                label = if (n == 6) "Classique" else "d$n",
                selected = false,
                enabled = canAdd,
                fonts = fonts,
                textStyles = textStyles
            ) { onAdd(n) }
        }
        FaceChip(
            label = "🔮 Destin",
            selected = false,
            enabled = canAdd,
            fonts = fonts,
            textStyles = textStyles
        ) { onAdd(ClassicDicePrefs.FATE_DIE) }
        FaceChip(
            label = "Autre",
            selected = customMode,
            enabled = enabled,
            fonts = fonts,
            textStyles = textStyles
        ) { customMode = !customMode }
    }
    if (customMode) {
        Spacer(Modifier.height(10.dp))
        val customValue = customText.toIntOrNull()
        val customValid = customValue != null &&
            customValue in ClassicDicePrefs.MIN_FACES..ClassicDicePrefs.MAX_FACES
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CustomFacesField(
                text = customText,
                enabled = enabled,
                fonts = fonts,
                textStyles = textStyles
            ) { typed -> customText = typed.filter { it.isDigit() }.take(3) }
            Text(
                text = "faces",
                style = TextStyle(
                    fontFamily = fonts.body,
                    fontSize = textStyles.bodySp(14f),
                    color = textStyles.bodyColor ?: Color.White,
                    shadow = ClassicTextShadow
                )
            )
            ClassicComicButton(
                text = "Ajouter",
                onClick = {
                    if (customValid) {
                        onAdd(customValue)
                        customText = ""
                    }
                },
                background = ClassicRed,
                contentColor = Color.White,
                fonts = fonts,
                textStyles = textStyles,
                enabled = canAdd && customValid
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "De ${ClassicDicePrefs.MIN_FACES} à ${ClassicDicePrefs.MAX_FACES} faces.",
            style = TextStyle(
                fontFamily = fonts.body,
                fontSize = textStyles.bodySp(13f),
                color = textStyles.bodyColor ?: Color.White,
                shadow = ClassicTextShadow
            )
        )
    }
}

/** Petit bouton "BD" (même habillage ombre/bordure que ClassicComicButton) pour un choix de dé. */
@Composable
private fun FaceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.6f)
            .hardShadow(if (selected) 1.dp else 3.dp, 10.dp, ClassicInk)
            .background(if (selected) ClassicRed else Color.White, shape)
            .border(3.dp, ClassicInk, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = textStyles.titleSp(17f),
                color = if (selected) Color.White else ClassicInk
            )
        )
    }
}

/** Champ numérique pour saisir un nombre de faces libre (chiffres seulement, 3 au plus). */
@Composable
private fun CustomFacesField(
    text: String,
    enabled: Boolean,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    onTextChange: (String) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(
            fontFamily = fonts.display,
            fontSize = textStyles.titleSp(18f),
            color = ClassicInk,
            textAlign = TextAlign.Center
        ),
        modifier = Modifier
            .width(96.dp)
            .background(Color.White, shape)
            .border(3.dp, ClassicInk, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}
