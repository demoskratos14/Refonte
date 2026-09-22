# Histoires multiples (Dés d'Aventure) — application Android

Application Android de narration interactive à base de dés : le joueur lance
un **dé de réussite** (1 à 6) et un **dé du destin**, fait monter des jauges
(totems, menace), débloque des quêtes secondaires, et une **IA narratrice**
(API Mistral, optionnelle) écrit la suite de l'histoire à chaque lancer.
Plusieurs histoires cohabitent : **Animorph**, **Poudlard**, et autant
d'histoires personnalisées que l'on veut créer depuis l'appli.

L'appli est écrite en **Kotlin / Jetpack Compose** pour l'interface, avec le
moteur de jeu en **Python** embarqué grâce à **Chaquopy**.

## Historique : pourquoi cette architecture

1. Au départ, un script Python lancé dans Pydroid 3 qui ouvrait un
   navigateur externe (`127.0.0.1:5001`).
2. Puis une première appli Android qui démarrait un serveur Flask interne et
   l'affichait dans une `WebView` (`dice_web.py`, `android_bridge.py`).
3. **Version actuelle : refonte complète.** Plus de serveur, plus de
   navigateur, plus de HTML : chaque écran est un composable Compose, et
   Kotlin appelle directement les fonctions Python. `flask`, `dice_web.py` et
   `android_bridge.py` n'existent plus.

## Comment Kotlin et Python communiquent

```
 Compose (écrans)  ──►  GameViewModel  ──►  game_api.call_json("nom_fonction", *args)
        ▲                    │                        │
        │                    │                        ▼
        └── sessionState ◄───┘             dice_engine / stories / mistral_client
            (JSON)                                   │
                                                     ▼
                                     fichiers JSON dans le stockage privé de l'appli
```

- `MainActivity` démarre Python (`Python.start(AndroidPlatform(this))`), puis
  appelle `game_api.init_app_dir()` : le répertoire de travail Python devient
  le stockage privé de l'appli, ce qui fait pointer tous les chemins relatifs
  (sauvegardes, `app_config.json`, `totem_images/`…) au bon endroit.
- **`game_api.py` est la seule porte d'entrée** vers le Python. Presque tout
  passe par `call_json(nom, *args)`, qui appelle la fonction `nom` de
  `game_api` et renvoie du JSON (le plus souvent l'état de session complet,
  `session_to_dict()`). Kotlin le range dans `GameViewModel.sessionState`
  (un `StateFlow`), et tous les écrans se redessinent à partir de lui.
- Les erreurs de l'IA ne font jamais perdre un lancer : elles reviennent dans
  le champ `ai_error` du JSON et s'affichent sous la narration.
- **Exception** : `export_journal()` et `export_identity()` renvoient des
  `bytes` (non sérialisables en JSON) ; Kotlin les appelle donc directement
  via Chaquopy, sans passer par `call_json`.

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
- Modèles proposés (`mistral_client.MODEL_CHOICES`) : Ministral 8B (très économique), **Mistral Small** (par défaut, recommandé), Mistral Medium (récits plus riches).
- `mistral_client.py` n'utilise que `urllib` (aucune dépendance) et **ne lève jamais d'exception** : il renvoie `(texte, erreur)` avec un message lisible (clé refusée, limite atteinte, réseau injoignable…). Délai maximal 40 s, 1400 tokens de réponse.
- Le prompt caching de Mistral est utilisé (`prompt_cache_key`) : le début du prompt (mécaniques + résumé long terme) est facturé moins cher tant qu'il ne change pas.
- À chaque lancer ou usage de jauge, l'événement est envoyé à l'IA (`run_ai_narrator`). Seule une fenêtre récente de la conversation (24 messages, `AI_HISTORY_WINDOW`) est renvoyée au modèle ; l'historique complet reste affiché et sauvegardé. Tous les 6 messages (3 événements envoyés + 3 réponses de l'IA), un appel « digest » (`maybe_update_story_digest`) produit **en une seule requête** un nouveau chapitre de journal (`story_log`) et le **résumé long terme** mis à jour (`story_summary`, réponse découpée via des balises `###CHAPITRE###`/`###RESUME###`, voir `_parse_digest_response`) — le journal se remplit donc automatiquement tant qu'une clé est active, d'où le masquage de la case de saisie manuelle (voir plus haut). Le résumé est réinjecté dans chaque prompt envoyé à l'IA dès qu'il existe.
- **Sans clé**, l'appli fonctionne en **mode manuel** : `ContinueSection` permet de copier le prompt complet à coller dans une IA externe, et le joueur colle en retour les résumés de chapitre dans le journal.
- « Démarrer ou relancer un chapitre » envoie mécaniques + histoire déjà vécue et demande à l'IA de commencer le chapitre suivant, sans attendre de lancer.
- « Réinitialiser » (`do_reset_ai_conversation`) remet **toute** l'histoire à zéro (dés, jauges, menace, quêtes, totems ajoutés), pas seulement la conversation : une confirmation est demandée.

## Les histoires

Toute la différence entre deux histoires est centralisée dans **`stories.py`** :
image de fond, fichier de sauvegarde, symboles et totems de départ, texte
d'univers envoyé à l'IA.

