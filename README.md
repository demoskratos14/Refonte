# Histoires multiples (Dés d'Aventure) — application Android

Application Android de narration interactive à base de dés : le joueur lance
un **dé de réussite** (1 à 6) et un **dé du destin**, fait monter des jauges
(totems, menace), débloque des quêtes secondaires, et une **IA narratrice**
(API Mistral, optionnelle) écrit la suite de l'histoire à chaque lancer.
L'application démarre vierge (aucune histoire codée en dur) : on crée ses
propres histoires depuis l'appli, ou on réimporte un fichier d'identité
précédemment exporté.

L'appli est écrite en **Kotlin / Jetpack Compose** pour l'interface, avec le
moteur de jeu en **Python** embarqué grâce à **Chaquopy**.

## Historique : pourquoi cette architecture

1. Au départ, un script Python lancé dans Pydroid 3 qui ouvrait un
   navigateur externe (`127.0.0.1:5001`).
2. Puis une première appli Android qui démarrait un serveur Flask interne et
   l'affichait dans une `WebView` (`dice_web.py`, `android_bridge.py`).
3. Refonte complète (2026) : plus de serveur, plus de navigateur, plus de
   HTML : chaque écran est un composable Compose. `flask`, `dice_web.py` et
   `android_bridge.py` n'existent plus. Le moteur de jeu restait en Python
   (`game_api.py`), appelé depuis Kotlin via Chaquopy.
4. **Version actuelle : portage du moteur de Python vers Kotlin, en cours.**
   `dice_engine.py`, `stories.py`, `mistral_client.py`, `image_utils.py` et
   `journal_export.py` ont été réécrits en Kotlin pur (`DiceSession.kt`,
   `StoryRegistry.kt`, `GameEngine.kt`, `ImageUtils.kt`, `MistralClient.kt`,
   `JournalExporter.kt`) — plus aucune dépendance à Chaquopy pour la logique
   de jeu elle-même. Les fichiers Python et Chaquopy restent présents dans le
   projet le temps de **reconnecter les écrans** à ce nouveau moteur (voir
   « État de la migration » ci-dessous) ; une fois ce travail terminé, tout
   le Python et Chaquopy pourront être retirés du projet.

## Comment Kotlin et le moteur de jeu communiquent (architecture cible)

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
- Il n'y a plus de répertoire de travail à fixer au démarrage (l'équivalent
  de `game_api.init_app_dir()`) : `GameEngine` reçoit directement
  `context.filesDir` à la construction.

## État de la migration Python → Kotlin

Le moteur de jeu est **entièrement porté** en Kotlin (`DiceSession.kt`,
`StoryRegistry.kt`, `GameEngine.kt`, `ImageUtils.kt`, `MistralClient.kt`,
`JournalExporter.kt`) et `GameViewModel.kt` l'utilise déjà pour tout.

