// DicePanel.kt
// Panneau de dés : carte du dernier résultat, colonnes de dés, bouton « les deux », son.
// Extrait de MainGameScreen.kt sans changement de comportement.

package com.aventure.desdice

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import com.aventure.desdice.screens.DieBox
import com.aventure.desdice.screens.FateDieFace
import com.aventure.desdice.screens.FateFace
import com.aventure.desdice.screens.PipGlyph
import com.aventure.desdice.screens.SpinDurationMs
import com.aventure.desdice.screens.SpinState
import com.aventure.desdice.screens.TumblingDie
import com.aventure.desdice.screens.newSpinSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.FontPrefs
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.ui.rememberAppTextStyles
import com.aventure.desdice.ui.SoundPrefs
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// Dernier resultat + boutons de lancer
// =====================================================================

@OptIn(ExperimentalTextApi::class)
@Composable
fun DiceResultCard(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    // Narration automatique active (cle Mistral enregistree) : un "!" est alors envoye
    // directement a l'IA, et la note "a copier" n'est pas proposee.
    hasKey: Boolean = false
) {
    val sessionState by viewModel.sessionState.collectAsState()
    // fateFaces est une simple liste Kotlin statique (FATE_FACES, cf.
    // DiceSession.kt), exposee telle quelle par GameViewModel -- pas de
    // collectAsState().
    val fateFaces = viewModel.fateFaces
    val fonts = rememberAppFonts()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fontPrefs = remember(context) { FontPrefs.get(context) }
    val textStyles = rememberAppTextStyles(fonts, fontPrefs)
    val textMeasurer = rememberTextMeasurer()
    val playDiceRollSound = rememberDiceRollSound()

    var rolling by remember { mutableStateOf(false) }
    var sendingToAi by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // true quand l'erreur vient de l'envoi à l'IA (le lancer, lui, a bien eu lieu et reste en
    // attente) : le bouton « Réessayer » est alors proposé.
    var errorFromAi by remember { mutableStateOf(false) }

    // Animation de lancer : memes des 3D que la page "Des classiques". Le resultat
    // est deja tire ; l'etat de session n'est publie qu'a la fin de l'animation
    // pour ne pas gacher le suspense.
    var spin by remember { mutableStateOf<SpinState?>(null) }
    // Symboles des totems dessines sur les faces du de de reussite qui roule.
    var spinGlyphs by remember { mutableStateOf<List<List<PipGlyph>>?>(null) }
    val progress = remember { Animatable(0f) }

    // « Réessayer » après une erreur d'envoi à l'IA : le lancer est resté en attente dans la session,
    // il repart tel quel (GameEngine.retryAiRequest) -- inutile de relancer le dé.
    fun retryAi() {
        if (rolling || sendingToAi) return
        scope.launch {
            sendingToAi = true
            error = null
            errorFromAi = false
            try {
                val sent = viewModel.retryAiAwait()
                val aiError = sent.str("ai_error")
                if (aiError.isNotEmpty()) {
                    error = aiError
                    errorFromAi = true
                }
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                sendingToAi = false
            }
        }
    }

    fun roll(kind: String) {
        if (rolling) return
        scope.launch {
            rolling = true
            error = null
            errorFromAi = false
            try {
                // rollAwaitingAnimation() calcule le resultat sans le publier tout de
                // suite dans sessionState -- on ne le fait qu'apres l'animation
                // (publishSessionState plus bas), pour ne pas gacher le suspense.
                val result = viewModel.rollAwaitingAnimation(kind)
                val last = result.optJSONObject("last_result")
                val successValue =
                    if (last != null && !last.isNull("success")) last.optInt("success") else null
                val fateKey = last?.str("fate").orEmpty()

                val cubeFaces = fateFaces.take(6)
                val successSpec =
                    if (kind != "fate" && successValue != null && successValue in 1..6) {
                        newSpinSpec(faceIndex = successValue - 1, bounces = 3, timeScale = 1f)
                    } else null
                val fateIndex =
                    if (kind != "success" && fateKey.isNotEmpty() && cubeFaces.size == 6) {
                        cubeFaces.indexOfFirst { it.key == fateKey }
                    } else -1
                val fateSpec =
                    if (fateIndex >= 0) {
                        newSpinSpec(faceIndex = fateIndex, bounces = 2, timeScale = 0.88f)
                    } else null

                // Symboles a la place des points noirs (points noirs si ca echoue).
                spinGlyphs = if (successSpec != null && successValue != null) {
                    try {
                        val finalKeys = last?.optJSONArray("pip_choice")
                            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                            ?: emptyList()
                        buildSuccessDieGlyphs(
                            context, result, textMeasurer, successValue, finalKeys
                        )
                    } catch (e: Exception) {
                        null
                    }
                } else null

                if (successSpec != null || fateSpec != null) {
                    spin = SpinState(successSpec, fateSpec)
                    playDiceRollSound()
                    progress.snapTo(0f)
                    progress.animateTo(
                        1f,
                        tween(durationMillis = SpinDurationMs, easing = LinearEasing)
                    )
                }
                spin = null
                spinGlyphs = null
                viewModel.publishSessionState(result)

                // Narration automatique active : un "!" est envoye directement a l'IA (avec
                // le lancer en attente). Le "?" n'est PAS envoye : il debloque une quete
                // secondaire, transmise a l'IA par le bouton "Commencer" de cette quete.
                if (hasKey && fateKey == "exclamation") {
                    sendingToAi = true
                    try {
                        val sent = viewModel.sendAiMessageAwait("")
                        val aiError = sent.str("ai_error")
                        if (aiError.isNotEmpty()) {
                            error = aiError
                            errorFromAi = true
                        }
                    } finally {
                        sendingToAi = false
                    }
                }
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                spin = null
                spinGlyphs = null
                rolling = false
            }
        }
    }

    // Derniere valeur montree sur chaque de (comme last_of("success") / last_of("fate")).
    val history = sessionState?.optJSONArray("history")
    val lastSuccessRec = lastRecordWith(history, "success")
    val lastFateRec = lastRecordWith(history, "fate")
    val lastRec = history?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1) else null }
    val successValue = lastSuccessRec?.optInt("success")
    val pipKeys: List<String> = lastSuccessRec?.optJSONArray("pip_choice")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        ?.takeIf { it.isNotEmpty() }
        ?: List(successValue ?: 1) { sessionState?.optString("pip_symbol").orEmpty() }
    val fateFace: FateFace? = lastFateRec?.str("fate")?.let { key ->
        fateFaces.firstOrNull { it.key == key }
            ?.let { FateFace(it.emoji, it.label) }
    }
    // Le de qui n'a pas servi au dernier lancer est estompe.
    val successUsed = lastRec == null || !lastRec.isNull("success")
    val fateUsed = lastRec == null || lastRec.str("fate").isNotEmpty()
    val cubeFateFaces = fateFaces.take(6).map { FateFace(it.emoji, it.label) }
    val description = sessionState?.optJSONObject("last_result")?.str("description").orEmpty()

    Section(modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fullDieSize = 150.dp
            val centerButtonSize = 56.dp
            val gap = 12.dp

            // Meme logique que ClassicDiceScreen.kt : le bouton rond central
            // a besoin de sa propre place en plus des deux des ; si la
            // largeur ne suffit pas a les ecarter a taille pleine, on
            // retrecit legerement les deux des (jamais en dessous de 100dp)
            // plutot que de les faire se chevaucher.
            val rowDieSize = remember(maxWidth) {
                val required = fullDieSize * 2 + centerButtonSize + gap * 2
                if (maxWidth >= required) {
                    fullDieSize
                } else {
                    val available = (maxWidth - centerButtonSize - gap * 2).coerceAtLeast(0.dp)
                    (available / 2).coerceIn(100.dp, fullDieSize)
                }
            }

            val successDie: @Composable (Dp) -> Unit = { dieSize ->
                DieColumn(
                    caption = "Dé de réussite",
                    dimmed = !successUsed,
                    fonts = fonts
                ) {
                    val spec = spin?.success
                    if (spec != null) {
                        Box(Modifier.size(dieSize).graphicsLayer { rotationZ = -1f }) {
                            TumblingDie(spec, progress, null, fonts, textStyles, Color.White, pipGlyphs = spinGlyphs)
                        }
                    } else {
                        DieBox(
                            rotation = -1f,
                            background = Color.White,
                            size = dieSize,
                            onClick = { roll("success") },
                            enabled = !rolling,
                            description = "Dé de réussite : " + (successValue?.toString() ?: "pas encore lancé")
                        ) {
                            SymbolSuccessDieFace(
                                value = successValue,
                                pipKeys = pipKeys,
                                state = sessionState,
                                placeholderKey = sessionState?.optString("pip_symbol").orEmpty()
                            )
                        }
                    }
                }
            }
            val fateDie: @Composable (Dp) -> Unit = { dieSize ->
                DieColumn(
                    caption = "Dé du destin",
                    dimmed = !fateUsed,
                    fonts = fonts
                ) {
                    val spec = spin?.fate
                    if (spec != null) {
                        Box(Modifier.size(dieSize).graphicsLayer { rotationZ = 1f }) {
                            TumblingDie(spec, progress, cubeFateFaces, fonts, textStyles, FateDieBg)
                        }
                    } else {
                        DieBox(
                            rotation = 1f,
                            background = FateDieBg,
                            size = dieSize,
                            onClick = { roll("fate") },
                            enabled = !rolling,
                            description = "Dé du destin : " + (fateFace?.label ?: "pas encore lancé")
                        ) {
                            FateDieFace(fateFace, fonts, textStyles)
                        }
                    }
                }
            }

            if (maxWidth >= 316.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                ) {
                    successDie(rowDieSize)
                    RollBothButton(size = centerButtonSize, enabled = !rolling, onClick = { roll("both") })
                    fateDie(rowDieSize)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    successDie(fullDieSize)
                    RollBothButton(size = centerButtonSize, enabled = !rolling, onClick = { roll("both") })
                    fateDie(fullDieSize)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (description.isNotEmpty() && spin == null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle(fonts, 14.sp)
            )
        }

        // Evenements "?" / "!" : texte a signaler a l'IA narratrice (copiable).
        // Note "a copier" (! et ?) : seulement en mode manuel. Avec la narration automatique,
        // le "!" part tout seul et le "?" passe par la quete secondaire.
        val narratorNote = if (spin != null || hasKey) "" else narratorNoteFor(
            sessionState?.optJSONObject("last_result"), fateFaces
        )
        if (narratorNote.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            NarratorNoteBox(text = narratorNote, fonts = fonts)
        }

        // "?" avec la narration automatique : rappel de la quete secondaire debloquee.
        val unlockedQuest = sessionState?.optJSONObject("last_result")?.optJSONObject("side_quest")
        if (hasKey && spin == null && unlockedQuest != null &&
            isQuestOpen(sessionState, unlockedQuest.optInt("id"))
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "📜 Quête secondaire débloquée : ${questKindText(unlockedQuest.optString("kind"))}. " +
                    "Lance-la avec « Commencer » dans les quêtes secondaires.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = bodyStyle(fonts, 14.sp, Gold)
            )
        }

        if (sendingToAi) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "⌛ Le narrateur écrit...", style = bodyStyle(fonts, 14.sp))
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, style = bodyStyle(fonts, 14.sp, ErrorOnPhoto))
            // Le lancer est conservé : « Réessayer » renvoie le même lancer à l'IA, sans relancer le dé.
            if (hasKey && errorFromAi) {
                Spacer(modifier = Modifier.height(8.dp))
                ComicButton(
                    text = "🔄 Réessayer",
                    onClick = { retryAi() },
                    fonts = fonts,
                    kind = ButtonKind.Secondary,
                    enabled = !rolling && !sendingToAi,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun questKindText(kind: String): String =
    if (kind == "ami") "se faire un nouvel ami" else "trouver un nouvel objet (ou un nouveau totem)"

/** Demande envoyee a l'IA quand on demarre une quete secondaire. */
internal fun questRequestText(kind: String): String =
    if (kind == "ami") {
        "Je commence la quête secondaire : se faire un nouvel ami. Fais entrer en scène " +
            "un nouveau personnage et raconte cette rencontre."
    } else {
        "Je commence la quête secondaire : trouver un nouvel objet (ou un nouveau totem). " +
            "Raconte cette découverte."
    }

/** Vrai si la quete `id` existe encore et n'est pas terminee. */
private fun isQuestOpen(state: JSONObject?, id: Int): Boolean {
    val arr = state?.optJSONArray("side_quests") ?: return false
    for (i in 0 until arr.length()) {
        val q = arr.getJSONObject(i)
        if (q.optInt("id") == id) return q.optString("status") == "ouverte"
    }
    return false
}

/** Fond creme du de du destin (comme la page "Des classiques"). */
private val FateDieBg = Color(0xFFFFF7E0)

/** Dernier enregistrement de l'historique dont le champ `field` est renseigne (ni absent, ni null, ni vide). */
private fun lastRecordWith(history: org.json.JSONArray?, field: String): JSONObject? {
    if (history == null) return null
    for (i in history.length() - 1 downTo 0) {
        val record = history.getJSONObject(i)
        if (!record.isNull(field) && record.optString(field, "").isNotEmpty()) return record
    }
    return null
}

/**
 * Meme bruitage de lancer que ClassicDiceScreen.kt (dupliquee ici pour la
 * meme raison que RollBothButton : la fonction source est private).
 * Le fichier attendu est res/raw/dice_roll.<extension> (mp3, wav ou ogg),
 * A AJOUTER TOI-MEME au projet -- ce fichier ne peut pas etre genere ici.
 * L'animation de lancer dure SpinDurationMs = 1800 ms, un son de cette
 * longueur ou un peu moins colle le mieux au mouvement des des.
 */
@Composable
private fun rememberDiceRollSound(): () -> Unit {
    val context = LocalContext.current
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    var soundId by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        soundId = soundPool.load(context, R.raw.dice_roll, 1)
        onDispose { soundPool.release() }
    }
    return {
        // Volume lu à chaque lancer : Réglages > Sons (son coupé ou baissé) s'applique tout de suite.
        val volume = SoundPrefs.get(context).effectiveVolume
        if (soundId != 0 && volume > 0f) soundPool.play(soundId, volume, volume, 1, 0, 1f)
    }
}

@Composable
private fun DieColumn(
    caption: String,
    dimmed: Boolean,
    fonts: AppFonts,
    die: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.alpha(if (dimmed) 0.45f else 1f)) { die() }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = caption,
            style = TextStyle(
                fontFamily = fonts.display,
                fontSize = 13.6.sp,
                color = Color.White,
                shadow = TextShadow
            )
        )
    }
}

/** Bouton rond "⚡" (lancer les deux dés a la fois), pose entre les deux des -- meme habillage "BD" (ombre/bordure) que ComicButton, en cercle. Identique a celui de ClassicDiceScreen.kt, duplique ici car private la-bas. */
@Composable
private fun RollBothButton(size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.6f)
            .offset { IntOffset(if (pressed) 2.dp.roundToPx() else 0, if (pressed) 2.dp.roundToPx() else 0) }
            .hardShadow(if (pressed) 1.dp else 3.dp, size / 2, Ink)
            .background(Red, CircleShape)
            .border(3.dp, Ink, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .semantics { contentDescription = "Lancer les deux dés" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⚡",
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = (size.value * 0.42f).sp, color = Color.White)
        )
    }
}
