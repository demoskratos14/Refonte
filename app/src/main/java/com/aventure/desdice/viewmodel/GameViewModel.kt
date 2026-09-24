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

    /**
     * Variante suspendue de createStory() : CreateStoryScreen a besoin
     * d'attendre la création (et la publication de sessionState) avant de
     * naviguer vers l'écran de jeu, contrairement à la version
     * fire-and-forget ci-dessus. Ne prend plus bgImageExt (jamais utilisé
     * côté GameEngine, l'image de fond étant toujours réencodée en JPEG
     * par StoryRegistry) ni protagonistName en position fixe : ce dernier
     * est optionnel, comme côté GameEngine.createStory().
     */
    suspend fun createStoryAwait(
        title: String,
        subtitle: String,
        loreText: String,
        totemLabel: String,
        totemPowers: String,
        totemSpecial: String,
        bgImageBytes: ByteArray,
        totemImageBytes: ByteArray,
        totemImageFilename: String,
        protagonistName: String = "",
        storyLength: String = "long",
        moralGoal: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
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
                totemImageFilename = totemImageFilename,
                protagonistName = protagonistName,
                storyLength = storyLength,
                moralGoal = moralGoal
            ).also { loadSessionState(it) }
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

    /**
     * Variante suspendue de roll() : calcule le résultat mais ne le publie
     * PAS tout de suite dans sessionState -- contrairement à la version
     * fire-and-forget ci-dessus, l'appelant doit lui-même appeler
     * publishSessionState(result) une fois prêt. Sert à MainGameScreen.kt
     * (DiceResultCard) : le résultat est déjà tiré, mais l'animation du dé
     * doit jouer AVANT que le reste de l'écran (jauges, quêtes...) ne se
     * mette à jour, pour ne pas gâcher le suspense.
     */
    suspend fun rollAwaitingAnimation(action: String): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.roll(action) } }

    /** À appeler une fois l'animation terminée, pour publier le résultat
     * calculé par rollAwaitingAnimation(). */
    fun publishSessionState(result: JSONObject) {
        loadSessionState(result)
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

    /** Variante suspendue de useTotemEnergy() : publie sessionState comme la
     * version normale, mais renvoie aussi le JSONObject à l'appelant pour
     * qu'il puisse lire les champs "effect" / "ai_error" (voir
     * TotemGaugesRow dans MainGameScreen.kt). */
    suspend fun useTotemEnergyAwait(key: String): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.useTotemEnergy(key).also { loadSessionState(it) } } }

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

    /** Charge l'état courant de "Des classiques" et le renvoie directement à
     * l'appelant (en plus de le publier dans classicDiceState) -- utilisé au
     * chargement de ClassicDiceScreen.kt, qui a besoin de la valeur tout de
     * suite plutôt que d'attendre une recomposition sur le StateFlow. */
    suspend fun classicDiceStateAwait(): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.classicDiceState().also { _classicDiceState.value = it } } }

    /**
     * Variante suspendue de classicDiceRoll() : calcule le résultat mais ne
     * le publie PAS tout de suite dans classicDiceState -- l'appelant doit
     * lui-même appeler publishClassicDiceState(result) une fois prêt. Sert
     * à ClassicDiceScreen.kt : le résultat est déjà tiré, mais l'animation
     * du dé doit jouer AVANT que l'historique affiché ne se mette à jour,
     * pour ne pas gâcher le suspense (même logique que rollAwaitingAnimation()).
     */
    suspend fun classicDiceRollAwait(kind: String): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.classicDiceRoll(kind) } }

    /** À appeler une fois l'animation terminée, pour publier le résultat
     * calculé par classicDiceRollAwait(). */
    fun publishClassicDiceState(result: JSONObject) {
        _classicDiceState.value = result
    }

    /** Variante suspendue de classicDiceClear() : publie comme la version
     * normale, mais renvoie aussi le JSONObject à l'appelant. */
    suspend fun classicDiceClearAwait(): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.classicDiceClear().also { _classicDiceState.value = it } } }

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

    /** Variante suspendue de addCustomTotem() : utilisée par
     * CreateStoryScreen.kt pour poser, un par un et dans l'ordre, les
     * totems supplémentaires d'une identité importée juste après
     * createStoryAwait() -- doit attendre chaque ajout avant le suivant
     * (et avant applyImportedProgress()), d'où le besoin d'une variante
     * suspendue plutôt que la version fire-and-forget ci-dessous. */
    suspend fun addCustomTotemAwait(
        label: String,
        powers: String = "",
        special: String = "",
        emoji: String = "",
        imageBytes: ByteArray = ByteArray(0),
        imageFilename: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine.addCustomTotem(label, powers, special, emoji, imageBytes, imageFilename).also { loadSessionState(it) }
        }
    }

    fun removeCustomTotem(key: String) = runEngine { engine.removeCustomTotem(key) }
    fun completeSideQuest(questId: Int) = runEngine { engine.completeSideQuest(questId) }
    fun addStoryEntry(text: String) = runEngine { engine.addStoryEntry(text) }

    /** Variante suspendue de completeSideQuest(), utile pour l'enchaîner
     * après sendAiMessageAwait() (voir SideQuestsList.startQuest() dans
     * MainGameScreen.kt : la quête n'est retirée qu'une fois le message
     * envoyé sans erreur). */
    suspend fun completeSideQuestAwait(questId: Int): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.completeSideQuest(questId).also { loadSessionState(it) } } }

    // ------------------------------------------------------------------
    // Narration IA
    // ------------------------------------------------------------------

    fun sendFullPrompt() = runEngine { engine.sendFullPrompt() }

    /** Texte du prompt complet à copier (mode manuel, sans clé Mistral). Voir ContinueSection. */
    suspend fun buildFullPromptText(): String =
        withContext(Dispatchers.IO) { mutex.withLock { engine.buildFullPromptText() } }

    fun sendAiMessage(text: String = "") = runEngine { engine.sendAiMessage(text) }
    fun resetAiConversation() = runEngine { engine.resetAiConversation() }

    /** Variante suspendue de sendAiMessage() : publie sessionState comme la
     * version normale, mais renvoie aussi le JSONObject à l'appelant pour
     * qu'il puisse lire "ai_error" avant de décider la suite (voir
     * DiceResultCard et SideQuestsList dans MainGameScreen.kt). */
    suspend fun sendAiMessageAwait(text: String = ""): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock { engine.sendAiMessage(text).also { loadSessionState(it) } } }

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

    /**
     * Variantes suspendues de setMistralKey()/clearMistralKey()/setMistralModel() :
     * publient configScreenState comme les versions fire-and-forget ci-dessus,
     * mais permettent à l'appelant d'attendre la fin de l'opération avant de
     * réinitialiser son propre état local -- sert à ConfigureKeyScreen.kt, qui
     * doit vider le champ de saisie et replier le formulaire de changement de
     * clé seulement une fois la nouvelle clé effectivement enregistrée (même
     * logique que useTotemEnergyAwait()/classicDiceClearAwait() ailleurs).
     */
    suspend fun setMistralKeyAwait(key: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                engine.setMistralKey(key)
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    suspend fun clearMistralKeyAwait() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                engine.clearMistralKey()
                _configScreenState.value = engine.getConfigScreenState()
            }
        }
    }

    suspend fun setMistralModelAwait(model: String) {
        withContext(Dispatchers.IO) {
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
