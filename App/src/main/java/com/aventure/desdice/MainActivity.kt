package com.aventure.desdice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.screens.ClassicDiceScreen
import com.aventure.desdice.screens.ConfigureKeyScreen
import com.aventure.desdice.screens.CreateStoryScreen
import com.aventure.desdice.screens.StorySelectorScreen
import com.aventure.desdice.viewmodel.GameViewModel

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Plus de demarrage Python/init_app_dir ici : GameEngine (construit
        // par GameViewModel) recoit directement context.filesDir, comme le
        // README l'indique ("il n'y a plus de repertoire de travail a
        // fixer au demarrage").
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
 * Navigation :
 * 1) ConfigureKeyScreen au premier ecran (cle API Mistral, optionnelle --
 *    "Passer" ou "Continuer" appellent tous deux onDone) ;
 * 2) StorySelectorScreen (carrousel) tant qu'aucune histoire n'est
 *    selectionnee (currentStorySlug == null). Depuis ce carrousel :
 *      - l'icone de de ouvre ClassicDiceScreen,
 *      - la cle rouvre ConfigureKeyScreen,
 *      - la derniere page ouvre CreateStoryScreen ;
 *    le bouton retour du telephone ferme chacun de ces ecrans secondaires ;
 * 3) MainGameScreen des qu'une histoire est choisie. Son lien "Changer
 *    d'histoire" (ou le bouton retour du telephone) rouvre le carrousel
 *    SANS modifier l'histoire active : retour = on revient a la partie,
 *    comme dans l'ancienne version (dice_web.py, change_story).
 *
 * A completer plus tard : bouton "changer d'histoire" depuis
 * MainGameScreen (repasser currentStorySlug a null cote ViewModel ou
 * ajouter un etat de nav dedie). AiPanel est deja integre a
 * MainGameScreen (voir AiPanel.kt) -- rien a brancher ici de ce cote.
 */
@Composable
fun AppNavigation(viewModel: GameViewModel, speechManager: SpeechManager) {
    var keyStepDone by remember { mutableStateOf(false) }
    var showConfigureKey by remember { mutableStateOf(false) }
    var showClassicDice by remember { mutableStateOf(false) }
    var showCreateStory by remember { mutableStateOf(false) }
    var showStorySelector by remember { mutableStateOf(false) }
    val currentStorySlug by viewModel.currentStorySlug.collectAsState()

    // Des que l'histoire active change (nouveau choix, creation, suppression),
    // le carrousel ouvert a la demande n'a plus lieu d'etre.
    LaunchedEffect(currentStorySlug) { showStorySelector = false }

    when {
        !keyStepDone -> {
            ConfigureKeyScreen(onDone = { keyStepDone = true })
        }
        showConfigureKey -> {
            BackHandler { showConfigureKey = false }
            ConfigureKeyScreen(onDone = { showConfigureKey = false })
        }
        showClassicDice -> {
            BackHandler { showClassicDice = false }
            ClassicDiceScreen()
        }
        showCreateStory -> {
            BackHandler { showCreateStory = false }
            CreateStoryScreen(
                viewModel = viewModel,
                onCreated = { showCreateStory = false }
            )
        }
        currentStorySlug == null || showStorySelector -> {
            if (currentStorySlug != null) {
                // Carrousel ouvert depuis la partie : retour = annuler.
                BackHandler { showStorySelector = false }
            }
            StorySelectorScreen(
                viewModel = viewModel,
                onStorySelected = { slug ->
                    // Autre histoire : le LaunchedEffect ci-dessus ferme le
                    // carrousel quand currentStorySlug change. Meme histoire :
                    // rien ne change, on referme donc a la main.
                    if (slug == currentStorySlug) showStorySelector = false
                },
                onNewStoryClick = { showCreateStory = true },
                onClassicDiceClick = { showClassicDice = true },
                onConfigureKeyClick = { showConfigureKey = true }
            )
        }
        else -> {
            // Comme l'ancien piege du bouton retour : retour = choix d'histoire.
            BackHandler { showStorySelector = true }
            MainGameScreen(
                viewModel = viewModel,
                onChangeStory = { showStorySelector = true }
            )
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
