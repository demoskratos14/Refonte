package com.aventure.desdice.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aventure.desdice.FATE_FACES
import com.aventure.desdice.GameEngine
import com.aventure.desdice.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// NB : passe de ViewModel à AndroidViewModel — il faut le répertoire de
// l'appli (getApplication().filesDir) pour construire GameEngine, comme
// init_app_dir() le faisait côté Python avec context.getFilesDir(). Si le
// ViewModel est obtenu via viewModel() (Compose) sans factory explicite,
// la factory par défaut sait déjà instancier un AndroidViewModel ; sinon,
// utilise ViewModelProvider.AndroidViewModelFactory.getInstance(application).
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = GameEngine(getApplication<Application>().filesDir)

    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories = _stories.asStateFlow()

    // Distinct de "stories.isEmpty()" : depuis le retrait des histoires
    // codees en dur (Animorph/Poudlard), un premier lancement peut tout a
    // fait n'avoir AUCUNE histoire (ni integree, ni personnalisee) --
    // stories.isEmpty() serait alors vrai en permanence, meme une fois le
    // chargement termine, ce qui empechait StorySelectorScreen de
    // distinguer "en cours de chargement" de "chargee, mais vide". Ce
    // flag, lui, ne passe a true qu'une fois pour de bon.
    private val _storiesLoaded = MutableStateFlow(false)
    val storiesLoaded = _storiesLoaded.asStateFlow()

    private val _currentStorySlug = MutableStateFlow<String?>(null)
    val currentStorySlug = _currentStorySlug.asStateFlow()

    // Etat de la session de jeu en cours (JSONObject construit nativement
    // par GameEngine.sessionToJson(), miroir exact de l'ancien
    // session_to_dict() Python).
    private val _sessionState = MutableStateFlow<JSONObject?>(null)
    val sessionState = _sessionState.asStateFlow()

    // Les 6 faces du de du destin (cle/emoji/label/desc). Contrairement a
    // l'ancienne version (get_fate_faces() via Chaquopy), FATE_FACES est
    // directement accessible cote Kotlin (DiceSession.kt) -- plus besoin
    // d'appel asynchrone ni de StateFlow separe pour cette donnee statique.
    val fateFaces: List<com.aventure.desdice.FateFace> = FATE_FACES

    private val mutex = Mutex()

    init {
        loadStories()
    }

    /** Remplace l'etat de session courant par le JSON produit par GameEngine
     * (do_roll, do_send_ai_message, select_story, etc. -- cote Kotlin). */
    private fun loadSessionState(result: JSONObject) {
        _sessionState.value = result
    }

    fun loadStories() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val storiesJson = engine.listStories()
                val storiesArray = storiesJson.getJSONArray("stories")
                val storiesList = mutableListOf<Story>()

                for (i in 0 until storiesArray.length()) {
                    val storyJson = storiesArray.getJSONObject(i)
                    storiesList.add(
                        Story(
                            slug = storyJson.getString("slug"),
                            title = storyJson.getString("title"),
                            subtitle = storyJson.getString("subtitle"),
                            bgImageB64 = storyJson.getString("bg_image_b64"),
                            isCustom = storyJson.getBoolean("is_custom")
                        )
                    )
                }

                _stories.value = storiesList
                _currentStorySlug.value = storiesJson.getString("current_story").takeIf { it.isNotEmpty() }
                _storiesLoaded.value = true
            }
        }
    }

    fun selectStory(slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val result = engine.selectStory(slug)
                loadSessionState(result)
                _currentStorySlug.value = slug
            }
        }
    }

    fun deleteStory(slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                engine.deleteStory(slug)
            }
            loadStories()
        }
    }

    fun createStory(
        title: String,
        subtitle: String,
        loreText: String,
        totemLabel: String,
        totemPowers: String,
        totemSpecial: String,
        bgImageBytes: ByteArray,
        bgImageExt: String,
        totemImageBytes: ByteArray,
        totemImageFilename: String
    ) {
        // bgImageExt n'est plus utilise : l'image de fond est de toute
        // facon toujours reencodee en JPEG par StoryRegistry.createCustomStory()
        // (comme en Python, voir stories.py) -- conserve uniquement pour ne
        // pas casser les appelants existants (CreateStoryScreen.kt).
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                engine.createStory(
                    title = title,
                    subtitle = subtitle,
                    loreText = loreText,
                    totemLabel = totemLabel,
                    totemPowers = totemPowers,
                    totemSpecial = totemSpecial,
                    bgImageBytes = bgImageBytes,
                    totemImageBytes = totemImageBytes,
                    totemImageFilename = totemImageFilename
                )
            }
            loadStories()
        }
    }

    // ------------------------------------------------------------------
    // Lancers de des
    // ------------------------------------------------------------------

    fun roll(action: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                loadSessionState(engine.roll(action))
            }
        }
    }

    fun undo() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                loadSessionState(engine.undo())
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                loadSessionState(engine.clear())
            }
        }
    }

    // ------------------------------------------------------------------
    // Pip / symboles / contraintes de tirage
    // ------------------------------------------------------------------

    fun setPipSymbol(symbol: String) = runEngine { engine.setPipSymbol(symbol) }
    fun setPipMode(mode: String) = runEngine { engine.setPipMode(mode) }
    fun toggleEnabledSymbol(symbol: String) = runEngine { engine.toggleEnabledSymbol(symbol) }
    fun toggleAllowedValue(value: Int) = runEngine { engine.toggleAllowedValue(value) }
    fun resetAllowedValues() = runEngine { engine.resetAllowedValues() }
    fun toggleAllowedFate(key: String) = runEngine { engine.toggleAllowedFate(key) }
    fun resetAllowedFate() = runEngine { engine.resetAllowedFate() }

    /** Miroir de do_use_totem_energy (narration IA pas encore branchée — voir GameEngine.useTotemEnergy). */
    fun useTotemEnergy(key: String) = runEngine { engine.useTotemEnergy(key) }

    private fun runEngine(block: () -> JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                loadSessionState(block())
            }
        }
    }

    // ------------------------------------------------------------------
    // Des classiques
    // ------------------------------------------------------------------

    private val _classicDiceState = MutableStateFlow<JSONObject?>(null)
    val classicDiceState = _classicDiceState.asStateFlow()

    fun loadClassicDiceState() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _classicDiceState.value = engine.classicDiceState()
            }
        }
    }

    fun classicDiceRoll(kind: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _classicDiceState.value = engine.classicDiceRoll(kind)
            }
        }
    }

    fun classicDiceClear() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _classicDiceState.value = engine.classicDiceClear()
            }
        }
    }

    // ------------------------------------------------------------------
    // Totems ajoutés en cours de partie / quêtes / journal
    // ------------------------------------------------------------------

    fun addCustomTotem(
        label: String,
        powers: String = "",
        special: String = "",
        emoji: String = "",
        imageBytes: ByteArray = ByteArray(0),
        imageFilename: String = ""
    ) = runEngine { engine.addCustomTotem(label, powers, special, emoji, imageBytes, imageFilename) }

    fun removeCustomTotem(key: String) = runEngine { engine.removeCustomTotem(key) }
    fun completeSideQuest(questId: Int) = runEngine { engine.completeSideQuest(questId) }
    fun addStoryEntry(text: String) = runEngine { engine.addStoryEntry(text) }

    // ------------------------------------------------------------------
    // Narration IA
    // ------------------------------------------------------------------

    fun sendFullPrompt() = runEngine { engine.sendFullPrompt() }
    fun sendAiMessage(text: String = "") = runEngine { engine.sendAiMessage(text) }
    fun resetAiConversation() = runEngine { engine.resetAiConversation() }

    // ------------------------------------------------------------------
    // Configuration Mistral
    // ------------------------------------------------------------------

    private val _configScreenState = MutableStateFlow<JSONObject?>(null)
    val configScreenState = _configScreenState.asStateFlow()

    fun loadConfigScreenState() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    fun setMistralKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                engine.setMistralKey(key)
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    fun clearMistralKey() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                engine.clearMistralKey()
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    fun setMistralModel(model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                engine.setMistralModel(model)
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    // ------------------------------------------------------------------
    // Export / import (fonctions suspend : l'écran a besoin du résultat
    // directement -- fichier à écrire/partager, ou champs à pré-remplir --
    // plutôt que d'un état observé de loin comme sessionState).
    // ------------------------------------------------------------------

    /** @return (octets, nom de fichier, type MIME) du PDF (ou du texte de repli) du journal. */
    suspend fun exportJournal(): Triple<ByteArray, String, String> =
        withContext(Dispatchers.IO) { mutex.withLock { engine.exportJournal() } }

    /** @return (octets, nom de fichier, type MIME) du JSON d'identité de l'histoire en cours. */
    suspend fun exportIdentity(): Triple<ByteArray, String, String> =
        withContext(Dispatchers.IO) { mutex.withLock { engine.exportIdentity() } }

    /** Analyse (sans rien appliquer) un JSON d'identité collé par le joueur -- à pré-remplir dans CreateStoryScreen. */
    suspend fun importIdentity(rawText: String): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.importIdentity(rawText) } }

    /**
     * À appeler juste après createStory (et les éventuels addCustomTotem
     * pour les totems supplémentaires) quand l'identité importée contenait
     * des quêtes et/ou un journal.
     */
    suspend fun applyImportedProgress(
        sideQuests: JSONArray,
        nextQuestId: Int,
        storyLog: JSONArray,
        storySummary: String
    ): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = engine.applyImportedProgress(sideQuests, nextQuestId, storyLog, storySummary)
            loadSessionState(result)
            result
        }
    }
}
