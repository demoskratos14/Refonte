# Histoires multiples (Dés d'Aventure) — application Android

Application Android de narration interactive à base de dés : le joueur lance
un **dé de réussite** (1 à 6) et un **dé du destin**, fait monter des jauges
(totems, menace), débloque des quêtes secondaires, et une **IA narratrice**
(API Mistral, optionnelle) écrit la suite de l'histoire à chaque lancer.
L'application démarre vierge (aucune histoire codée en dur) : on crée ses
propres histoires depuis l'appli, ou on réimporte un fichier d'identité
précédemment exporté.

L'appli est écrite entièrement en **Kotlin / Jetpack Compose**, y compris le
moteur de jeu. Un écran **Réglages** permet de personnaliser les polices, les
images de fond des pages et l'icône de l'application.

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
6. **Écran Réglages** : choix des polices (texte et titres), des images de
   fond des pages et de l'icône de l'application, sans passer par le moteur
   de jeu (voir la section « Réglages » plus bas).

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
- **Les réglages d'apparence** (polices, fonds, icône) ne passent pas par
  `GameEngine` : ils sont gérés par trois petits objets du package `ui`
  (`FontPrefs`, `BackgroundStore`, `AppIconStore`), voir plus bas.

## Navigation (MainActivity.kt → `AppNavigation`)

| Ordre | Écran | Rôle |
|---|---|---|
| 1 | `ConfigureKeyScreen` | Premier écran : saisie de la clé API Mistral (optionnelle, « Passer » possible) et choix du modèle. Un **engrenage** (en haut à droite) ouvre les Réglages |
| 2 | `StorySelectorScreen` | Carrousel des histoires + page « Nouvelle histoire » ; icône dé (en haut à gauche) → « Des classiques » ; icône 🔑 → configuration de la clé ; corbeille sur les histoires personnalisées |
| 3 | `MainGameScreen` | La partie en cours (voir ci-dessous) |
| — | `SettingsScreen` | Réglages, en 3 pages qu'on fait glisser : Polices, Photos, Icône |
| — | `CreateStoryScreen` | Création d'une histoire personnalisée (voir ci-dessous) |
| — | `ClassicDiceScreen` | Page « Des classiques » : dé de réussite + dé du destin, indépendants de toute histoire |

Le bouton retour du téléphone ferme les écrans secondaires ; depuis la
partie, il rouvre le carrousel sans changer l'histoire active.

`SettingsScreen` est testé en premier dans le `when` d'`AppNavigation` : on
peut donc l'ouvrir depuis la page de la clé API dès le tout premier écran,
ou après l'avoir rouverte depuis le carrousel. Retour = on revient à la page
de la clé.

### L'écran de jeu, de haut en bas

1. En-tête (titre de l'histoire, lien « Changer d'histoire ») et badges des totems (touche = fiche du totem).
2. `AiPanel` : conversation avec l'IA (fil complet, message libre, lecture vocale du dernier message, réinitialisation avec confirmation) ; masqué (message + bouton vers la configuration) tant qu'aucune clé Mistral n'est enregistrée. Remonté juste sous l'en-tête (au-dessus des dés) pour rester bien visible ; dès qu'une nouvelle réponse de l'IA arrive, l'écran défile automatiquement pour l'amener en haut (position mesurée via `onGloballyPositioned`, dans `MainGameScreen.kt`).
3. `DiceResultCard` : lancers de dés (dés 3D animés), résultat, note pour l'IA.
4. `ThreatGauge` : jauge de menace.
5. `SymbolPicker` : symbole affiché sur le dé de réussite (mode unique / aléatoire / mixte).
6. Bouton « Configurer les valeurs autorisées » (`AllowedValuesDialog`) : quelles faces du dé de réussite et du dé du destin comptent.
7. `TotemGaugesRow` : une jauge par totem, utilisable quand elle est pleine ; gestion des totems ajoutés en cours de partie (`TotemManagementDialog`).
8. `SideQuestsList` : quêtes secondaires.
9. `ContinueSection` : démarrer ou relancer un chapitre, ou copier le prompt complet (mode manuel).
10. `HistoryList` : historique des lancers (annuler le dernier, tout effacer).
11. `JournalSection` : journal de l'histoire (masqué quand la narration automatique est active).

Le fond de l'écran de jeu est l'image de l'histoire en cours (elle se choisit
à la création de l'histoire) ; il n'est pas modifiable depuis les Réglages.

### Création d'une histoire (`CreateStoryScreen`)

Champs : titre, sous-titre, texte de lore, prénom du héros (optionnel — si
vide, l'IA s'adresse à « le personnage principal »), totem de départ
(libellé/pouvoirs/spécial + image), image de fond.

