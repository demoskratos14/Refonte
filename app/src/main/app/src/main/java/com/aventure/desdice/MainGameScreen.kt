package com.aventure.desdice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.aventure.desdice.viewmodel.GameViewModel
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Composable
fun MainGameScreen(viewModel: GameViewModel, onChangeStory: () -> Unit = {}) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    var showOptions by remember { mutableStateOf(false) }
    var showAllowedValues by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Animorph - Aventure héroïque",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = sessionState?.optString("story_title") ?: "Aventure",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Le jeu de rôle par dés",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Equivalent du lien "Changer d'histoire" de l'ancienne page de jeu
                    // (layout() dans dice_web.py) : ouvre le carrousel de choix
                    // sans rien modifier tant qu'un autre choix n'est pas fait.
                    TextButton(onClick = onChangeStory) {
                        Text(
                            text = "🔁 Changer d'histoire",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 8.dp)
        ) {
            DiceResultCard(viewModel = viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            TotemGaugesRow(viewModel = viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            ThreatGauge(viewModel = viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            SideQuestsList(viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            SymbolPicker(viewModel = viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { showAllowedValues = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configurer les valeurs autorisées")
            }

            Spacer(modifier = Modifier.height(16.dp))

            HistoryList(viewModel = viewModel)
        }
    }

    if (showAllowedValues) {
        AllowedValuesDialog(
            viewModel = viewModel,
            onDismiss = { showAllowedValues = false }
        )
    }
}

@Composable
fun DiceResultCard(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    var lastResult by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionState) {
        lastResult = sessionState?.optJSONObject("last_result")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLoading) {
            CircularProgressIndicator()
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            lastResult?.let { result ->
                val success = result.optInt("success", -1)
                val fate = result.optString("fate", "")
                val description = result.optString("description", "")

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (success != -1) {
                        val pipSymbol = sessionState?.optString("pip_symbol") ?: ""
                        val pipMode = sessionState?.optString("pip_mode") ?: "single"

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = "Résultat : $success",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = pipSymbol,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    }

                    if (fate.isNotEmpty()) {
                        val fateFaces by viewModel.fateFaces.collectAsState()
                        val fateFace = fateFaces.find { it.optString("key") == fate }

                        fateFace?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = it.optString("emoji"),
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = it.optString("label"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = it.optString("desc"),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } ?: run {
                Text(
                    text = "Aucun lancer effectué",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        mutex.withLock {
                            isLoading = true
                            error = null
                        }
                        try {
                            val result = python.getModule("game_api")
                                .callAttr("call_json", "do_roll", "success")
                                .toString()
                            viewModel.loadSessionState(result)
                            lastResult = JSONObject(result).optJSONObject("last_result")
                        } catch (e: Exception) {
                            error = "Erreur : ${e.message}"
                        } finally {
                            mutex.withLock { isLoading = false }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lancer réussite")
            }

            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        mutex.withLock {
                            isLoading = true
                            error = null
                        }
                        try {
                            val result = python.getModule("game_api")
                                .callAttr("call_json", "do_roll", "fate")
                                .toString()
                            viewModel.loadSessionState(result)
                            lastResult = JSONObject(result).optJSONObject("last_result")
                        } catch (e: Exception) {
                            error = "Erreur : ${e.message}"
                        } finally {
                            mutex.withLock { isLoading = false }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lancer destin")
            }

            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        mutex.withLock {
                            isLoading = true
                            error = null
                        }
                        try {
                            val result = python.getModule("game_api")
                                .callAttr("call_json", "do_roll", "both")
                                .toString()
                            viewModel.loadSessionState(result)
                            lastResult = JSONObject(result).optJSONObject("last_result")
                        } catch (e: Exception) {
                            error = "Erreur : ${e.message}"
                        } finally {
                            mutex.withLock { isLoading = false }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Lancer les deux")
            }
        }
    }
}

@Composable
fun HistoryList(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    val historyState = sessionState?.optJSONArray("history")
    val listState = rememberLazyListState()

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            historyState?.let { history ->
                items((0 until history.length()).map { history.getJSONObject(it) }.reversed()) { record ->
                    HistoryItem(
                        record = record,
                        viewModel = viewModel,
                        python = python,
                        mutex = mutex
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        mutex.withLock {
                            mutex.withLock {
                                try {
                                    val result = python.getModule("game_api")
                                        .callAttr("call_json", "do_undo")
                                        .toString()
                                    viewModel.loadSessionState(result)
                                } catch (e: Exception) {
                                    // Gérer l'erreur si nécessaire
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Undo, contentDescription = "Annuler")
                Spacer(modifier = Modifier.size(4.dp))
                Text("Annuler")
            }

            Button(
                onClick = {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        mutex.withLock {
                            try {
                                val result = python.getModule("game_api")
                                    .callAttr("call_json", "do_clear")
                                    .toString()
                                viewModel.loadSessionState(result)
                            } catch (e: Exception) {
                                // Gérer l'erreur si nécessaire
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Réinitialiser")
                Spacer(modifier = Modifier.size(4.dp))
                Text("Réinitialiser")
            }
        }
    }
}

@Composable
fun HistoryItem(
    record: JSONObject,
    viewModel: GameViewModel,
    python: Python,
    mutex: Mutex
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val success = record.optInt("success", -1)
    val fate = record.optString("fate", "")
    val note = record.optString("note", "")
    val pipChoice = record.optJSONArray("pip_choice")
    val threatTriggered = record.optBoolean("threat_triggered")
    val sideQuest = record.optJSONObject("side_quest")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (success != -1) {
                val pipSymbol = if (pipChoice != null && pipChoice.length() > 0) {
                    pipChoice.getString(0)
                } else {
                    sessionState?.optString("pip_symbol") ?: ""
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${record.optInt("id")}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    if (note.isNotEmpty()) {
                        Text(
                            text = "[$note]",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                    }
                    Text(
                        text = "Réussite=$success",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pipSymbol,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else if (fate.isNotEmpty()) {
                val fateFaces by viewModel.fateFaces.collectAsState()
                val fateFace = fateFaces.find { it.optString("key") == fate }

                fateFace?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${record.optInt("id")}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        if (note.isNotEmpty()) {
                            Text(
                                text = "[$note]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                        }
                        Text(
                            text = "Destin=${it.optString("emoji")} ${it.optString("label")}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TotemGaugesRow(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }
    val sessionState by viewModel.sessionState.collectAsState()

    var symbols by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Texte narratif renvoyé par do_use_totem_energy quand un pouvoir se
    // manifeste (ex. "Le pouvoir du totem Aigle se manifeste : vol...") --
    // distinct de "error", qui reste réservé aux vraies erreurs techniques.
    var effectMessage by remember { mutableStateOf<String?>(null) }

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
    }

    Column(modifier = modifier) {
        Text(
            text = "Jauges totémiques",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(symbols.toList()) { (key, symbol) ->
                    TotemGaugeItem(
                        key = key,
                        symbol = symbol,
                        viewModel = viewModel,
                        python = python,
                        mutex = mutex,
                        onLoadingChange = { isLoading = it },
                        onError = { error = it },
                        onEffect = { effectMessage = it }
                    )
                }
            }
        }

        // Affichés en dehors du "if (isLoading)" : sinon le message posé juste
        // avant la fin du chargement disparaissait aussitôt (on repasse dans
        // la branche "else" qui ne les affichait pas).
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        effectMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun TotemGaugeItem(
    key: String,
    symbol: JSONObject,
    viewModel: GameViewModel,
    python: Python,
    mutex: Mutex,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onEffect: (String) -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val energy = sessionState?.optJSONObject("totem_energy")?.optInt(key, 0) ?: 0
    val threshold = 15
    val isReady = energy >= threshold
    val label = symbol.optString("label")
    val emoji = symbol.optString("emoji")
    val image = symbol.optString("image")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
			.size(60.dp)
                .clip(CircleShape)
                .background(if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (image.isNotEmpty()) {
                AsyncImage(
                    model = android.util.Base64.decode(image, android.util.Base64.DEFAULT),
                    contentDescription = label,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$energy/$threshold",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )

        LinearProgressIndicator(
            progress = { energy.toFloat() / threshold },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        Button(
            onClick = {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    mutex.withLock {
                        onLoadingChange(true)
                        onError("")
                    }
                    try {
                        val result = python.getModule("game_api")
                            .callAttr("call_json", "do_use_totem_energy", key)
                            .toString()
                        val parsed = JSONObject(result)
                        val effect = parsed.optString("effect", "")
                        val aiError = parsed.optString("ai_error", "")
                        if (effect.isNotEmpty()) onEffect(effect)
                        if (aiError.isNotEmpty()) onError(aiError)
                        viewModel.loadSessionState(result)
                    } catch (e: Exception) {
                        onError("Erreur : ${e.message}")
                    } finally {
                        mutex.withLock { onLoadingChange(false) }
                    }
                }
            },
            enabled = isReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = "Utiliser")
            Spacer(modifier = Modifier.size(4.dp))
            Text("Utiliser")
        }
    }
}

@Composable
fun ThreatGauge(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val threatLevel = sessionState?.optInt("threat_level", 0) ?: 0
    val threshold = 10

    Column(modifier = modifier) {
        Text(
            text = "Jauge de menace",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Niveau actuel : $threatLevel/$threshold",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        LinearProgressIndicator(
            progress = { threatLevel.toFloat() / threshold },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (threatLevel >= threshold) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.primary
        )

        if (threatLevel >= threshold) {
            Text(
                text = "⚠️ Seuil atteint : complication secondaire !",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun SideQuestsList(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    var sideQuests by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionState) {
        sideQuests = buildList {
            sessionState?.optJSONArray("side_quests")?.let { arr ->
                for (i in 0 until arr.length()) {
                    add(arr.getJSONObject(i))
                }
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = "Quêtes secondaires",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else if (sideQuests.isEmpty()) {
            Text(
                text = "Aucune quête secondaire en cours",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(sideQuests) { quest ->
                    SideQuestItem(
                        quest = quest,
                        viewModel = viewModel,
                        python = python,
                        mutex = mutex,
                        onLoadingChange = { isLoading = it },
                        onError = { error = it },
                        onQuestCompleted = { completedQuest ->
                            sideQuests = sideQuests.filter { it.optInt("id") != completedQuest.optInt("id") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SideQuestItem(
    quest: JSONObject,
    viewModel: GameViewModel,
    python: Python,
    mutex: Mutex,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onQuestCompleted: (JSONObject) -> Unit
) {
    val kind = quest.optString("kind")
    val status = quest.optString("status")
    val kindText = if (kind == "ami") "Nouvel ami" else "Nouvel objet/totem"

    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Text(
            text = "⭐ Quête secondaire",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = kindText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    mutex.withLock {
                        onLoadingChange(true)
                        onError("")
                    }
                    try {
                        val result = python.getModule("game_api")
                            .callAttr("call_json", "do_complete_side_quest", quest.optInt("id"))
                            .toString()
                        viewModel.loadSessionState(result)
                        onQuestCompleted(quest)
                    } catch (e: Exception) {
                        onError("Erreur : ${e.message}")
                    } finally {
                        mutex.withLock { onLoadingChange(false) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = status == "ouverte"
        ) {
            Text("Terminer")
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymbolPicker(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    var symbols by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var pipMode by remember { mutableStateOf("single") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expandedMode by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        symbols = buildMap {
            sessionState?.optJSONArray("all_symbols")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    put(obj.optString("key"), obj)
                }
            }
        }
        pipMode = sessionState?.optString("pip_mode") ?: "single"
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                text = "Symbole actif",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { expandedMode = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Paramètres de mode")
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                symbols.forEach { (key, symbol) ->
                    val isActive = when (pipMode) {
                        "single" -> sessionState?.optString("pip_symbol") == key
                        "random", "mixed" -> sessionState
                            ?.optJSONArray("enabled_symbols")
                            ?.let { arr ->
                                (0 until arr.length()).any { arr.getString(it) == key }
                            } ?: false
                        else -> false
                    }

                    val label = symbol.optString("label")
                    val emoji = symbol.optString("emoji")
                    val image = symbol.optString("image")

                    FilterChip(
                        selected = isActive,
                        onClick = {
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                mutex.withLock {
                                    isLoading = true
                                    error = null
                                }
                                try {
                                    val result = if (pipMode == "single") {
                                        python.getModule("game_api")
                                            .callAttr("call_json", "do_set_pip_symbol", key)
                                            .toString()
                                    } else {
                                        python.getModule("game_api")
                                            .callAttr("call_json", "do_toggle_enabled_symbol", key)
                                            .toString()
                                    }
                                    viewModel.loadSessionState(result)
                                } catch (e: Exception) {
                                    error = "Erreur : ${e.message}"
                                } finally {
                                    mutex.withLock { isLoading = false }
                                }
                            }
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (image.isNotEmpty()) {
                                    AsyncImage(
                                        model = android.util.Base64.decode(image, android.util.Base64.DEFAULT),
                                        contentDescription = label,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(text = emoji)
                                }
                                Text(text = label, maxLines = 1)
                            }
                        },
                        modifier = Modifier.height(40.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expandedMode,
            onDismissRequest = { expandedMode = false },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Mode de lancer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                listOf("single" to "Symbole unique", "random" to "Aléatoire", "mixed" to "Mixte").forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = pipMode == mode,
                                onValueChange = { checked ->
                                    if (checked) {
                                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                                            mutex.withLock {
                                                isLoading = true
                                                error = null
                                            }
                                            try {
                                                val result = python.getModule("game_api")
                                                    .callAttr("call_json", "do_set_pip_mode", mode)
                                                    .toString()
                                                viewModel.loadSessionState(result)
                                                pipMode = mode
                                            } catch (e: Exception) {
                                                error = "Erreur : ${e.message}"
                                            } finally {
                                                mutex.withLock { isLoading = false }
                                            }
                                        }
                                    }
                                },
                                role = Role.RadioButton
                            )
                            .padding(8.dp)
                    ) {
                        RadioButton(
                            selected = pipMode == mode,
                            onClick = null
                        )
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AllowedValuesDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    val python = remember { Python.getInstance() }
    val mutex = remember { Mutex() }

    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var allowedValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var allowedFates by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(sessionState) {
        allowedValues = sessionState?.optJSONArray("allowed_success_values")?.let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }
        } ?: listOf(1, 2, 3, 4, 5, 6)

        allowedFates = sessionState?.optJSONArray("allowed_fate_keys")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: listOf("coeur", "question", "soleil", "etoile", "exclamation", "spirale")
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Valeurs autorisées",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Dé de réussite (1-6)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (1..6).forEach { value ->
                        val isChecked = value in allowedValues

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.toggleable(
                                value = isChecked,
                                onValueChange = { checked ->
                                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                                        mutex.withLock {
                                            isLoading = true
                                            error = null
                                        }
                                        try {
                                            val result = python.getModule("game_api")
                                                .callAttr("call_json", "do_toggle_allowed_value", value)
                                                .toString()
                                            viewModel.loadSessionState(result)
                                            allowedValues = sessionState?.optJSONArray("allowed_success_values")
                                                ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
                                                ?: emptyList()
                                        } catch (e: Exception) {
                                            error = "Erreur : ${e.message}"
                                        } finally {
                                            mutex.withLock { isLoading = false }
                                        }
                                    }
                                },
                                role = Role.Checkbox
                            )
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null
                            )
                            Text(text = value.toString())
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            mutex.withLock {
                                isLoading = true
                                error = null
                            }
                            try {
                                val result = python.getModule("game_api")
                                    .callAttr("call_json", "do_reset_allowed_values")
                                    .toString()
                                viewModel.loadSessionState(result)
                                allowedValues = listOf(1, 2, 3, 4, 5, 6)
                            } catch (e: Exception) {
                                error = "Erreur : ${e.message}"
                            } finally {
                                mutex.withLock { isLoading = false }
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Réinitialiser")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Dé du destin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        "coeur" to "❤️ Cœur",
                        "question" to "❓ Question",
                        "soleil" to "☀️ Soleil",
                        "etoile" to "⭐ Étoile",
                        "exclamation" to "❗ Exclamation",
                        "spirale" to "🌀 Spirale"
                    ).forEach { (key, label) ->
                        val isChecked = key in allowedFates

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.toggleable(
                                value = isChecked,
                                onValueChange = { checked ->
                                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                                        mutex.withLock {
                                            isLoading = true
                                            error = null
                                        }
                                        try {
                                            val result = python.getModule("game_api")
                                                .callAttr("call_json", "do_toggle_allowed_fate", key)
                                                .toString()
                                            viewModel.loadSessionState(result)
                                            allowedFates = sessionState?.optJSONArray("allowed_fate_keys")
                                                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                                                ?: emptyList()
                                        } catch (e: Exception) {
                                            error = "Erreur : ${e.message}"
                                        } finally {
                                            mutex.withLock { isLoading = false }
                                        }
                                    }
                                },
                                role = Role.Checkbox
                            )
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null
                            )
                            Text(text = label)
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            mutex.withLock {
                                isLoading = true
                                error = null
                            }
                            try {
                                val result = python.getModule("game_api")
                                    .callAttr("call_json", "do_reset_allowed_fate")
                                    .toString()
                                viewModel.loadSessionState(result)
                                allowedFates = listOf("coeur", "question", "soleil", "etoile", "exclamation", "spirale")
                            } catch (e: Exception) {
                                error = "Erreur : ${e.message}"
                            } finally {
                                mutex.withLock { isLoading = false }
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Réinitialiser")
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Fermer")
                    }
                }
            }
        }
    }
}

@Composable
private fun Checkbox(
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(
                enabled = onCheckedChange != null,
                role = Role.Checkbox,
                onClick = { onCheckedChange?.invoke() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}