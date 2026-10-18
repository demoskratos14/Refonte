// TotemsAndQuests.kt
// Jauges totémiques et quêtes secondaires de la page de jeu.
// Extrait de MainGameScreen.kt sans changement de comportement.

package com.aventure.desdice

import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import com.aventure.desdice.screens.TotemManagementDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.rememberAppFonts
import com.aventure.desdice.viewmodel.GameViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TOTEM_THRESHOLD = 15

// =====================================================================
// Jauges totemiques
// =====================================================================

@Composable
fun TotemGaugesRow(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    // Clic sur l'icone d'un totem : ouvre sa fiche (pouvoirs / capacite speciale).
    onTotemClick: ((String) -> Unit)? = null
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val scope = rememberCoroutineScope()
    val fonts = rememberAppFonts()

    var symbols by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Texte narratif renvoye par useTotemEnergyAwait quand un pouvoir se
    // manifeste -- distinct de "error", reserve aux vraies erreurs techniques.
    var effectMessage by remember { mutableStateOf<String?>(null) }
    var showTotemManager by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        val session = sessionState
        symbols = buildMap {
            session?.optJSONArray("all_symbols")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    put(obj.optString("key"), obj)
                }
            }
        }
        isLoading = false
    }

    fun useTotem(key: String) {
        scope.launch {
            isLoading = true
            error = null
            try {
                val result = viewModel.useTotemEnergyAwait(key)
                val effect = result.str("effect")
                val aiError = result.str("ai_error")
                if (effect.isNotEmpty()) effectMessage = effect
                if (aiError.isNotEmpty()) error = aiError
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Section(modifier) {
        SectionTitle("Jauges totémiques", fonts)

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(symbols.toList()) { (key, symbol) ->
                    // Icone cliquable seulement si une fiche existe.
                    val hasInfo = sessionState?.optJSONObject("totem_info")?.has(key) == true
                    TotemGaugeItem(
                        symbol = symbol,
                        energy = sessionState?.optJSONObject("totem_energy")?.optInt(key, 0) ?: 0,
                        fonts = fonts,
                        onUse = { useTotem(key) },
                        onInfo = if (hasInfo) ({ onTotemClick?.invoke(key) }) else null
                    )
                }
            }
        }

        // Affiches en dehors du "if (isLoading)" : sinon le message pose juste
        // avant la fin du chargement disparaitrait aussitot.
        error?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp, ErrorOnPhoto),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        effectMessage?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        ComicButton(
            text = "➕ Ajouter / gérer les totems",
            onClick = { showTotemManager = true },
            fonts = fonts,
            kind = ButtonKind.Secondary,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showTotemManager) {
        TotemManagementDialog(
            viewModel = viewModel,
            onDismiss = { showTotemManager = false }
        )
    }
}

@Composable
private fun TotemGaugeItem(
    symbol: JSONObject,
    energy: Int,
    fonts: AppFonts,
    onUse: () -> Unit,
    onInfo: (() -> Unit)? = null
) {
    val isReady = energy >= TOTEM_THRESHOLD

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(92.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isReady) Gold.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.25f))
                .border(
                    if (isReady) 3.dp else 2.dp,
                    if (isReady) Gold else Color.White.copy(alpha = 0.5f),
                    CircleShape
                )
                .then(if (onInfo != null) Modifier.clickable(onClickLabel = "Voir la fiche du totem", role = Role.Button, onClick = onInfo) else Modifier)
        ) {
            SymbolIcon(symbol, 46.dp, 30.sp)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = symbol.optString("label"),
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = bodyStyle(fonts, 12.sp)
        )
        Text(
            text = "$energy/$TOTEM_THRESHOLD",
            style = boldStyle(fonts, 13.sp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        GaugeBar(
            fraction = energy.toFloat() / TOTEM_THRESHOLD,
            color = if (isReady) Gold else Color.White,
            height = 8.dp
        )

        Spacer(modifier = Modifier.height(6.dp))

        ComicButton(
            text = "Utiliser",
            onClick = onUse,
            fonts = fonts,
            enabled = isReady,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =====================================================================
// Quetes secondaires
// =====================================================================

@Composable
fun SideQuestsList(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    // Narration automatique active : "Commencer" envoie la demande a l'IA. Sinon la
    // demande est copiee dans le presse-papiers (a coller dans l'IA de ton choix).
    hasKey: Boolean = false
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val scope = rememberCoroutineScope()
    val fonts = rememberAppFonts()
    val clipboard = LocalClipboard.current

    var sideQuests by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Seulement les quetes OUVERTES (openSideQuests()) :
    // une quete terminee ou commencee disparait de la liste.
    LaunchedEffect(sessionState) {
        sideQuests = buildList {
            sessionState?.optJSONArray("side_quests")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val quest = arr.getJSONObject(i)
                    if (quest.optString("status") == "ouverte") add(quest)
                }
            }
        }
        isLoading = false
    }

    // "Commencer" : la demande de quete part a l'IA (le lancer "?" en attente est ajoute
    // automatiquement par sendAiMessage()), puis la quete est retiree de la liste -- il
    // n'y a donc pas de bouton "Terminer". Si l'IA renvoie une erreur, la quete est
    // conservee pour pouvoir reessayer.
    fun startQuest(quest: JSONObject) {
        val request = questRequestText(quest.optString("kind"))
        scope.launch {
            isLoading = true
            error = null
            info = null
            try {
                if (hasKey) {
                    val sent = viewModel.sendAiMessageAwait(request)
                    val aiError = sent.str("ai_error")
                    if (aiError.isNotEmpty()) {
                        error = aiError
                        return@launch
                    }
                } else {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", request)))
                    info = "Demande copiée dans le presse-papiers."
                }
                viewModel.completeSideQuestAwait(quest.optInt("id"))
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Section(modifier) {
        SectionTitle("Quêtes secondaires", fonts)

        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                if (hasKey) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "⌛ Le narrateur écrit...", style = bodyStyle(fonts, 14.sp))
                }
            }
        } else if (sideQuests.isEmpty()) {
            Text(
                text = "Aucune quête secondaire en cours",
                style = bodyStyle(fonts, 14.sp)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(sideQuests) { quest ->
                    SideQuestItem(
                        quest = quest,
                        fonts = fonts,
                        onStart = { startQuest(quest) }
                    )
                }
            }
        }

        info?.let {
            Text(
                text = it,
                style = bodyStyle(fonts, 14.sp),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        error?.let {
            Text(
                text = "⚠️ $it",
                style = bodyStyle(fonts, 14.sp, ErrorOnPhoto),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SideQuestItem(
    quest: JSONObject,
    fonts: AppFonts,
    onStart: () -> Unit
) {
    val kind = quest.optString("kind")
    val kindText = if (kind == "ami") "👥 Nouvel ami" else "🎁 Nouvel objet/totem"
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(shape)
            .background(Ink.copy(alpha = 0.45f))
            .border(2.dp, Color.White.copy(alpha = 0.4f), shape)
            .padding(10.dp)
    ) {
        Text(text = "⭐ Quête secondaire", style = boldStyle(fonts, 12.sp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = kindText, style = bodyStyle(fonts, 13.sp))
        Spacer(modifier = Modifier.height(8.dp))
        ComicButton(
            text = "Commencer",
            onClick = onStart,
            fonts = fonts,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