**Longueur de l'histoire** (`storyLength`, propagé jusqu'à
`StoryEntry.storyLength` et transmis à l'IA via
`GameEngine.buildStoryLengthInstructions()`) :

| Valeur | Libellé | Effet |
|---|---|---|
| `short` | Courte (~10 échanges) | Histoire complète qui se conclut naturellement autour du 10ᵉ échange ; un rappel de progression à jour (`buildStoryProgressNote`) est renvoyé à l'IA à chaque appel |
| `medium` | Moyenne (20 à 30) | Idem, mais viser 20 à 30 échanges. **Valeur par défaut à la création** d'une nouvelle histoire |
| `long` | Longue (chapitres) | Pas de limite totale ; l'IA découpe en chapitres (chacun avec une vraie fin) et demande confirmation avant d'enchaîner sur le suivant. Valeur de repli côté moteur pour les histoires déjà existantes (avant l'ajout de ce réglage) |

Une identité importée restaure sa propre longueur d'histoire
(`story_length` dans le JSON exporté) ; si absente, elle retombe sur `long`.

**Objectif moral** (`moralGoal`, optionnel — propagé jusqu'à
`StoryEntry.moralGoal`) : une valeur ou une notion que l'aventure doit
mettre en avant à travers ses situations (patience, fair-play, partage,
courage face à la peur, entraide...), en plus de l'univers décrit dans le
texte de lore — jamais à sa place. Transmis à l'IA dans
`GameEngine.buildMechanicsContext()` par une instruction dédiée
(« OBJECTIF MORAL DU RÉCIT »), juste après les paragraphes de lore, avec la
consigne explicite de le faire vivre par le récit (situations, personnages,
choix) et de ne jamais le tourner en discours moralisateur direct. Laissé
vide, aucune instruction n'est envoyée : comportement inchangé pour toute
histoire qui n'en définit pas. Restauré lui aussi par un import d'identité
(`moral_goal` dans le JSON exporté).

Le bloc « importer une identité exportée (JSON) » (collage manuel ou choix
d'un fichier) pré-remplit tous les champs ci-dessus, y compris les totems
supplémentaires, quêtes secondaires et journal, appliqués à part juste après
la création (`addCustomTotemAwait` puis `applyImportedProgress`).

## Réglages (`screens/SettingsScreen.kt`)

Accessible par l'engrenage de `ConfigureKeyScreen` (composable
`SettingsGearButton`). L'écran se compose d'un en-tête fixe (flèche de retour
+ titre) et de **3 pages** qu'on change en glissant, ou en touchant l'onglet
vertical collé au bord de l'écran (« Photos › », « ‹ Polices », « Icône › »…).
Le fond de la page Réglages est lui-même personnalisable.

### Page 1 — Polices

Deux choix **indépendants**, parmi les fichiers `.ttf` / `.otf` présents dans
`app/src/main/assets/fonts/` (chaque police est affichée dans son propre
style, avec un aperçu) :

| Choix | Effet dans `AppFonts` | Où on le voit |
|---|---|---|
| Police du texte de l'application | `body` (et `bodyBold`, voir ci-dessous) | descriptions, narration, notes, intitulés en gras |
| Police des titres | `display` | titres des histoires, en-têtes, titres de sections, boutons, légendes des dés |

- Par défaut : `Nunito-Regular.ttf` pour le texte et `Bangers-Regular.ttf` pour
  les titres. « Rétablir la police par défaut » efface le choix.
- Les variantes de style (fichiers se terminant par Bold, Italic, Light, Thin,
  Medium, Black) ne sont pas proposées comme police à part entière. La
  variante grasse du texte est retrouvée automatiquement : si on choisit
  `Foo-Regular.ttf`, `bodyBold` cherche `Foo-Bold.ttf` dans le même dossier,
  sinon il retombe sur la police normale.
- Le choix est mémorisé dans les `SharedPreferences` (`app_font_prefs`) par
  `ui/FontPrefs.kt`. Comme `FontPrefs` expose des états Compose et que
  `rememberAppFonts()` les lit, **tous les écrans changent de police
  immédiatement**, sans redémarrage.
- Si le fichier choisi est illisible ou a disparu, `rememberAppFonts()`
  retombe sur l'ordre de recherche d'origine (`assets/fonts`, puis `res/font`,
  puis police du système).

Pour **ajouter une police** : déposer le fichier dans `assets/fonts/`, elle
apparaît d'elle-même dans les listes.

### Page 2 — Photos (images de fond)

Cinq pages de l'appli ont un fond personnalisable (`ui/BackgroundStore.kt`,
énumération `BackgroundSlot`) :

