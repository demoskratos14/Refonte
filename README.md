# Histoires multiples (Dés d'Aventure) — application Android

Application Android de narration interactive à base de dés : le joueur lance
un **dé de réussite** (1 à 6) et un **dé du destin**, fait monter des jauges
(totems, menace), débloque des quêtes secondaires, et une **IA narratrice**
(API Mistral, optionnelle) écrit la suite de l'histoire à chaque lancer.
L'application démarre vierge (aucune histoire codée en dur) : on crée ses
propres histoires depuis l'appli, ou on réimporte un fichier d'identité
précédemment exporté.

L'appli est écrite entièrement en **Kotlin / Jetpack Compose**, y compris le
moteur de jeu.

## Historique : pourquoi cette architecture

1. Au départ, un script Python lancé dans Pydroid 3 qui ouvrait un
   navigateur externe (`127.0.0.1:5001`).
2. Puis une première appli Android qui démarrait un serveur Flask interne et
   l'affichait dans une `WebView` (`dice_web.py`, `android_bridge.py`).
3. Refonte complète (2026) : plus de serveur, plus de navigateur, plus de
   HTML : chaque écran est un composable Compose. `flask`, `dice_web.py` et
   `android_bridge.py` n'existent plus. Le moteur de jeu restait en Python
   (`game_api.py`), appelé depuis Kotlin via Chaquopy.
4. **Portage complet du moteur de Python vers Kotlin.** `dice_engine.py`,
   `stories.py`, `mistral_client.py`, `image_utils.py` et `journal_export.py`
   ont été réécrits en Kotlin pur (`DiceSession.kt`, `StoryRegistry.kt`,
   `GameEngine.kt`, `ImageUtils.kt`, `MistralClient.kt`,
   `JournalExporter.kt`).
5. **Migration terminée** : tous les écrans (`MainGameScreen.kt` et les
   composables qu'il appelle, `ClassicDiceScreen.kt`, `ConfigureKeyScreen.kt`,
   `CreateStoryScreen.kt`, `StorySelectorScreen.kt`) passent désormais
   uniquement par `GameViewModel`/`GameEngine`. Le dossier `python/`, Chaquopy
   et les dépendances `pillow`/`fpdf2` ont été retirés du projet.

## Comment Kotlin et le moteur de jeu communiquent

```
 Compose (écrans)  ──►  GameViewModel  ──►  GameEngine (Kotlin pur)
        ▲                    │                        │
        │                    │                        ▼
        └── sessionState ◄───┘             DiceSession / StoryRegistry / MistralClient
            (JSONObject)                              │
                                                       ▼
                                     fichiers JSON dans le stockage privé de l'appli
```