Ce qui **reste à faire** : les écrans appellent encore
`Python.getInstance().getModule("game_api")...` directement (au lieu des
méthodes de `GameViewModel`), en s'appuyant sur `game_api.py` (toujours
présent, non branché sur le nouveau moteur). C'est le cas d'au moins
`MainGameScreen.kt` (la quasi-totalité des actions de l'écran de jeu),
`CreateStoryScreen.kt` (création, ajout de totem, import de progression) et
vraisemblablement `ConfigureKeyScreen.kt` / `ClassicDiceScreen.kt`. Tant
qu'un écran n'est pas reconnecté, il continue de fonctionner (les deux
moteurs coexistent), mais **via une session Python complètement séparée**
de celle de `GameEngine` — un écran migré et un écran non migré ne
partagent donc plus le même état tant que la migration n'est pas terminée
partout.

Reste également à vérifier, en reconnectant `MainGameScreen.kt` :
`GameViewModel.loadSessionState()` a été rendu `private` lors du portage
(les nouvelles méthodes dédiées l'appellent en interne) — il faudra soit le
rouvrir, soit (mieux) remplacer chaque appel direct à
`python.getModule("game_api")...` par la méthode `GameViewModel`
correspondante.

Une fois tous les écrans reconnectés : retirer `game_api.py`,
`dice_engine.py`, `stories.py`, `mistral_client.py`, `image_utils.py`,
`journal_export.py`, le dossier `python/`, le plugin Chaquopy et les
dépendances `pillow`/`fpdf2` d'`app/build.gradle`.

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
6. `NarrationSection` : conversation avec l'IA (dernière réponse, fil complet, message libre, réinitialisation).
7. `TotemGaugesRow` : une jauge par totem, utilisable quand elle est pleine ; gestion des totems ajoutés en cours de partie (`TotemManagerDialog`).
8. `SideQuestsList` : quêtes secondaires.
9. `ContinueSection` : démarrer ou relancer un chapitre, ou copier le prompt complet (mode manuel).
10. `HistoryList` : historique des lancers (annuler le dernier, tout effacer).
11. `JournalSection` : journal de l'histoire (chapitres), lecture, export PDF/TXT, export de l'identité (histoires personnalisées). La case de saisie manuelle est masquée dès qu'une clé Mistral valide est active, puisque le digest (voir ci-dessous) remplit alors le journal automatiquement ; le titre, la lecture et les exports restent toujours affichés.

Habillage commun « papier BD » : palette encre / papier / rouge / or, polices
**Bangers** (titres) et **Nunito** (texte), boutons `ComicButton`.

## Narration automatique (Mistral, optionnelle)

- Compte gratuit sur [console.mistral.ai](https://console.mistral.ai/), génération d'une clé API, puis saisie dans `ConfigureKeyScreen`. La clé est **partagée par toutes les histoires** (stockée dans `app_config.json`, pas dans une sauvegarde d'histoire).
- Modèles proposés (`MistralClient.MODEL_CHOICES`) : Ministral 8B (très économique), **Mistral Small** (par défaut, recommandé), Mistral Medium (récits plus riches).
- `MistralClient.kt` n'utilise que `HttpURLConnection` (aucune dépendance) et **ne lève jamais d'exception** : il renvoie `(texte, erreur)` avec un message lisible (clé refusée, limite atteinte, réseau injoignable…). Délai maximal 40 s, 1400 tokens de réponse.
- Le prompt caching de Mistral est utilisé (`prompt_cache_key`) : le début du prompt (mécaniques + résumé long terme) est facturé moins cher tant qu'il ne change pas.
- À chaque lancer ou usage de jauge, l'événement est envoyé à l'IA (`GameEngine.runAiNarrator`). Seule une fenêtre récente de la conversation (24 messages, `AI_HISTORY_WINDOW`) est renvoyée au modèle ; l'historique complet reste affiché et sauvegardé. Tous les 6 messages (3 événements envoyés + 3 réponses de l'IA), un appel « digest » (`GameEngine.maybeUpdateStoryDigest`) produit **en une seule requête** un nouveau chapitre de journal (`story_log`) et le **résumé long terme** mis à jour (`story_summary`, réponse découpée via des balises `###CHAPITRE###`/`###RESUME###`) — le journal se remplit donc automatiquement tant qu'une clé est active, d'où le masquage de la case de saisie manuelle (voir plus haut). Le résumé est réinjecté dans chaque prompt envoyé à l'IA dès qu'il existe.
- **Sans clé**, l'appli fonctionne en **mode manuel** : `ContinueSection` permet de copier le prompt complet à coller dans une IA externe, et le joueur colle en retour les résumés de chapitre dans le journal.
- « Démarrer ou relancer un chapitre » envoie mécaniques + histoire déjà vécue et demande à l'IA de commencer le chapitre suivant, sans attendre de lancer.
- « Réinitialiser » (`GameEngine.resetAiConversation`) remet **toute** l'histoire à zéro (dés, jauges, menace, quêtes, totems ajoutés), pas seulement la conversation : une confirmation est demandée.

## Les histoires

Il n'y a plus de registre d'histoires codées en dur : `StoryRegistry` ne
gère plus que des histoires personnalisées, et **toute** histoire (créée
depuis l'appli ou réimportée depuis un fichier d'identité) passe par le
même mécanisme, décrit par un fichier JSON (`custom_stories.json`) + une
image dans `custom_story_bg/`. Au premier lancement, le carrousel est vide
(seule la tuile « Nouvelle histoire » s'affiche) tant qu'aucune histoire
n'a été créée ou réimportée.

- **Créées depuis l'appli** (`CreateStoryScreen`) : titre, sous-titre, description d'univers, prénom du héros (optionnel), premier totem (nom, pouvoirs, capacité, image). Le prénom du héros, s'il est renseigné, devient `protagonistRef` (l'IA s'adresse alors à lui par son nom) ; laissé vide, il retombe sur la formule générique « le personnage principal » — utile par exemple quand le prénom doit être demandé au joueur en cours d'aventure plutôt que fixé à l'avance.
- **Exportables et réimportables** : une histoire peut être exportée en fichier JSON (« identité ») et réimportée (`GameEngine.exportIdentity` / `importIdentity`). L'export embarque, en plus du totem de départ et du prénom du héros : tous les totems acquis en cours de partie (`DiceSession.customTotems`, images comprises), les quêtes secondaires et le journal (`storyLog` + résumé long terme `storySummary`) — l'inverse exact de ce que `StoryRegistry.parseIdentityImport` relit. À l'import, `CreateStoryScreen` crée d'abord l'histoire avec un seul totem de départ (la règle ne change pas : `createStory` n'en accepte jamais qu'un), puis recrée les totems supplémentaires un par un via `addCustomTotem`, et restaure enfin quêtes et journal en un seul appel à `applyImportedProgress` (qui délègue la validation à `DiceSession.importProgress`). Un ancien fichier exporté (sans ces champs) s'importe toujours sans problème.
- **Sauvegardes totalement séparées** : `dice_state_<slug>.json` par histoire — dés, jauges, totems et quêtes d'une histoire n'influencent jamais une autre.
- **Suppression** (`GameEngine.deleteStory`) : efface l'entrée dans `custom_stories.json`, son image de fond, sa sauvegarde de partie et l'image de son totem de départ. Ne touche évidemment jamais aux autres histoires.
- Ajouter une **histoire intégrée** (codée en dur) n'est plus prévu par cette architecture : `StoryRegistry` part du principe que toute histoire est personnalisée.

### Totems ajoutés en cours de partie

Quand un « ❓ » du dé du destin débloque une quête secondaire de type
« objet », l'IA invente la rencontre d'un nouveau totem. **C'est ensuite le
joueur qui l'ajoute** (`TotemManagerDialog` : nom, pouvoirs, capacité
optionnelle, emoji ou image). Il obtient alors sa jauge, apparaît dans le
sélecteur de symboles, et entre dans le contexte envoyé à l'IA — sans
réécrire les chapitres précédents. Les images sont stockées dans
`totem_images/` (redimensionnées par `ImageUtils.kt`). Pour une histoire
personnalisée, ces totems ajoutés en cours de partie font partie de l'export
d'identité (voir ci-dessus) au même titre que le totem de départ.

## Journal de l'histoire

Le journal (`storyLog`) contient un chapitre par entrée. Il alimente le
prompt de démarrage (« histoire déjà vécue ») et peut être exporté en
**PDF** via `JournalExporter.kt` (`android.graphics.pdf.PdfDocument`,
natif — toujours disponible, contrairement à l'ancienne dépendance
optionnelle `fpdf2`), ou en **TXT** en repli si le journal est vide.
L'export passe par le sélecteur de fichiers Android (Storage Access
Framework). Pour une histoire personnalisée, le journal (ainsi que le
résumé long terme `storySummary` qui l'accompagne) fait aussi partie de
l'export d'identité (voir plus haut), et est restauré à l'import.

## Où sont les données (stockage privé de l'appli)

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
├── build.gradle                      # plugins Android / Kotlin / Chaquopy (racine)
├── settings.gradle
├── gradle.properties
├── .gitignore
└── app/
    ├── build.gradle                  # config Android, Compose, Chaquopy — À RETIRER une fois tous les écrans reconnectés (pip : pillow, fpdf2, plus utiles côté Kotlin)
    ├── proguard-rules.pro            # vide (minification désactivée)
    └── src/main/
        ├── AndroidManifest.xml       # permission INTERNET (API Mistral)
        ├── res/                      # icônes, polices, images de fond
        ├── java/com/aventure/desdice/
        │   ├── MainActivity.kt       # démarre Python (encore nécessaire tant que les écrans n'ont pas migré) + navigation (AppNavigation)
        │   ├── MainGameScreen.kt     # écran de jeu + briques d'UI communes (ComicButton, Section…) — appelle encore Python directement, à reconnecter
        │   ├── GameDice.kt           # face du dé de réussite, badges et fiches de totems
        │   ├── GameExtras.kt         # NarrationSection, ContinueSection, JournalSection, TotemManagerDialog
        │   ├── DiceSession.kt        # moteur de jeu (dés, jauges, menace, quêtes, session) — portage de dice_engine.py
        │   ├── StoryRegistry.kt      # registre des histoires personnalisées — portage de stories.py
        │   ├── GameEngine.kt         # porte d'entrée unique du moteur Kotlin — portage de game_api.py
        │   ├── ImageUtils.kt         # redimensionnement des fonds et des totems — portage de image_utils.py
        │   ├── MistralClient.kt      # client API Mistral — portage de mistral_client.py
        │   ├── JournalExporter.kt    # export PDF du journal (PdfDocument natif) — portage de journal_export.py
        │   ├── SpeechManager.kt      # synthèse vocale (lecture des réponses de l'IA)
        │   ├── model/Story.kt
        │   ├── viewmodel/GameViewModel.kt  # utilise GameEngine ; plus aucun appel Python à l'intérieur
        │   ├── screens/              # ClassicDiceScreen, ConfigureKeyScreen, CreateStoryScreen, StorySelectorScreen — appellent encore Python directement (sauf StorySelectorScreen), à reconnecter
        │   └── ui/AppFonts.kt        # chargement des polices (rememberAppFonts)
        └── python/                   # OBSOLÈTE — conservé le temps de reconnecter les écrans (voir « État de la migration »), à supprimer ensuite avec Chaquopy
            ├── game_api.py           # ancienne porte d'entrée Kotlin → Python (call_json) — remplacée par GameEngine.kt
            ├── dice_engine.py        # remplacé par DiceSession.kt
            ├── stories.py            # remplacé par StoryRegistry.kt
            ├── mistral_client.py     # remplacé par MistralClient.kt
            ├── image_utils.py        # remplacé par ImageUtils.kt
            └── journal_export.py     # remplacé par JournalExporter.kt
```

> `bg_animorph_data.py`, `bg_poudlard_data.py` et `dice_state_seed.json` ne
> sont plus référencés par aucun fichier depuis le retrait des deux
> histoires codées en dur : à supprimer du projet.

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
| Chaquopy | 15.0.1 (Python 3.11) |
| Gradle (dans le workflow) | 8.7 |
| JDK | 17 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| ABI | arm64-v8a, x86_64 |

Le workflow appelle `gradle` directement (pas de `gradlew`), donc aucun
`gradle-wrapper.jar` n'est nécessaire dans le dépôt. Python 3.11 est aussi
installé sur la machine de build : Chaquopy en a besoin pour télécharger les
paquets pip (`pillow`, `fpdf2`), pas pour faire tourner l'appli (Python est
embarqué dans l'APK). Ces deux paquets ne sont plus utilisés par le nouveau
code Kotlin (`ImageUtils.kt`/`JournalExporter.kt` s'en passent) — ils
restent nécessaires uniquement tant que `python/image_utils.py` et
`python/journal_export.py` sont encore dans le projet (voir « État de la
migration »).

### Identifiants

- `namespace` : `com.aventure.desdice` (nom des packages).
- `applicationId` : `com.aventure.desdice.histoiresmultiples`. C'est cet identifiant qu'Android utilise pour décider si une appli est « la même » (mise à jour) ou une autre (installée à côté). Il diffère de celui de l'ancienne version WebView (`com.aventure.desdice.auto`) : les deux peuvent cohabiter, mais leurs sauvegardes ne se transmettent pas.

## En cas d'échec du build

1. Ouvrir l'étape en erreur dans l'onglet Actions et lire le message exact.
2. Le plus souvent, un désaccord de versions entre Chaquopy, l'Android Gradle Plugin, Gradle et Kotlin : voir [chaquo.com/chaquopy/doc/current/versions.html](https://chaquo.com/chaquopy/doc/current/versions.html) pour les combinaisons compatibles.
3. Si Kotlin passe un jour à **2.0 ou plus**, le compilateur Compose se configure autrement : ajouter le plugin `org.jetbrains.kotlin.plugin.compose` et **supprimer** le bloc `composeOptions` de `app/build.gradle`.
4. Ne pas retirer `androidx.appcompat` : le thème du manifeste (`Theme.AppCompat.Light.NoActionBar`) en dépend.

## Ouvrir le projet dans Android Studio (optionnel)

Ouvrir le dossier avec *Open*. Comme le projet n'a pas de wrapper Gradle,
Android Studio utilisera le Gradle installé, ou proposera d'en générer un
(`gradle wrapper --gradle-version 8.7`).
