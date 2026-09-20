package com.aventure.desdice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.screens.ConfigureKeyScreen
import com.aventure.desdice.screens.CreateStoryScreen
import com.aventure.desdice.screens.StorySelectorScreen
import com.aventure.desdice.viewmodel.GameViewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // Doit etre appele avant tout autre acces a game_api (voir
        // init_app_dir() dans game_api.py : fixe le repertoire de travail
        // Python sur le stockage prive de l'app, pour que les chemins
        // relatifs -- app_config.json, totem_images/, sauvegarde de
        // session -- pointent au bon endroit).
        Python.getInstance().getModule("game_api").callAttr("init_app_dir")

        speechManager = SpeechManager(this)

        setContent {
            DesDiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: GameViewModel = viewModel()
                    AppNavigation(viewModel = viewModel, speechManager = speechManager)
                }
            }
        }
    }
}

/**
 * Navigation en 3 etapes, calquee sur le parcours de dice_web.py :
 * 1) ConfigureKeyScreen (cle API Mistral, optionnelle -- "Passer pour
 *    l'instant" ou "Continuer" appellent tous deux onDone) ;
 * 2) StorySelectorScreen tant qu'aucune histoire n'est selectionnee
 *    (currentStorySlug == null), avec bascule vers CreateStoryScreen
 *    quand on appuie sur "Nouvelle histoire" ;
 * 3) MainGameScreen des qu'une histoire est choisie.
 *
 * A completer plus tard : bouton "changer d'histoire" depuis
 * MainGameScreen (repasser currentStorySlug a null cote ViewModel ou
 * ajouter un etat de nav dedie), et integration d'AiPanel (non appele
 * depuis MainGameScreen pour l'instant -- a brancher comme onglet,
 * section ou bottom sheet).
 */
@Composable
fun AppNavigation(viewModel: GameViewModel, speechManager: SpeechManager) {
    var keyStepDone by remember { mutableStateOf(false) }
    var showCreateStory by remember { mutableStateOf(false) }
    val currentStorySlug by viewModel.currentStorySlug.collectAsState()

    when {
        !keyStepDone -> {
            ConfigureKeyScreen(onDone = { keyStepDone = true })
        }
        showCreateStory -> {
            CreateStoryScreen(
                viewModel = viewModel,
                onCreated = { showCreateStory = false }
            )
        }
        currentStorySlug == null -> {
            StorySelectorScreen(
                viewModel = viewModel,
                onStorySelected = { /* currentStorySlug est deja mis a jour par selectStory() */ },
                onNewStoryClick = { showCreateStory = true }
            )
        }
        else -> {
            MainGameScreen(viewModel = viewModel)
        }
    }
}

@Composable
fun DesDiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