- **Animorph** : les 6 totems animaux (Aigle, Loup, Renard, Jaguar, Grand Bond, Profondeurs) sont acquis dès le début, plus trois alliés fixes liés à des symboles (Araignée = Spider-Man, Bouclier = Captain America, Étoile = Shuri) ; le contexte envoyé à l'IA précise l'univers Marvel.
- **Poudlard** : part presque de zéro (un seul totem, la Baguette Magique) ; amis, créatures et sorts se construisent en jouant, avec une mécanique pédagogique (l'IA enseigne un fait scientifique réel, pose une question, et le sort débloqué en découle).
- **Histoires personnalisées** (`CreateStoryScreen`) : titre, sous-titre, description d'univers, prénom du héros (optionnel), premier totem (nom, pouvoirs, capacité, image). Elles sont décrites par `custom_stories.json` + une image dans `custom_story_bg/`, et supprimables depuis le carrousel. Le prénom du héros, s'il est renseigné, devient `protagonist_ref` (l'IA s'adresse alors à lui par son nom, comme « Gabin » pour Animorph) ; laissé vide, il retombe sur la formule générique « le personnage principal » — utile par exemple quand le prénom doit être demandé au joueur en cours d'aventure plutôt que fixé à l'avance (voir Poudlard). Une histoire personnalisée peut être exportée en fichier JSON (« identité ») et réimportée (`export_identity` / `do_import_identity`). L'export embarque, en plus du totem de départ et du prénom du héros : tous les totems acquis en cours de partie (`session.custom_totems`, images comprises), les quêtes secondaires et le journal (`story_log` + résumé long terme `story_summary`) — l'inverse exact de ce que `stories.parse_identity_import` relit. À l'import, `CreateStoryScreen` crée d'abord l'histoire avec un seul totem de départ (la règle ne change pas : `do_create_story` n'en accepte jamais qu'un), puis recrée les totems supplémentaires un par un via `do_add_custom_totem`, et restaure enfin quêtes et journal en un seul appel à `do_apply_imported_progress` (qui délègue la validation à la nouvelle méthode `DiceSession.import_progress`). Un ancien fichier exporté (sans ces champs) s'importe toujours sans problème.
- **Sauvegardes totalement séparées** : `dice_state_<slug>.json` par histoire — dés, jauges, totems et quêtes d'une histoire n'influencent jamais une autre.
- Ajouter une **troisième histoire intégrée** = ajouter une entrée dans `STORIES` (et `STORY_ORDER`) de `stories.py`, plus un module `bg_<nom>_data.py` pour son image de fond.

### Totems ajoutés en cours de partie

Quand un « ❓ » du dé du destin débloque une quête secondaire de type
« objet », l'IA invente la rencontre d'un nouveau totem. **C'est ensuite le
joueur qui l'ajoute** (`TotemManagerDialog` : nom, pouvoirs, capacité
optionnelle, emoji ou image). Il obtient alors sa jauge, apparaît dans le
sélecteur de symboles, et entre dans le contexte envoyé à l'IA — sans
réécrire les chapitres précédents. Les images sont stockées dans
`totem_images/` (redimensionnées par `image_utils.py`). Pour une histoire
personnalisée, ces totems ajoutés en cours de partie font partie de l'export
d'identité (voir ci-dessus) au même titre que le totem de départ.

## Journal de l'histoire

Le journal (`story_log`) contient un chapitre par entrée. Il alimente le
prompt de démarrage (« histoire déjà vécue ») et peut être exporté :
**PDF** via `journal_export.py` (bibliothèque `fpdf2`), ou **TXT** en repli si
`fpdf2` est absente. L'export passe par le sélecteur de fichiers Android
(Storage Access Framework). Pour une histoire personnalisée, le journal
(ainsi que le résumé long terme `story_summary` qui l'accompagne) fait aussi
partie de l'export d'identité (voir plus haut), et est restauré à l'import.

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
    ├── build.gradle                  # config Android, Compose, Chaquopy (pip : pillow, fpdf2)
    ├── proguard-rules.pro            # vide (minification désactivée)
    └── src/main/
        ├── AndroidManifest.xml       # permission INTERNET (API Mistral)
        ├── res/                      # icônes, polices, images de fond
        ├── java/com/aventure/desdice/
        │   ├── MainActivity.kt       # démarre Python + navigation (AppNavigation)
        │   ├── MainGameScreen.kt     # écran de jeu + briques d'UI communes (ComicButton, Section…)
        │   ├── GameDice.kt           # face du dé de réussite, badges et fiches de totems
        │   ├── GameExtras.kt         # NarrationSection, ContinueSection, JournalSection, TotemManagerDialog
        │   ├── SpeechManager.kt      # synthèse vocale (lecture des réponses de l'IA)
        │   ├── model/Story.kt
        │   ├── viewmodel/GameViewModel.kt
        │   ├── screens/              # ClassicDiceScreen, ConfigureKeyScreen, CreateStoryScreen, StorySelectorScreen
        │   └── ui/AppFonts.kt        # chargement des polices (rememberAppFonts)
        └── python/
            ├── game_api.py           # PORTE D'ENTRÉE unique Kotlin → Python (call_json)
            ├── dice_engine.py        # moteur de jeu (dés, jauges, menace, quêtes, session)
            ├── stories.py            # registre des histoires + histoires personnalisées
            ├── mistral_client.py     # client API Mistral (stdlib pure)
            ├── image_utils.py        # redimensionnement des fonds et des totems (Pillow)
            ├── journal_export.py     # export PDF du journal (fpdf2)
            ├── bg_animorph_data.py   # image de fond Animorph (base64)
            ├── bg_poudlard_data.py   # image de fond Poudlard (base64)
            └── dice_state_seed.json  # partie de départ d'Animorph
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
| Chaquopy | 15.0.1 (Python 3.11) |
| Gradle (dans le workflow) | 8.7 |
| JDK | 17 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| ABI | arm64-v8a, x86_64 |

Le workflow appelle `gradle` directement (pas de `gradlew`), donc aucun
`gradle-wrapper.jar` n'est nécessaire dans le dépôt. Python 3.11 est aussi
installé sur la machine de build : Chaquopy en a besoin pour télécharger les
paquets pip (`pillow`, `fpdf2`), pas pour faire tourner l'appli (Python est
embarqué dans l'APK).

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
