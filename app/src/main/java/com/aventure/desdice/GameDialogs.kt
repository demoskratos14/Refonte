// GameDialogs.kt
// Fenêtres de la page de jeu : choix du symbole actif (mode de lancer + symboles)
// et valeurs autorisées. Extrait de MainGameScreen.kt sans changement de comportement.

package com.aventure.desdice

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import org.json.JSONObject

// =====================================================================
// Symbole actif (selection + mode de lancer)
// =====================================================================

/**
 * Symbole actif : un resume (mode + symboles utilises) et un bouton qui ouvre la
 * fenetre de choix -- pour ne pas surcharger la page de jeu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymbolPicker(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()
    var showDialog by remember { mutableStateOf(false) }

    val pipMode = sessionState?.optString("pip_mode").orEmpty().ifEmpty { "single" }
    val activeKeys: List<String> = if (pipMode == "single") {
        listOfNotNull(sessionState?.optString("pip_symbol")?.takeIf { it.isNotEmpty() })
    } else {
        sessionState?.optJSONArray("enabled_symbols")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: emptyList()
    }
    val summary = when (pipMode) {
        "random" -> "🎲 Aléatoire : ${activeKeys.size} symbole(s) dans le tirage"
        "mixed" -> "🔀 Mixte : ${activeKeys.size} symbole(s) mélangés sur la face"
        else -> "Symbole unique : " +
            (findSymbol(sessionState, activeKeys.firstOrNull().orEmpty())?.str("label").orEmpty())
    }

    Section(modifier) {
        SectionTitle("Symbole actif", fonts)
        Text(text = summary, style = bodyStyle(fonts, 15.sp))
        if (activeKeys.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                activeKeys.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        SymbolIcon(findSymbol(sessionState, key), 26.dp, 20.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        ComicButton(
            text = "Choisir le symbole",
            onClick = { showDialog = true },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showDialog) {
        SymbolPickerDialog(viewModel = viewModel, onDismiss = { showDialog = false })
    }
}

/** Fenetre de choix : mode de lancer (unique / aleatoire / mixte) + liste des symboles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPickerDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()

    // Purement cosmetique (ces reglages ne peuvent pas echouer cote moteur) :
    // retombe a false des que sessionState se met a jour.
    var isLoading by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState) { isLoading = false }

    val pipMode = sessionState?.optString("pip_mode").orEmpty().ifEmpty { "single" }
    val activeSingle = sessionState?.optString("pip_symbol").orEmpty()
    val enabled: Set<String> = sessionState?.optJSONArray("enabled_symbols")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
        ?: emptySet()
    val symbols = buildList<JSONObject> {
        sessionState?.optJSONArray("all_symbols")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clip(shape)
                .background(Paper)
                .border(3.dp, Ink, shape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "Symboles",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Mode de lancer",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Le moteur (DiceSession.set_pip_mode) fonctionne en bascule : il
                    // n'accepte que "random" / "mixed", et redemander le mode deja actif
                    // le desactive (retour au symbole unique).
                    ModeOption(
                        text = "Unique",
                        selected = pipMode == "single",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "single") { isLoading = true; viewModel.setPipMode(pipMode) } }
                    )
                    ModeOption(
                        text = "🎲 Aléatoire",
                        selected = pipMode == "random",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "random") { isLoading = true; viewModel.setPipMode("random") } }
                    )
                    ModeOption(
                        text = "🔀 Mixte",
                        selected = pipMode == "mixed",
                        fonts = fonts,
                        modifier = Modifier.weight(1f),
                        onClick = { if (pipMode != "mixed") { isLoading = true; viewModel.setPipMode("mixed") } }
                    )
                }
                Text(
                    text = when (pipMode) {
                        "random" -> "Un symbole tiré au sort par lancer. Touche les symboles pour les inclure ou les exclure du tirage (un au moins reste actif)."
                        "mixed" -> "Un symbole différent sur chaque point du dé. Touche les symboles pour les inclure ou les exclure du tirage (un au moins reste actif)."
                        else -> "Touche un symbole pour le choisir."
                    },
                    style = TextStyle(fontFamily = fonts.body, fontSize = 13.sp, color = Ink),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    symbols.forEach { symbol ->
                        val key = symbol.optString("key")
                        val selected = if (pipMode == "single") key == activeSingle else key in enabled
                        val chipShape = RoundedCornerShape(20.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .alpha(if (selected) 1f else 0.7f)
                                .clip(chipShape)
                                .background(if (selected) Red else Color.White)
                                .border(2.dp, Ink, chipShape)
                                .clickable(enabled = !isLoading, role = Role.Button) {
                                    isLoading = true
                                    if (pipMode == "single") {
                                        viewModel.setPipSymbol(key)
                                    } else {
                                        viewModel.toggleEnabledSymbol(key)
                                    }
                                }
                                .padding(horizontal = 12.dp)
                                // L'état choisi/non choisi n'était visible que par la couleur.
                                .semantics { stateDescription = if (selected) "sélectionné" else "non sélectionné" }
                        ) {
                            SymbolIcon(symbol, 22.dp, 18.sp)
                            Text(
                                text = symbol.optString("label"),
                                maxLines = 1,
                                style = TextStyle(
                                    fontFamily = fonts.bodyBold,
                                    fontSize = 14.sp,
                                    color = if (selected) Color.White else Ink
                                )
                            )
                        }
                    }
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ink)
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

@Composable
private fun ModeOption(
    text: String,
    selected: Boolean,
    fonts: AppFonts,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Red else Color.White)
            .border(2.dp, Ink, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
                color = if (selected) Color.White else Ink
            )
        )
    }
}

// =====================================================================
// Valeurs autorisees (fenetre)
// =====================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowedValuesDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val fonts = rememberAppFonts()

    var allowedValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var allowedFates by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(sessionState) {
        allowedValues = sessionState?.optJSONArray("allowed_success_values")?.let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        } ?: listOf(1, 2, 3, 4, 5, 6)

        allowedFates = sessionState?.optJSONArray("allowed_fate_keys")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: FATE_OPTIONS.map { it.first }
    }

    // La liste affichee est rafraichie par le LaunchedEffect ci-dessus des que
    // le moteur publie le nouvel etat (ces reglages ne peuvent pas echouer).

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Paper)
                .border(3.dp, Ink, shape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "Valeurs autorisées",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, letterSpacing = 1.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Dé de réussite (1-6)",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (1..6).forEach { value ->
                        ToggleRow(
                            checked = value in allowedValues,
                            label = value.toString(),
                            fonts = fonts,
                            onToggle = { viewModel.toggleAllowedValue(value) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ComicButton(
                    text = "Réinitialiser",
                    onClick = { viewModel.resetAllowedValues() },
                    fonts = fonts,
                    kind = ButtonKind.Paper,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Dé du destin",
                    style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 16.sp, color = Ink),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FATE_OPTIONS.forEach { (key, label) ->
                        ToggleRow(
                            checked = key in allowedFates,
                            label = label,
                            fonts = fonts,
                            onToggle = { viewModel.toggleAllowedFate(key) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ComicButton(
                    text = "Réinitialiser",
                    onClick = { viewModel.resetAllowedFate() },
                    fonts = fonts,
                    kind = ButtonKind.Paper,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )

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

@Composable
private fun ToggleRow(
    checked: Boolean,
    label: String,
    fonts: AppFonts,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .toggleable(
                value = checked,
                onValueChange = { onToggle() },
                role = Role.Checkbox
            )
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) Red else Color.White)
                .border(2.dp, Ink, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(text = "✓", style = TextStyle(fontSize = 15.sp, color = Color.White))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(fontFamily = fonts.body, fontSize = 16.sp, color = Ink)
        )
    }
}
