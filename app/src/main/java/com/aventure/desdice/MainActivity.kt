package com.aventure.desdice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aventure.desdice.screens.AudienceChoiceScreen
import com.aventure.desdice.screens.ClassicDiceScreen
import com.aventure.desdice.screens.ConfigureKeyScreen
import com.aventure.desdice.screens.CreateStoryScreen
import com.aventure.desdice.screens.SettingsScreen
import com.aventure.desdice.screens.StorySelectorScreen
import com.aventure.desdice.ui.AudiencePrefs
import com.aventure.desdice.ui.MusicHost
import com.aventure.desdice.viewmodel.GameViewModel

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Affichage bord a bord (obligatoire des targetSdk 35) : les ecrans dessinent sous les
        // barres systeme, qui sont transparentes avec des icones claires (fonds sombres).
        // Chaque ecran reserve lui-meme la place des barres (systemBarsPadding & co).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // GameEngine (construit par GameViewModel) recoit directement
        // context.filesDir : il n'y a pas de repertoire de travail a fixer
        // au demarrage.
        speechManager = SpeechManager(this)

        setContent {
            DesDiceTheme {
                Surface(
                    // imePadding : le clavier pousse le contenu au lieu de le recouvrir.
                    modifier = Modifier.fillMaxSize().imePadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: GameViewModel = viewModel()
                    AppNavigation(viewModel = viewModel, speechManager = speechManager)
                }
            }
        }
    }

    override fun onDestroy() {
        // Libère le moteur vocal quand l'appli est vraiment fermée.
        speechManager.shutdown()
        super.onDestroy()
    }
}

/**
 * Navigation :
 * 0) AudienceChoiceScreen tout premier ecran, une seule fois : choix du public
 *    vise (filles / garcons / les 2), qui determine les icones proposees dans
 *    Reglages et le fond par defaut de la page "Nouvelle histoire" du carrousel
 *    (voir AudiencePrefs.kt). Pas de bouton retour ; reste modifiable ensuite
 *    depuis Reglages > Profil ;
 * 1) ConfigureKeyScreen ensuite (cle API Mistral, optionnelle --
 *    "Passer" ou "Continuer" appellent tous deux onDone). Son engrenage
 *    ouvre SettingsScreen (choix des polices) ;
 * 2) StorySelectorScreen (carrousel) tant qu'aucune histoire n'est
 *    selectionnee (currentStorySlug == null). Depuis ce carrousel :
 *      - l'icone de de ouvre ClassicDiceScreen,
 *      - la cle rouvre ConfigureKeyScreen,
 *      - la derniere page ouvre CreateStoryScreen ;
 *    le bouton retour du telephone ferme chacun de ces ecrans secondaires ;
 * 3) MainGameScreen des qu'une histoire est choisie. Son lien "Changer
 *    d'histoire" (ou le bouton retour du telephone) rouvre le carrousel
 *    SANS modifier l'histoire active : retour = on revient a la partie.
 */
@Composable
fun AppNavigation(viewModel: GameViewModel, speechManager: SpeechManager) {
    // Musique de fond : démarre à l'ouverture de l'appli, continue pendant les histoires
    // (MainGameScreen propose de la couper, la relancer ou changer de morceau) et se met
    // en pause en arrière-plan (voir MusicPlayer.kt).
    MusicHost()

    val context = LocalContext.current
    val audiencePrefs = remember(context) { AudiencePrefs.get(context) }

    var keyStepDone by remember { mutableStateOf(false) }
    var showConfigureKey by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClassicDice by remember { mutableStateOf(false) }
    var showCreateStory by remember { mutableStateOf(false) }
    var showStorySelector by remember { mutableStateOf(false) }
    val currentStorySlug by viewModel.currentStorySlug.collectAsState()

    // Des que l'histoire active change (nouveau choix, creation, suppression),
    // le carrousel ouvert a la demande n'a plus lieu d'etre.
    LaunchedEffect(currentStorySlug) { showStorySelector = false }

    when {
        // Réglages (polices) : accessible par l'engrenage de ConfigureKeyScreen,
        // y compris au premier écran ; testé en premier pour passer par-dessus.
        showSettings -> {
            SettingsScreen(onBack = { showSettings = false })
        }
        !audiencePrefs.hasChosen -> {
            AudienceChoiceScreen(onChosen = { audiencePrefs.updateAudience(it) })
        }
        !keyStepDone -> {
            ConfigureKeyScreen(
                onDone = { keyStepDone = true },
                onOpenSettings = { showSettings = true }
            )
        }
        showConfigureKey -> {
            BackHandler { showConfigureKey = false }
            ConfigureKeyScreen(
                onDone = { showConfigureKey = false },
                onOpenSettings = { showSettings = true }
            )
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
            // Retour = choix d'histoire.
            BackHandler { showStorySelector = true }
            MainGameScreen(
                viewModel = viewModel,
                onChangeStory = { showStorySelector = true },
                // Sans ce paramètre, le bouton "Écouter" du panneau de narration reste masqué.
                speechManager = speechManager
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
