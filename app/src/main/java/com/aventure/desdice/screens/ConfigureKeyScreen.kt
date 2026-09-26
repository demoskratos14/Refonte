package com.aventure.desdice.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.R
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.AppTextStyles
import com.aventure.desdice.ui.BackgroundSlot
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.bodySp
import com.aventure.desdice.ui.rememberBackgroundPainter
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.ui.rememberAppTextStyles
import com.aventure.desdice.ui.titleSp
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch

// Palette recopiee des variables CSS de l'ancienne page HTML
// (render_configure_key_page, dice_web.py) -- gardee identique aux
// autres ecrans restyles (StorySelectorScreen) pour que l'appli reste
// visuellement coherente d'un ecran a l'autre.
private val Ink = Color(0xFF14161A)
private val Paper = Color(0xFFFBF3E1)
private val Red = Color(0xFFE0263C)
private val DangerRed = Color(0xFF8A1020)
private val PageBg = Color(0xFF1B140C)
private val KeyStatusBg = Color(0xFFFFF8EA)
private val LineColor = Color(0x4DFFFFFF) // trait clair, visible sur la photo
// Ombre portee des textes poses directement sur l'image (plus de carte papier).
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1.5f, 2f), 6f)
private val ErrorOnPhoto = Color(0xFFFFC9C9)

