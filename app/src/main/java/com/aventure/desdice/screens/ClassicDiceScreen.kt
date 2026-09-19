package com.aventure.desdice.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private data class ClassicRoll(
    val id: Int,
    val success: Int?,
    val fateEmoji: String?,
    val fateLabel: String?
)

/**
 * Equivalent Compose de render_classic_dice_page (dice_web.py) : le de
 * de reussite (1-6) et le de du destin, independants de toute histoire,
 * pense comme aide-memoire pendant une partie sur table.
 *
 * S'appuie sur do_classic_dice_roll(kind)/do_classic_dice_clear, deja
 * presentes dans game_api.py, et sur l'enrichissement fate_emoji/
 * fate_label ajoute a classic_dice_to_dict a l'etape 6 (evite de
 * dupliquer FATE_BY_KEY cote Kotlin).
 */
@Composable
fun ClassicDiceScreen(modifier: Modifier = Modifier) {
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var rolling by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<ClassicRoll>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyResult(result: String) {
        val parsed = JSONObject(result)
        val historyArray = parsed.optJSONArray("history")
        val list = mutableListOf<ClassicRoll>()
        if (historyArray != null) {
            for (i in 0 until historyArray.length()) {
                val entry = historyArray.getJSONObject(i)
                list.add(
                    ClassicRoll(
                        id = entry.optInt("id"),
                        success = if (entry.isNull("success")) null else entry.optInt("success"),
                        fateEmoji = entry.optString("fate_emoji", "").ifEmpty { null },
                        fateLabel = entry.optString("fate_label", "").ifEmpty { null }
                    )
                )
            }
        }
        history = list.reversed()
    }

    fun call(funcName: String, vararg args: Any) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { rolling = true; error = null }
            try {
                val result = python.getModule("game_api")
                    .callAttr("call_json", funcName, *args)
                    .toString()
                mutex.withLock { applyResult(result) }
            } catch (e: Exception) {
                mutex.withLock { error = "Erreur : ${e.message}" }
            } finally {
                mutex.withLock { rolling = false; loading = false }
            }
        }
    }

    LaunchedEffect(Unit) { call("classic_dice_state") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🎲 Des classiques",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Le dé classique et le dé du destin, indépendants de toute histoire " +
                "— pratique pour une partie sur table.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { call("do_classic_dice_roll", "both") },
                enabled = !rolling,
                modifier = Modifier.weight(1f)
            ) {
                Text("⚡ Les deux dés")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { call("do_classic_dice_roll", "success") },
                enabled = !rolling,
                modifier = Modifier.weight(1f)
            ) {
                Text("Dé classique")
            }
            OutlinedButton(
                onClick = { call("do_classic_dice_roll", "fate") },
                enabled = !rolling,
                modifier = Modifier.weight(1f)
            ) {
                Text("Dé du destin")
            }
        }
        OutlinedButton(
            onClick = { call("do_classic_dice_clear") },
            enabled = !rolling,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Effacer l'historique")
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Divider()

        if (loading) {
            CircularProgressIndicator()
        } else if (history.isEmpty()) {
            Text(
                text = "(aucun lancer pour l'instant)",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(history) { roll ->
                    val parts = mutableListOf<String>()
                    if (roll.success != null) parts.add("Dé classique = ${roll.success}")
                    if (roll.fateEmoji != null) parts.add("Destin = ${roll.fateEmoji} ${roll.fateLabel}")
                    Text(
                        text = "#${roll.id} — " + parts.joinToString(" | "),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