| Emplacement | Image par défaut | Écran concerné |
|---|---|---|
| Page de la clé API | `raw/bg_key_page.jpg` | `ConfigureKeyScreen` |
| Page Réglages | `raw/bg_settings.jpg` | `SettingsScreen` |
| Nouvelle histoire (carrousel) | `drawable/new_story_bg` | `StorySelectorScreen` (dernière page) |
| Création d'histoire | `drawable/bg_create_story.jpg` | `CreateStoryScreen` |
| Dés classiques | `drawable/bg_classic_dice.jpg` | `ClassicDiceScreen` |

- Chaque ligne montre une miniature, un bouton « Choisir une image »
  (sélecteur de la galerie, sans permission) et, si une image personnalisée
  est en place, « Image par défaut ».
- L'image choisie passe par `ImageUtils.resizeBgBytes()` (réduite à 1600 px,
  complétée par un fond flouté si elle est en paysage), puis est copiée dans
  `filesDir/backgrounds/<emplacement>.jpg`.
- Les écrans obtiennent leur fond par
  `rememberBackgroundPainter(BackgroundSlot.XXX)` : le décodage se fait hors
  du thread principal, et un changement dans les Réglages se voit tout de
  suite sur la page concernée.
- Les images par défaut doivent **rester** dans `res/` : elles servent de
  repli.
- Les mises en page de `ConfigureKeyScreen` (espace laissé en haut pour le
  livre) et de `CreateStoryScreen` (image calée en haut, décalée pour garder
  la lune visible) ont été réglées pour les images d'origine ; avec une autre
  image, le sujet principal peut ne pas tomber au même endroit.

Pour **rendre le fond d'une autre page personnalisable** : ajouter une entrée
à `BackgroundSlot` (clé, libellé, image par défaut), puis remplacer le
`painterResource(...)` du fond de cette page par
`rememberBackgroundPainter(BackgroundSlot.NOUVELLE_ENTREE)`. La page Photos
affiche automatiquement toutes les entrées.

### Page 3 — Icône de l'application

Grille de 3 × 3 : l'icône d'origine (étiquette « Origine ») + 8 icônes
alternatives. Un clic sur une icône l'applique ; l'icône en cours est
entourée de blanc avec une pastille « ✓ ».

**Comment ça marche** : Android ne permet pas de remplacer l'icône d'une
appli par une image arbitraire une fois installée. On déclare donc dans le
manifeste **un `<activity-alias>` par icône** (tous pointant sur
`MainActivity`, chacun avec son propre `android:icon`) ; un seul est activé à
la fois et c'est lui que le lanceur affiche. `ui/AppIconStore.kt`
(`AppIcon`, `AppIconStore.current()` / `apply()`) active l'alias choisi puis
désactive les autres avec `PackageManager.setComponentEnabledSetting(…,
DONT_KILL_APP)` : l'appli ne redémarre pas.

- L'état des alias est conservé par Android (pas de fichier à nous).
- Selon le lanceur du téléphone, la nouvelle icône peut mettre quelques
  secondes à apparaître sur l'écran d'accueil.
- Si un alias manque dans le manifeste, le clic affiche un message d'erreur
  au lieu de planter.

Pour **ajouter une icône** : ajouter les ressources `ic_altN` et
`icon_preview_N` (voir plus bas), un `<activity-alias android:name=".IconN"
android:enabled="false" …>` dans le manifeste, et une entrée
`ICON_N("IconN", R.drawable.icon_preview_N)` dans l'énumération `AppIcon`.

## Fichiers stockés sur l'appareil

| Fichier / dossier | Contenu |
|---|---|
| `app_config.json` | clé Mistral et modèle choisi (commun à toutes les histoires) |
| `dice_state_<slug>.json` | la partie de chaque histoire |
| `custom_stories.json`, `custom_story_bg/` | histoires personnalisées et leurs images de fond |
| `totem_images/` | images des totems ajoutés |
| `classic_dice_state.json` | historique de la page « Des classiques » |
| `backgrounds/<emplacement>.jpg` | fonds de pages personnalisés (Réglages > Photos) |
| `SharedPreferences` `app_font_prefs` | polices choisies (texte et titres) |

Désinstaller l'appli efface tout cela. L'icône active est mémorisée par
Android lui-même (état des alias).

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
        ├── AndroidManifest.xml       # permission INTERNET + alias d'icônes (voir plus bas)
        ├── assets/fonts/             # polices proposées dans Réglages (Bangers, Nunito, …)
        ├── res/                      # icônes, images de fond (voir « Ressources »)
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
        │   │   ├── ConfigureKeyScreen.kt     # saisie de la clé API Mistral (+ engrenage vers Réglages)
        │   │   ├── CreateStoryScreen.kt      # création d'une histoire personnalisée
        │   │   ├── SettingsScreen.kt         # Réglages : polices, fonds, icône (+ SettingsGearButton)
        │   │   └── StorySelectorScreen.kt    # carrousel des histoires
        │   └── ui/
        │       ├── AppFonts.kt        # chargement des polices (rememberAppFonts)
        │       ├── FontPrefs.kt       # polices choisies + liste des polices de assets/fonts
        │       ├── BackgroundStore.kt # fonds personnalisés (BackgroundSlot, rememberBackgroundPainter)
        │       └── AppIconStore.kt    # icône de l'appli (AppIcon, activation des alias)