/**
 * Equivalent Compose de render_configure_key_page (dice_web.py), restyle
 * dans le theme "papier BD" de l'ancienne page HTML (meme palette, memes
 * polices Bangers/Nunito que StorySelectorScreen). Repose sur
 * GameViewModel.configScreenState / setMistralKeyAwait / clearMistralKeyAwait /
 * setMistralModelAwait (portage Kotlin pur, ex game_api.get_config_screen_state
 * et set_mistral_key/clear_mistral_key/set_mistral_model).
 *
 * L'image de fond (bg_key_page.jpg, a placer dans res/raw/) est affichee
 * en plein ecran (Crop, calee en haut) derriere le contenu : le haut de
 * l'ecran laisse voir le livre et sa magie, puis le titre et la carte
 * "papier" se posent sur la partie souterraine de l'image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureKeyScreen(
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GameViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val fonts = rememberAppFonts()
    val context = LocalContext.current
    val fontPrefs = remember(context) { FontPrefs.get(context) }
    val textStyles = rememberAppTextStyles(fonts, fontPrefs)

    val configState by viewModel.configScreenState.collectAsState()
    val loading = configState == null
    val hasKey = configState?.optBoolean("has_key", false) ?: false
    val maskedKey = configState?.optString("masked_key", "") ?: ""
    val selectedModel = configState?.optString("model", "") ?: ""
    val modelChoices = remember(configState) {
        val choicesArray = configState?.optJSONArray("model_choices")
        val choices = mutableListOf<Pair<String, String>>()
        if (choicesArray != null) {
            for (i in 0 until choicesArray.length()) {
                val pair = choicesArray.getJSONArray(i)
                choices.add(pair.getString(0) to pair.getString(1))
            }
        }
        choices
    }
    var saving by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Equivalent du toggleKeyForm() JS de l'ancienne page : le formulaire
    // de changement de cle reste replie tant qu'une cle est deja active.
    var showChangeForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadConfigScreenState() }

    fun setKey() {
        scope.launch {
            saving = true
            error = null
            try {
                viewModel.setMistralKeyAwait(keyInput)
                keyInput = ""
                showChangeForm = false
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    fun removeKey() {
        scope.launch {
            saving = true
            error = null
            try {
                viewModel.clearMistralKeyAwait()
                keyInput = ""
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    fun selectModel(value: String) {
        scope.launch {
            saving = true
            error = null
            try {
                viewModel.setMistralModelAwait(value)
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        // Hauteur a laquelle l'image (848 x 1264) est affichee en mode Crop :
        // le bord de la table (bas de la scene du livre) est a ~36 % de cette hauteur.
        val imageHeight = maxOf(maxHeight, maxWidth * (1264f / 848f))

        // --- Fond plein ecran (fixe, le contenu defile par-dessus) ---
        Image(
            painter = rememberBackgroundPainter(BackgroundSlot.KEY_PAGE),
            contentDescription = "Le Livre des Mille Histoires",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        // Voile leger, surtout en bas, pour garder les textes lisibles sur l'image.
        // Plus l'alpha (les 2 premiers chiffres apres 0x) est petit, plus l'image se voit.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x00000000),
                            0.35f to Color(0x14000000),
                            0.6f to Color(0x59000000),
                            1.0f to Color(0x8C000000)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // On laisse le livre et la magie visibles en haut de l'ecran.
            Spacer(Modifier.height(imageHeight * 0.36f))

            Text(
                text = "\uD83D\uDD11 Clé API Mistral",
                fontFamily = fonts.display,
                color = textStyles.titleColor ?: Color.White,
                fontSize = textStyles.titleSp(26f),
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                style = TextStyle(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 3f), blurRadius = 10f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 14.dp)
            )

            // --- Contenu pose directement sur l'image (plus de carte papier) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        if (hasKey && !showChangeForm) {
                            KeyStatusBlock(
                                maskedKey = maskedKey,
                                fonts = fonts,
                                textStyles = textStyles,
                                saving = saving,
                                onContinue = onDone,
                                onChangeKey = { showChangeForm = true },
                                onRemoveKey = { removeKey() }
                            )
                        } else {
                            NoKeyBlock(
                                fonts = fonts,
                                textStyles = textStyles,
                                keyInput = keyInput,
                                onKeyInputChange = { keyInput = it },
                                saving = saving,
                                showCancel = hasKey,
                                onCancel = { showChangeForm = false },
                                onActivate = { setKey() },
                                onSkip = onDone
                            )
                        }

                        error?.let {
                            Text(
                                text = it,
                                color = ErrorOnPhoto,
                                style = TextStyle(shadow = TextShadow),
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        // --- Separateur pointille + section modele ---
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                                .height(2.dp)
                                .background(LineColor)
                        )

                        Text(
                            text = "Modèle utilisé pour la narration",
                            fontFamily = fonts.bodyBold,
                            color = textStyles.bodyColor ?: Color.White,
                            style = TextStyle(shadow = TextShadow),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Text(
                            text = "Plus le modèle est riche, plus les histoires sont détaillées — " +
                                "mais aussi (légèrement) plus coûteux sur ton forfait Mistral. " +
                                "Modifiable à tout moment, même en cours de partie.",
                            fontSize = textStyles.bodySp(14f),
                            color = textStyles.bodyColor ?: Color.White.copy(alpha = 0.95f),
                            fontFamily = fonts.body,
                            style = TextStyle(shadow = TextShadow),
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        ModelDropdown(
                            modelChoices = modelChoices,
                            selectedModel = selectedModel,
                            enabled = !saving,
                            fonts = fonts,
                            textStyles = textStyles,
                            onSelect = { value -> selectModel(value) }
                        )
                    }
                }
            }
        }

        // --- Bouton Réglages (engrenage), par-dessus le contenu défilant ---
        SettingsGearButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        )
    }
}

@Composable
private fun KeyStatusBlock(
    maskedKey: String,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    saving: Boolean,
    onContinue: () -> Unit,
    onChangeKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KeyStatusBg)
            .border(2.dp, Ink, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "\u2705", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
        Column {
            Text(
                text = "Narration automatique activée",
                fontFamily = fonts.bodyBold,
                color = Ink
            )
            Text(
                text = maskedKey,
                fontSize = 12.sp,
                color = Ink.copy(alpha = 0.75f)
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    ComicButton(text = "Continuer vers les histoires \u2192", onClick = onContinue, fonts = fonts, textStyles = textStyles, enabled = !saving)
    Spacer(Modifier.height(8.dp))
    ComicButton(text = "Changer la clé", onClick = onChangeKey, fonts = fonts, textStyles = textStyles, secondary = true, enabled = !saving)
    Spacer(Modifier.height(8.dp))
    if (confirmRemove) {
        Text(
            text = "Retirer la clé API et revenir au mode manuel ?",
            fontSize = textStyles.bodySp(14f),
            color = textStyles.bodyColor ?: Color.White,
            fontFamily = fonts.body,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComicButton(
                text = "Confirmer",
                onClick = { confirmRemove = false; onRemoveKey() },
                fonts = fonts,
                textStyles = textStyles,
                danger = true,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
            ComicButton(
                text = "Annuler",
                onClick = { confirmRemove = false },
                fonts = fonts,
                textStyles = textStyles,
                secondary = true,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        ComicButton(text = "Retirer la clé", onClick = { confirmRemove = true }, fonts = fonts, textStyles = textStyles, danger = true, enabled = !saving)
    }
}

@Composable
private fun NoKeyBlock(
    fonts: AppFonts,
    textStyles: AppTextStyles,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    saving: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onSkip: () -> Unit,
) {
    Text(
        text = "Colle ici une clé API Mistral gratuite (compte gratuit sur " +
            "console.mistral.ai, email + mot de passe, sans carte bancaire) pour " +
            "que l'histoire s'écrive toute seule à chaque lancer, quelle que soit " +
            "l'histoire choisie ensuite.",
        fontSize = textStyles.bodySp(14f),
        color = textStyles.bodyColor ?: Color.White.copy(alpha = 0.95f),
        fontFamily = fonts.body,
        style = TextStyle(shadow = TextShadow),
        modifier = Modifier.padding(bottom = 12.dp)
    )

    BasicTextField(
        value = keyInput,
        onValueChange = onKeyInputChange,
        singleLine = true,
        enabled = !saving,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = TextStyle(fontFamily = fonts.body, fontSize = textStyles.bodySp(15f), color = Ink),
        cursorBrush = SolidColor(Ink),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(2.dp, Ink, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box {
                if (keyInput.isEmpty()) {
                    Text(
                        text = "Clé API Mistral",
                        fontFamily = fonts.body,
                        fontSize = textStyles.bodySp(15f),
                        color = Ink.copy(alpha = 0.5f)
                    )
                }
                innerTextField()
            }
        }
    )
    Spacer(Modifier.height(10.dp))

    ComicButton(
        text = "\uD83D\uDD11 Activer la narration automatique",
        onClick = onActivate,
        fonts = fonts,
        textStyles = textStyles,
        enabled = !saving && keyInput.isNotBlank()
    )
    Spacer(Modifier.height(8.dp))
    if (showCancel) {
        ComicButton(text = "Annuler", onClick = onCancel, fonts = fonts, textStyles = textStyles, secondary = true, enabled = !saving)
    } else {
        ComicButton(text = "Passer pour l'instant \u2192", onClick = onSkip, fonts = fonts, textStyles = textStyles, secondary = true, enabled = !saving)
    }
}

/** Reproduit .btn / .btn.secondary / .btn.danger de l'ancien CSS : fond
 * plat, bordure epaisse, "ombre" facon BD (rectangle decale, pas de flou). */
