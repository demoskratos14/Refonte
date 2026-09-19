package com.aventure.desdice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aventure.desdice.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class GameViewModel : ViewModel() {
    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories = _stories.asStateFlow()

    private val _currentStorySlug = MutableStateFlow<String?>(null)
    val currentStorySlug = _currentStorySlug.asStateFlow()

    private val mutex = Mutex()

    init {
        loadStories()
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
                callGameApi("select_story", slug)
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
