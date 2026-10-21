package com.aventure.desdice.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppTextStyles
import com.aventure.desdice.ui.titleSp

// Affichage des des a plat (face tiree) : cadre BD, points, nombre, face du destin.
// Le de qui roule est dans DiceSpin3D.kt.

@Composable
internal fun DieWithCaption(caption: String, fonts: AppFonts, textStyles: AppTextStyles, die: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        die()
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = textStyles.titleSp(13.6f),
                color = textStyles.titleColor ?: Color.White,
                shadow = ClassicTextShadow
            )
        )
    }
}

@Composable
internal fun DieBox(
    rotation: Float,
    background: Color,
    size: Dp = 150.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    // Lu par TalkBack à la place du dessin du dé (points, symboles) : ex. « Dé de réussite : 4 ».
    description: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .hardShadow(5.dp, 14.dp, ClassicInk)
            .background(background, shape)
            .border(4.dp, ClassicInk, shape)
            .let {
                if (onClick != null) {
                    it.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClickLabel = "Lancer ce dé",
                        role = Role.Button,
                        onClick = onClick
                    )
                } else it
            }
            .let { if (description != null) it.semantics { contentDescription = description } else it }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
internal fun SuccessDieFace(value: Int?) {
    val pips = value?.let { PIP_POSITIONS[it] }
    if (pips == null) {
        Text(
            text = "⚡",
            modifier = Modifier.alpha(0.55f),
            style = TextStyle(fontSize = 54.sp)
        )
    } else {
        Canvas(Modifier.fillMaxSize()) {
            val cellW = size.width / 3f
            val cellH = size.height / 3f
            val radius = 11.dp.toPx()
            pips.forEach { (row, col) ->
                drawCircle(
                    color = ClassicInk,
                    radius = radius,
                    center = Offset((col - 0.5f) * cellW, (row - 0.5f) * cellH)
                )
            }
        }
    }
}

/** Face a plat d'un de de reussite autre qu'un d6 : le nombre tire, en grand (ou "⚡" avant tout lancer). */
@Composable
internal fun NumberDieFace(value: Int?, fonts: AppFonts) {
    if (value == null) {
        Text(
            text = "⚡",
            modifier = Modifier.alpha(0.55f),
            style = TextStyle(fontSize = 54.sp)
        )
    } else {
        val text = value.toString()
        val size = when {
            text.length >= 3 -> 46.sp
            text.length == 2 -> 64.sp
            else -> 80.sp
        }
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = fonts.display, fontSize = size, color = ClassicInk)
        )
    }
}

@Composable
internal fun FateDieFace(fate: FateFace?, fonts: AppFonts, textStyles: AppTextStyles) {
    if (fate == null) {
        Text(text = "🔮", style = TextStyle(fontSize = 54.sp))
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = fate.emoji, style = TextStyle(fontSize = 58.sp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = fate.label,
                textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = fonts.display, fontSize = textStyles.titleSp(16f), color = ClassicPurple)
            )
        }
    }
}
