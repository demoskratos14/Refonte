package com.aventure.desdice.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aventure.desdice.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class GameViewModel : ViewModel() {
    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories = _stories.asStateFlow()

    private val _currentStorySlug = MutableStateFlow<String?>(null)
    val currentStorySlug = _currentStorySlug.asStateFlow()

    // Etat de la session de jeu en cours (dict Python session_to_dict(),
    // recu via call_json sous forme de JSON). Utilise comme etat Compose
    // "brut" (JSONObject) plutot que via des champs Kotlin dedies, pour
    // rester en phase avec les cles ajoutees/retirees cote Python sans
    // devoir modifier ce ViewModel a chaque fois.
    var sessionState by mutableStateOf<JSONObject?>(null)
        private set

    // Les 6 faces du de du destin (cle/emoji/label/desc), chargees une
    // seule fois au demarrage via get_fate_faces() (donnees statiques,
    // independantes de la session en cours).
    var fateFaces by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    private val mutex = Mutex()

    init {
        loadStories()
        loadFateFaces()
    }

    /** Remplace l'etat de session courant par le JSON recu d'un appel
     * game_api (do_roll, do_send_ai_message, select_story, etc.). */
    fun loadSessionState(result: String) {
        sessionState = JSONObject(result)
    }

    private fun loadFateFaces() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = callGameApi("get_fate_faces")
            val array = JSONArray(result)
            val faces = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                faces.add(array.getJSONObject(i))
            }
            fateFaces = faces
        }
    }

    fun loadStories() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val result = callGameApi("list_stories")
                val storiesJson = JSONObject(result)
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
            }
        }
    }

    fun selectStory(slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val result = callGameApi("select_story", slug)
                loadSessionState(result)
                _currentStorySlug.value = slug
            }
        }
    }

    fun deleteStory(slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                callGameApi("do_delete_story", slug)
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
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                callGameApi(
                    "do_create_story",
                    title,
                    subtitle,
                    loreText,
                    totemLabel,
                    totemPowers,
                    totemSpecial,
                    bgImageBytes,
                    bgImageExt,
                    totemImageBytes,
                    totemImageFilename
                )
            }
            loadStories()
        }
    }

    private fun callGameApi(funcName: String, vararg args: Any): String {
        return com.chaquo.python.Python.getInstance()
            .getModule("game_api")
            .callAttr("call_json", funcName, *args)
            .toString()
    }
}