```

### Ressources attendues dans `res/`

- **Fonds par défaut** : `drawable/bg_classic_dice.jpg` (« Des classiques »), `drawable/bg_create_story.jpg` (création d'histoire), `drawable/new_story_bg` (dernière page du carrousel), `raw/bg_key_page.jpg` (clé API), `raw/bg_settings.jpg` (Réglages).
- **Icônes d'interface** : `drawable/classic_dice_icon` (icône du carrousel), `drawable/ic_settings.png` (engrenage).
- **Polices** : Bangers et Nunito (dont `Nunito-Bold.ttf`) dans `assets/fonts/`, plus toute police à proposer dans Réglages.
- **Icône d'origine** :
  - `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` et `ic_launcher_round.png` (Android avant la version 8) ;
  - `mipmap-anydpi-v26/ic_launcher.xml` et `ic_launcher_round.xml` (icône adaptative, Android 8 et plus), qui utilisent `drawable-nodpi/ic_original_bg.jpg` et `ic_original_fg.png`.
- **Icônes alternatives 1 à 8** :
  - `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_altN.png` (Android avant la version 8) ;
  - `mipmap-anydpi-v26/ic_altN.xml` (icône adaptative) qui utilise `drawable-nodpi/ic_altN_bg.jpg` et `ic_altN_fg.png` ;
  - `drawable-nodpi/icon_preview_N.png` : miniature affichée dans Réglages.
- `values/strings.xml` (`app_name`).

### Le manifeste (`AndroidManifest.xml`)

- `MainActivity` **n'a plus** de filtre `MAIN` / `LAUNCHER` (sinon l'appli
  apparaîtrait en double dans le lanceur) ; elle reste `exported="true"`.
- Neuf `<activity-alias>` portent ce filtre : `.IconOriginal` (activé par
  défaut, `@mipmap/ic_launcher` + `@mipmap/ic_launcher_round`) et `.Icon1` à
  `.Icon8` (désactivés par défaut, `@mipmap/ic_alt1` à `ic_alt8`).
- Les noms d'alias doivent rester alignés avec l'énumération `AppIcon`
  (`"IconOriginal"`, `"Icon1"`… dans `AppIconStore.kt`) : le code retrouve les
  alias par `com.aventure.desdice.<nom>`.

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

- `namespace` : `com.aventure.desdice` (nom des packages, et des alias d'icônes du manifeste).
- `applicationId` : `com.aventure.desdice.histoiresmultiples`. C'est cet identifiant qu'Android utilise pour décider si une appli est « la même » (mise à jour) ou une autre (installée à côté). Il diffère de celui de l'ancienne version WebView (`com.aventure.desdice.auto`) : les deux peuvent cohabiter, mais leurs sauvegardes ne se transmettent pas.

## En cas d'échec du build

1. Ouvrir l'étape en erreur dans l'onglet Actions et lire le message exact.
2. Si Kotlin passe un jour à **2.0 ou plus**, le compilateur Compose se configure autrement : ajouter le plugin `org.jetbrains.kotlin.plugin.compose` et **supprimer** le bloc `composeOptions` de `app/build.gradle`.
3. Ne pas retirer `androidx.appcompat` : le thème du manifeste (`Theme.AppCompat.Light.NoActionBar`) en dépend.
4. `Unresolved reference: toBitmap` dans `SettingsScreen.kt` : ajouter la dépendance `androidx.core:core-ktx` (elle sert à dessiner la miniature de l'icône d'origine).
5. `Unresolved reference: R.drawable.icon_preview_N`, `R.mipmap.ic_altN` ou `R.raw.bg_settings` : une ressource d'icône ou de fond manque ou est mal nommée (noms en minuscules, sans espace ni tiret).
6. Un clic sur une icône affiche « les alias du manifeste ne sont pas en place » : le `AndroidManifest.xml` n'est pas celui qui contient les `<activity-alias>`.

## Ouvrir le projet dans Android Studio (optionnel)

Ouvrir le dossier avec *Open*. Comme le projet n'a pas de wrapper Gradle,
Android Studio utilisera le Gradle installé, ou proposera d'en générer un
(`gradle wrapper --gradle-version 8.7`).
