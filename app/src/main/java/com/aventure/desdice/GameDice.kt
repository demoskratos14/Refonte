package com.aventure.desdice

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aventure.desdice.screens.PipGlyph
import com.aventure.desdice.screens.pipGlyphSizeDp
import com.aventure.desdice.ui.rememberAppFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Meme table que PIP_POSITIONS (dice_web.py) : (ligne, colonne) dans une grille 3x3.
private val GAME_PIP_POSITIONS: Map<Int, List<Pair<Int, Int>>> = mapOf(
    1 to listOf(2 to 2),
    2 to listOf(1 to 1, 3 to 3),
    3 to listOf(1 to 1, 2 to 2, 3 to 3),
    4 to listOf(1 to 1, 1 to 3, 3 to 1, 3 to 3),
    5 to listOf(1 to 1, 1 to 3, 2 to 2, 3 to 1, 3 to 3),
    6 to listOf(1 to 1, 1 to 3, 2 to 1, 2 to 3, 3 to 1, 3 to 3)
)

/**
 * Face du de de reussite dans l'ecran de jeu : comme sur la page "Des classiques",
 * mais chaque pip est le SYMBOLE (image ou emoji) du totem tire (pip_choice), comme
 * dans l'ancienne page (render_success_die). Avant le premier lancer : le symbole
 * actif en grand, sinon un eclair.
 */
@Composable
internal fun SymbolSuccessDieFace(
    value: Int?,
    pipKeys: List<String>,
    state: JSONObject?,
    placeholderKey: String
) {
    if (value == null || value !in 1..6) {
        val symbol = findSymbol(state, placeholderKey)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.6f),
            contentAlignment = Alignment.Center
        ) {
            if (symbol != null) {
                SymbolIcon(symbol, 84.dp, 64.sp)
            } else {
                Text(text = "⚡", style = TextStyle(fontSize = 54.sp))
            }
        }
        return
    }

    if (value == 1) {
        // Un seul symbole : on lui laisse toute la case.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SymbolIcon(
                findSymbol(state, pipKeys.firstOrNull().orEmpty()),
                96.dp, 76.sp, fallback = "⚫"
            )
        }
        return
    }

    val positions = GAME_PIP_POSITIONS.getValue(value)
    val pipSize = when (value) {
        2 -> 38
        3 -> 32
        4 -> 29
        5 -> 26
        else -> 22
    }
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 1..3) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 1..3) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val index = positions.indexOf(row to col)
                        if (index >= 0) {
                            val key = pipKeys.getOrNull(index) ?: pipKeys.firstOrNull().orEmpty()
                            SymbolIcon(
                                findSymbol(state, key),
                                pipSize.dp, (pipSize * 0.8f).sp, fallback = "⚫"
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rangee de badges sous le titre (render_totem_row_html) : un badge par totem
 * (totems de l'histoire + totems ajoutes), cliquable pour voir sa fiche.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TotemBadgesRow(state: JSONObject?, onTotemClick: (String) -> Unit) {
    val info = state?.optJSONObject("totem_info") ?: return
    // Les allies fixes (Araignee, Bouclier...) ont une fiche mais pas de badge sous le titre.
    val keys = info.keys().asSequence()
        .filter { info.optJSONObject(it)?.optBoolean("badge", true) != false }
        .toList()
    if (keys.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        keys.forEach { key ->
            val icon = info.optJSONObject(key)?.str("icon").orEmpty()
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    .clickable { onTotemClick(key) },
                contentAlignment = Alignment.Center
            ) {
                SymbolIcon(findSymbol(state, key), 32.dp, 24.sp, fallback = icon)
            }
        }
    }
}

/** Fiche d'un totem : icone, nom, pouvoirs et capacite speciale (totem-modal de l'ancienne page). */
@Composable
internal fun TotemInfoDialog(totemKey: String, state: JSONObject?, onDismiss: () -> Unit) {
    val fonts = rememberAppFonts()
    val info = state?.optJSONObject("totem_info")?.optJSONObject(totemKey) ?: return
    val special = info.str("special")
    val powers = buildList<String> {
        info.optJSONArray("powers")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.optString(i))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Paper)
                .border(3.dp, Ink, shape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    SymbolIcon(findSymbol(state, totemKey), 64.dp, 48.sp, fallback = info.str("icon"))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = info.str("label"),
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = fonts.display, fontSize = 28.sp, letterSpacing = 1.sp, color = Ink)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (powers.isNotEmpty()) {
                        Text(
                            text = "Pouvoirs",
                            style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink)
                        )
                        powers.forEach { power ->
                            Text(
                                text = "• $power",
                                style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (special.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Capacité spéciale",
                            style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink)
                        )
                        Text(
                            text = special,
                            style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (powers.isEmpty() && special.isEmpty()) {
                        Text(
                            text = "Aucun pouvoir renseigné pour ce totem.",
                            style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                ComicButton(
                    text = "Fermer",
                    onClick = onDismiss,
                    fonts = fonts,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Symboles a dessiner sur les 6 faces du de de reussite pendant qu'il roule (a la place
 * des points noirs). La face qui va rester visible porte les VRAIS symboles du tirage
 * (pip_choice) : la transition avec le de a plat est donc invisible. Les autres faces
 * sont des leurres du meme style que le mode actif (unique : le symbole actif ;
 * aleatoire : un symbole tire au sort par face ; mixte : un par point).
 */
@OptIn(ExperimentalTextApi::class)
internal suspend fun buildSuccessDieGlyphs(
    context: Context,
    state: JSONObject?,
    textMeasurer: TextMeasurer,
    finalValue: Int,
    finalKeys: List<String>
): List<List<PipGlyph>> {
    val mode = state?.optString("pip_mode").orEmpty().ifEmpty { "single" }
    val active = state?.optString("pip_symbol").orEmpty()
    val enabled: List<String> = state?.optJSONArray("enabled_symbols")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        ?: emptyList()
    val decoyPool = if (mode == "single" || enabled.isEmpty()) listOf(active) else enabled

    val faceKeys: List<List<String>> = (1..6).map { count ->
        if (count == finalValue && finalKeys.isNotEmpty()) {
            List(count) { finalKeys.getOrNull(it) ?: finalKeys.first() }
        } else when (mode) {
            "single" -> List(count) { active }
            "random" -> {
                val key = decoyPool.random()
                List(count) { key }
            }
            else -> List(count) { decoyPool.random() }
        }
    }

    // Images (fichiers de totem_images/) decodees hors du thread principal.
    val bitmaps = withContext(Dispatchers.IO) {
        faceKeys.flatten().toSet().associateWith { key ->
            findSymbol(state, key)?.str("image")
                ?.takeIf { it.isNotEmpty() }
                ?.let { loadTotemBitmap(context, it) }
        }
    }

    return faceKeys.mapIndexed { index, keys ->
        val style = TextStyle(fontSize = (pipGlyphSizeDp(index + 1) * 0.8f).sp)
        keys.map { key ->
            val bitmap = bitmaps[key]
            if (bitmap != null) {
                PipGlyph(bitmap, null)
            } else {
                val emoji = findSymbol(state, key)?.str("emoji").orEmpty().ifEmpty { "⚫" }
                PipGlyph(null, textMeasurer.measure(emoji, style))
            }
        }
    }
}