@Composable
private fun ComicButton(
    text: String,
    onClick: () -> Unit,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        danger -> DangerRed
        secondary -> Color.White
        else -> Red
    }
    val textColor = if (secondary) Ink else Color.White
    val alpha = if (enabled) 1f else 0.5f

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 3.dp, y = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(bg.copy(alpha = alpha))
                .border(3.dp, Ink.copy(alpha = alpha), RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = fonts.display,
                color = textColor.copy(alpha = alpha),
                fontSize = textStyles.titleSp(16f),
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Menu deroulant pour le choix du modele Mistral -- remplace la liste a
 * puces (un choix par ligne) par un select compact, plus adapte a une
 * liste de 3 options sur mobile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    modelChoices: List<Pair<String, String>>,
    selectedModel: String,
    enabled: Boolean,
    fonts: AppFonts,
    textStyles: AppTextStyles,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = modelChoices.firstOrNull { it.first == selectedModel }?.second
        ?: "Choisir un modèle"

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(2.dp, Ink, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Text(
                text = selectedLabel,
                fontFamily = fonts.body,
                color = Ink,
                fontSize = textStyles.bodySp(14f),
                modifier = Modifier.weight(1f)
            )
            Text(text = "\u25BE", fontSize = 18.sp, color = Ink)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            modelChoices.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontFamily = fonts.body,
                            color = Ink,
                            fontSize = textStyles.bodySp(14f)
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}
