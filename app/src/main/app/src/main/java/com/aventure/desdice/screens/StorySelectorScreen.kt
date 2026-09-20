package com.aventure.desdice.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.R
import com.aventure.desdice.model.Story
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ecran de choix d'histoire, reproduit d'apres render_story_selector_page
 * (dice_web.py) : carrousel plein ecran (une page par histoire + une
 * derniere page "Nouvelle histoire"), image de fond en "cover", degrade
 * sombre en bas, titre / sous-titre / "Toucher pour commencer", pastilles
 * de pagination, et en haut : de classique (a gauche), titre de la page
 * (au centre) et cle API (a droite).
 */
@Composable
fun StorySelectorScreen(
    viewModel: GameViewModel,
    onStorySelected: (String) -> Unit,
    onNewStoryClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClassicDiceClick: () -> Unit = {},
    onConfigureKeyClick: () -> Unit = {}
) {
    val stories by viewModel.stories.collectAsState()
    val fonts = rememberAppFonts()
    var storyToDelete by remember { mutableStateOf<Story?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (stories.isEmpty()) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val pageCount = stories.size + 1
            val pagerState = rememberPagerState(pageCount = { stories.size + 1 })

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val story = stories.getOrNull(page)
                if (story != null) {
                    StorySlide(
                        story = story,
                        fonts = fonts,
                        onClick = {
                            viewModel.selectStory(story.slug)
                            onStorySelected(story.slug)
                        },
                        onDeleteClick = { storyToDelete = story }
                    )
                } else {
                    NewStorySlide(fonts = fonts, onClick = onNewStoryClick)
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(pageCount) { index ->
                    val active = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(if (active) 1.3f else 1f)
                            .clip(CircleShape)
                            .background(
                                if (active) Color.White else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }

        SelectorHeader(
            fonts = fonts,
            onClassicDiceClick = onClassicDiceClick,
            onConfigureKeyClick = onConfigureKeyClick,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    storyToDelete?.let { story ->
        AlertDialog(
            onDismissRequest = { storyToDelete = null },
            title = { Text("Supprimer cette histoire ?") },
            text = {
                Text(
                    "Supprimer définitivement ${story.title} ? La partie en cours, " +
                        "l'image de fond et le totem de départ associés seront effacés. " +
                        "Impossible à annuler."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteStory(story.slug)
                    storyToDelete = null
                }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { storyToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }
}

// Degrade de .slide-overlay : leger en haut, presque transparent vers 40 %,
// tres sombre en bas (pour lire le titre et le sous-titre).
private val OverlayBrush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.25f),
    0.4f to Color.Black.copy(alpha = 0.05f),
    1f to Color.Black.copy(alpha = 0.9f)
)

private fun textShadow(alpha: Float, blur: Float, dy: Float) =
    Shadow(Color.Black.copy(alpha = alpha), Offset(0f, dy), blur)

@Composable
private fun SelectorHeader(
    fonts: AppFonts,
    onClassicDiceClick: () -> Unit,
    onConfigureKeyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 11.dp, end = 11.dp, top = 9.dp)
            .height(44.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .clickable(onClick = onClassicDiceClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.classic_dice_icon),
                contentDescription = "Dés classiques",
                colorFilter = ColorFilter.tint(Color.White),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp)
            )
        }

        Text(
            text = "\uD83C\uDF1F Choisis ton histoire",
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 22.sp,
                color = Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                shadow = textShadow(0.7f, 12f, 4f)
            ),
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(44.dp)
                .clickable(onClick = onConfigureKeyClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uD83D\uDD11",
                style = TextStyle(
                    fontSize = 21.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    shadow = textShadow(0.7f, 12f, 4f)
                )
            )
        }
    }
}

@Composable
private fun StoryBackground(b64: String, contentDescription: String?) {
    // Decodage hors du thread principal : l'image (jusqu'a ~1600 px) est
    // decodee une fois par page affichee, sans bloquer le geste de balayage.
    val bitmap by produceState<ImageBitmap?>(null, b64) {
        value = withContext(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SlideTexts(
    title: String,
    subtitle: String,
    cta: String,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    topContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        topContent()
        Text(
            text = title,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 42.sp,
                color = Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                shadow = textShadow(0.7f, 16f, 6f)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                fontFamily = fonts.body,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                shadow = textShadow(0.6f, 8f, 2f)
            ),
            modifier = Modifier.widthIn(max = 420.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = cta.uppercase(),
            style = TextStyle(
                fontFamily = fonts.body,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun StorySlide(
    story: Story,
    fonts: AppFonts,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            StoryBackground(b64 = story.bgImageB64, contentDescription = story.title)
            Box(modifier = Modifier.fillMaxSize().background(OverlayBrush))
            SlideTexts(
                title = story.title,
                subtitle = story.subtitle,
                cta = "Toucher pour commencer \u2192",
                fonts = fonts,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Corbeille : uniquement pour les histoires personnalisees, placee
        // en dehors de la zone cliquable de la page pour que son clic ne
        // declenche jamais "Toucher pour commencer".
        if (story.isCustom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(20, 22, 26).copy(alpha = 0.65f))
                    .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    .clickable(onClick = onDeleteClick),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "\uD83D\uDDD1\uFE0F", style = TextStyle(fontSize = 20.sp))
            }
        }
    }
}

@Composable
private fun NewStorySlide(
    fonts: AppFonts,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF2A2118), Color(0xFF14161A)))
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().background(OverlayBrush))
        SlideTexts(
            title = "Nouvelle histoire",
            subtitle = "Crée ton propre univers : image de fond, description, premier totem.",
            cta = "Toucher pour créer \u2192",
            fonts = fonts,
            modifier = Modifier.align(Alignment.BottomCenter),
            topContent = {
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFCD3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "\u2795", style = TextStyle(fontSize = 29.sp))
                }
            }
        )
    }
}