- **`GameEngine.kt` est la seule porte d'entrée** vers la logique de jeu :
  une méthode par action (`roll`, `setPipSymbol`, `useTotemEnergy`,
  `sendAiMessage`, `createStory`, `exportJournal`…), chacune renvoyant (ou
  mettant à jour) l'état de session complet sous forme de `JSONObject`
  (`sessionToJson()`, miroir de l'ancien `session_to_dict()` Python).
  `GameViewModel` expose une méthode par action, qui appelle `GameEngine`
  puis range le résultat dans `GameViewModel.sessionState` (un `StateFlow`) ;
  tous les écrans se redessinent à partir de lui.
- Les erreurs de l'IA ne font jamais perdre un lancer : elles reviennent
  dans le champ `ai_error` du JSON et s'affichent sous la narration.
- **Exception** : `exportJournal()` et `exportIdentity()` renvoient des
  octets (PDF/JSON, pas du JSON de session) via des fonctions `suspend`
  dédiées de `GameViewModel`, pour que l'écran obtienne le résultat
  directement plutôt que de l'observer dans `sessionState`.
- Il n'y a plus de répertoire de travail à fixer au démarrage : `GameEngine`
  reçoit directement `context.filesDir` à la construction.

## Navigation (MainActivity.kt → `AppNavigation`)

| Ordre | Écran | Rôle |
|---|---|---|
| 1 | `ConfigureKeyScreen` | Premier écran : saisie de la clé API Mistral (optionnelle, « Passer » possible) et choix du modèle |
| 2 | `StorySelectorScreen` | Carrousel des histoires + page « Nouvelle histoire » ; icône dé (en haut à gauche) → « Des classiques » ; icône 🔑 → configuration de la clé ; corbeille sur les histoires personnalisées |
| 3 | `MainGameScreen` | La partie en cours (voir ci-dessous) |
| — | `CreateStoryScreen` | Création d'une histoire personnalisée |
| — | `ClassicDiceScreen` | Page « Des classiques » : dé de réussite + dé du destin, indépendants de toute histoire |

Le bouton retour du téléphone ferme les écrans secondaires ; depuis la
partie, il rouvre le carrousel sans changer l'histoire active.

### L'écran de jeu, de haut en bas

1. En-tête (titre de l'histoire, lien « Changer d'histoire ») et badges des totems (touche = fiche du totem).
2. `DiceResultCard` : lancers de dés (dés 3D animés), résultat, note pour l'IA.
3. `ThreatGauge` : jauge de menace.
4. `SymbolPicker` : symbole affiché sur le dé de réussite (mode unique / aléatoire / mixte).
5. Bouton « Configurer les valeurs autorisées » (`AllowedValuesDialog`) : quelles faces du dé de réussite et du dé du destin comptent.
6. `AiPanel` : conversation avec l'IA (fil complet, message libre, lecture vocale du dernier message, réinitialisation avec confirmation) ; masqué (message + bouton vers la configuration) tant qu'aucune clé Mistral n'est enregistrée.
7. `TotemGaugesRow` : une jauge par totem, utilisable quand elle est pleine ; gestion des totems ajoutés en cours de partie (`TotemManagementDialog`).
8. `SideQuestsList` : quêtes secondaires.
9. `ContinueSection` : démarrer ou relancer un chapitre, ou copier le prompt complet (mode manuel).
10. `HistoryList` : historique des lancers (annuler le dernier, tout effacer).

## Fichiers stockés sur l'appareil

| Fichier / dossier | Contenu |
|---|---|
| `app_config.json` | clé Mistral et modèle choisi (commun à toutes les histoires) |
| `dice_state_<slug>.json` | la partie de chaque histoire |
| `custom_stories.json`, `custom_story_bg/` | histoires personnalisées et leurs images de fond |
| `totem_images/` | images des totems ajoutés |
| `classic_dice_state.json` | historique de la page « Des classiques » |

Désinstaller l'appli efface tout cela.

## Structure du projet

Les chemins Kotlin ci-dessous sont déduits des noms de packages.

```
DesDeAventure/
├── .github/workflows/build-apk.yml   # construit l'APK automatiquement
├── build.gradle                      # plugins Android / Kotlin (racine)
├── settings.gradle
├── gradle.properties
├── .gitignore
└── app/
    ├── build.gradle                  # config Android, Compose
    ├── proguard-rules.pro            # vide (minification désactivée)
    └── src/main/
        ├── AndroidManifest.xml       # permission INTERNET (API Mistral)
        ├── res/                      # icônes, polices, images de fond
        ├── java/com/aventure/desdice/
        │   ├── MainActivity.kt       # navigation (AppNavigation)
        │   ├── MainGameScreen.kt     # écran de jeu + briques d'UI communes (ComicButton, Section…)
        │   ├── AiPanel.kt            # panneau de narration IA (conversation, message libre, réinitialisation)
        │   ├── GameDice.kt           # face du dé de réussite, badges et fiches de totems
        │   ├── GameExtras.kt         # NarratorNoteBox, ContinueSection, JournalSection
        │   ├── DiceSession.kt        # moteur de jeu (dés, jauges, menace, quêtes, session)
        │   ├── StoryRegistry.kt      # registre des histoires personnalisées
        │   ├── GameEngine.kt         # porte d'entrée unique du moteur Kotlin
        │   ├── ImageUtils.kt         # redimensionnement des fonds et des totems
        │   ├── MistralClient.kt      # client API Mistral
        │   ├── JournalExporter.kt    # export PDF du journal (PdfDocument natif)
        │   ├── SpeechManager.kt      # synthèse vocale (lecture des réponses de l'IA)
        │   ├── model/Story.kt
        │   ├── viewmodel/GameViewModel.kt  # utilise GameEngine
        │   ├── screens/
        │   │   ├── TotemManagementDialog.kt  # gestion des totems ajoutés en cours de partie
        │   │   ├── ClassicDiceScreen.kt      # page "Des classiques"
        │   │   ├── ConfigureKeyScreen.kt     # saisie de la clé API Mistral
        │   │   ├── CreateStoryScreen.kt      # création d'une histoire personnalisée
        │   │   └── StorySelectorScreen.kt    # carrousel des histoires
        │   └── ui/AppFonts.kt        # chargement des polices (rememberAppFonts)
```

### Ressources attendues dans `res/`

- `drawable/bg_classic_dice.jpg` (fond de « Des classiques »), `drawable/bg_create_story.jpg` (création d'histoire), `drawable/classic_dice_icon` (icône du carrousel) ;
- `raw/bg_key_page.jpg` (fond de l'écran de clé API) ;
- les polices Bangers et Nunito ;
- `mipmap-*` (icône de l'appli, y compris la version adaptative) et `values/strings.xml` (`app_name`).

## Construire l'APK

Le projet est compilé automatiquement par GitHub Actions
(`.github/workflows/build-apk.yml`) à chaque push sur `main`, ou à la demande
(bouton **Run workflow** de l'onglet Actions).

1. Créer un dépôt GitHub vide, puis dans le dossier du projet :

   ```bash
   git init
   git add .
   git commit -m "Première version"
   git branch -M main
   git remote add origin https://github.com/<ton-compte>/<ton-depot>.git
   git push -u origin main
   ```

2. Onglet **Actions** → dernière exécution de « Build APK » → section **Artifacts** : télécharger **« Dés d'Aventure »** (un `.zip` contenant `Dés d'Aventure-debug.apk`).
3. Copier l'APK sur le téléphone et l'ouvrir ; Android demande d'autoriser l'installation d'applications inconnues pour l'appli qui ouvre le fichier.

Seul l'APK **debug** est produit (pas de signature de release configurée).

### Versions utilisées

| Élément | Version |
|---|---|
| Android Gradle Plugin | 8.2.2 |
| Kotlin | 1.9.24 |
| Compilateur Compose (`kotlinCompilerExtensionVersion`) | 1.5.14 |
| Compose BOM | 2024.06.00 |
| Gradle (dans le workflow) | 8.7 |
| JDK | 17 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| ABI | arm64-v8a, x86_64 |

Le workflow appelle `gradle` directement (pas de `gradlew`), donc aucun
`gradle-wrapper.jar` n'est nécessaire dans le dépôt.

### Identifiants

- `namespace` : `com.aventure.desdice` (nom des packages).
- `applicationId` : `com.aventure.desdice.histoiresmultiples`. C'est cet identifiant qu'Android utilise pour décider si une appli est « la même » (mise à jour) ou une autre (installée à côté). Il diffère de celui de l'ancienne version WebView (`com.aventure.desdice.auto`) : les deux peuvent cohabiter, mais leurs sauvegardes ne se transmettent pas.

## En cas d'échec du build

1. Ouvrir l'étape en erreur dans l'onglet Actions et lire le message exact.
2. Si Kotlin passe un jour à **2.0 ou plus**, le compilateur Compose se configure autrement : ajouter le plugin `org.jetbrains.kotlin.plugin.compose` et **supprimer** le bloc `composeOptions` de `app/build.gradle`.
3. Ne pas retirer `androidx.appcompat` : le thème du manifeste (`Theme.AppCompat.Light.NoActionBar`) en dépend.

## Ouvrir le projet dans Android Studio (optionnel)

Ouvrir le dossier avec *Open*. Comme le projet n'a pas de wrapper Gradle,
Android Studio utilisera le Gradle installé, ou proposera d'en générer un
(`gradle wrapper --gradle-version 8.7`).
