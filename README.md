# Histoires multiples (Dés d'Aventure) — application Android

Application Android de narration interactive à base de dés : le joueur lance
un **dé de réussite** (1 à 6) et un **dé du destin**, fait monter des jauges
(totems, menace), débloque des quêtes secondaires, et une **IA narratrice**
(API Mistral, optionnelle) écrit la suite de l'histoire à chaque lancer.
L'application démarre vierge (aucune histoire codée en dur) : on crée ses
propres histoires depuis l'appli, ou on réimporte un fichier d'identité
précédemment exporté.

Les principales mécaniques, détaillées plus bas :

- **deux dés** : réussite (1 à 6, avec symboles de totems sur les points) et
  destin (6 symboles dont le ❓, qui change d'effet selon la **longueur** de
  l'histoire) ;
- **paliers de contenu** (enfant / adolescent / adulte) déduits de l'âge du
  plus jeune joueur : ils règlent le ton, la peur, la gravité des échecs, la
  difficulté des énigmes et le **seuil de la jauge de menace** ;
- **jauge de menace** (explosion = complication, avec vibration et animation),
  **jauges de totems** (15 points, capacité spéciale, « Second Souffle ») et
  **quêtes secondaires** ;
- **un ou plusieurs héros** par histoire, chacun avec ses propres choix et
  lancers ;
- **fin d'histoire et bilan de l'aventure** (défaite définitive possible aux
  paliers adolescent et adulte) ;
- réponses de l'IA **affichées au fil de l'eau**, relance automatique en cas
  d'erreur réseau, bouton « Réessayer », lecture vocale.

L'appli est écrite entièrement en **Kotlin / Jetpack Compose**, y compris le
moteur de jeu. Un écran **Réglages** permet de personnaliser les polices
(avec couleur et taille), les images de fond des pages, l'icône de
l'application, les sons (dés, musique, vibration et animations) et le public
visé. Des **boutons d'aide** expliquent, écran par écran, comment s'en servir.

## Historique : pourquoi cette architecture

1. L'appli a d'abord été une page web affichée dans une `WebView`. Elle a
   ensuite fait l'objet d'une refonte complète (2026) : plus de HTML, chaque
   écran est un composable Compose.
2. Le moteur de jeu est écrit en **Kotlin pur** (`DiceSession.kt`,
   `StoryRegistry.kt`, `GameEngine.kt` et les classes qu'il délègue :
   `SessionJson.kt`, `PromptBuilder.kt`, `AiNarrator.kt`, `MistralConfig.kt`,
   `StoryTransfer.kt`, `ClassicDiceEngine.kt` ; puis `ImageUtils.kt`,
   `MistralClient.kt`, `JournalExporter.kt`). Tous les écrans (`MainGameScreen.kt` et les
   composables qu'il appelle, `ClassicDiceScreen.kt`, `ConfigureKeyScreen.kt`,
   `CreateStoryScreen.kt`, `StorySelectorScreen.kt`) passent uniquement par
   `GameViewModel`/`GameEngine`. Le projet n'a aucune dépendance native ni
   interpréteur embarqué.
3. **Écran Réglages** : choix des polices (texte, titres, réponses de l'IA),
   des images de fond des pages, de l'icône de l'application, des sons et du
   public visé, sans passer par le moteur de jeu (voir la section
   « Réglages » plus bas).
4. **Boutons d'aide** : un petit bouton rond ouvre une fenêtre d'explication
   propre à l'écran où l'on se trouve (voir « Aides intégrées » plus bas).
5. **Paliers de contenu** : l'âge du plus jeune joueur, saisi à la création
   de l'histoire, choisit le cadre de contenu donné à l'IA et le seuil de la
   jauge de menace (voir « Paliers de contenu »).
6. **Fin d'histoire et bilan** : l'IA signale la conclusion de l'histoire par
   une balise ; un bilan chiffré est alors proposé (voir « Fin d'histoire et
   bilan »).
7. **Robustesse** : réponses de l'IA en streaming, réessais automatiques,
   sauvegardes écrites de façon atomique avec copie de secours
   (`SafeFile.kt`), clé API chiffrée (`SecretCipher.kt`), tests unitaires et
   tests d'écrans exécutés à chaque build (voir « Tests et qualité »).

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
  (`sessionToJson()`).
  `GameViewModel` expose une méthode par action, qui appelle `GameEngine`
  puis range le résultat dans `GameViewModel.sessionState` (un `StateFlow`) ;
  tous les écrans se redessinent à partir de lui.
  `GameEngine` garde l'état courant (histoire, session), la navigation, les
  lancers et les totems ; le reste est délégué, sans changer son API publique :
  état envoyé à Compose (`SessionJson.kt`), textes envoyés à l'IA
  (`PromptBuilder.kt`), appels à Mistral et digest du journal
  (`AiNarrator.kt`), clé et modèle (`MistralConfig.kt`), exports et import
  (`StoryTransfer.kt`), dés classiques (`ClassicDiceEngine.kt`).
- Les erreurs de l'IA ne font jamais perdre un lancer : elles reviennent
  dans le champ `ai_error` du JSON et s'affichent sous la narration. Le
  lancer reste « en attente » dans la session, et le bouton **« 🔄 Réessayer »**
  (`GameEngine.retryAiRequest()`) renvoie exactement le même envoi (même
  lancer, même consigne de quête, sans dépenser de nouveau l'énergie d'un
  totem). En cas d'échec, la conversation retrouve l'état d'avant l'appel :
  aucun doublon.
- **Affichage au fil de l'eau** : `GameViewModel.streamingReply` (un
  `StateFlow<StreamingReply?>`) reçoit le texte de l'IA au fur et à mesure
  (`AiNarrator.runAiNarrator(onStream = …)`). `AiPanel` le montre dans une
  bulle provisoire, qui est remplacée par l'échange terminé sans changement de
  mise en page. Rien de cet aperçu n'est sauvegardé.
- **Exception** : `exportJournal()` et `exportIdentity()` renvoient des
  octets (PDF/JSON, pas du JSON de session) via des fonctions `suspend`
  dédiées de `GameViewModel`, pour que l'écran obtienne le résultat
  directement plutôt que de l'observer dans `sessionState`.
- Il n'y a plus de répertoire de travail à fixer au démarrage : `GameEngine`
  reçoit directement `context.filesDir` à la construction.
- **Les réglages** (polices, fonds, icône, sons, public visé) ne passent pas
  par `GameEngine` : ils sont gérés par de petits objets du package `ui`
  (`FontPrefs`, `BackgroundStore`, `AppIconStore`, `SoundPrefs`,
  `AudiencePrefs`, `ClassicDicePrefs`), voir plus bas.
- **Changer le palier de contenu en cours d'histoire** passe par
  `GameEngine.setPlayerAge()` (voir « Paliers de contenu »).
- **Clé API** : `MistralConfig` chiffre la clé avec `SecretCipher`
  (`KeystoreSecretCipher`, AES-256-GCM, clé gardée dans l'Android Keystore) ;
  les tests utilisent `SecretCipher.None`.
- **Sauvegardes** : toutes les écritures JSON importantes passent par
  `SafeFile` (voir « Fichiers stockés sur l'appareil »).

## Navigation (MainActivity.kt → `AppNavigation`)

| Ordre | Écran | Rôle |
|---|---|---|
| 0 | `AudienceChoiceScreen` | Tout premier écran, une seule fois : choix du public visé (filles / garçons / les 2). Détermine les icônes proposées dans Réglages et le fond par défaut de la page « Nouvelle histoire ». Pas de bouton retour ; modifiable ensuite dans Réglages > Profil |
| 1 | `ConfigureKeyScreen` | Deuxième écran : saisie de la clé API Mistral (optionnelle, « Passer » possible) et choix du modèle. Un **engrenage** (en haut à droite) ouvre les Réglages |
| 2 | `StorySelectorScreen` | Carrousel des histoires + page « Nouvelle histoire » ; icône dé (en haut à gauche) → « Des classiques » ; icône 🔑 → configuration de la clé ; corbeille sur les histoires personnalisées |
| 3 | `MainGameScreen` | La partie en cours (voir ci-dessous) |
| — | `SettingsScreen` | Réglages, en 5 pages qu'on fait glisser : Polices, Photos, Icône, Sons, Profil |
| — | `CreateStoryScreen` | Création d'une histoire personnalisée (voir ci-dessous) |
| — | `ClassicDiceScreen` | Page « Des classiques » : dé de réussite (d6 ou autre dé) + dé du destin, indépendants de toute histoire (voir « Des classiques » plus bas) |

Le bouton retour du téléphone ferme les écrans secondaires ; depuis la
partie, il rouvre le carrousel sans changer l'histoire active.

`SettingsScreen` est testé en premier dans le `when` d'`AppNavigation` : on
peut donc l'ouvrir depuis la page de la clé API dès le premier écran de
configuration, ou après l'avoir rouverte depuis le carrousel. Retour = on
revient à la page de la clé. Depuis la partie, le chemin est donc :
« Changer d'histoire » → icône 🔑 → engrenage.

### L'écran de jeu, de haut en bas

1. En-tête : titre de l'histoire avec, en haut à droite, le **bouton d'aide** « Comment fonctionne le jeu ? » ; lien « Changer d'histoire » ; liens musique (couper / lancer / autre morceau, visibles s'il existe des morceaux) ; bouton « 🚀 Démarrer l'histoire » (avec clé API, tant que l'IA n'a rien répondu). Puis les badges des totems (touche = fiche du totem).
2. `AiPanel` : conversation avec l'IA (fil complet, réponse écrite **au fil de l'eau**, message libre, lecture vocale du dernier message, réinitialisation avec confirmation) ; masqué (message + bouton vers la configuration) tant qu'aucune clé Mistral n'est enregistrée. Quand la réponse se termine par des lignes `OPTION: …`, elles sont retirées du texte et proposées en **boutons d'actions** sous le dernier message (le champ libre reste disponible, et les choix peuvent être lus à voix haute). En cas d'erreur d'envoi, un bouton « 🔄 Réessayer » apparaît. Quand l'IA conclut l'histoire, un bouton « 🏆 Voir le bilan de l'aventure » apparaît (jamais d'office, pour ne pas cacher le texte de fin). C'est aussi ici que vibration et explosion se déclenchent après une explosion de la jauge de menace. Remonté juste sous l'en-tête (au-dessus des dés) pour rester bien visible ; dès qu'une nouvelle réponse de l'IA arrive, l'écran défile automatiquement pour l'amener en haut (position mesurée via `onGloballyPositioned`, dans `MainGameScreen.kt`).
3. `DiceResultCard` (`DicePanel.kt`) : lancers de dés (dés 3D animés), résultat, note pour l'IA, « Réessayer » si l'envoi à l'IA a échoué.
4. `ThreatGauge` : jauge de menace, dont le **seuil dépend du palier de contenu** (voir « La jauge de menace »).
5. `SymbolPicker` : symbole affiché sur le dé de réussite (mode unique / aléatoire / mixte).
6. Bouton « Configurer les valeurs autorisées » (`AllowedValuesDialog`) : quelles faces du dé de réussite et du dé du destin comptent. Dessous, le bouton « **Palier de contenu : enfant / adolescent / adulte** » (`ContentTierDialog`) permet de changer le palier en cours d'histoire (voir « Paliers de contenu »).
7. `TotemGaugesRow` (`TotemsAndQuests.kt`) : une jauge par totem (15 points pour la remplir), bouton « Utiliser » actif quand elle est pleine (la jauge repart alors à zéro, l'image du totem apparaît en grand à l'écran, et les symboles patte et baguette relancent le dernier lancer de réussite : « Second Souffle ») ; bouton « ➕ Ajouter / gérer les totems » (`TotemManagementDialog` : nom, pouvoirs, capacité spéciale, emoji ou image, suppression).
8. `SideQuestsList` (`TotemsAndQuests.kt`) : quêtes secondaires (voir « Le symbole ❓ du dé du destin selon la longueur »).
9. `ContinueSection` : démarrer ou relancer un chapitre, ou copier le prompt complet (mode manuel).
10. `HistoryList` : historique des lancers (annuler le dernier, tout effacer).
11. `JournalSection` : journal de l'histoire (masqué quand la narration automatique est active).

Le fond de l'écran de jeu est l'image de l'histoire en cours (elle se choisit
à la création de l'histoire) ; il n'est pas modifiable depuis les Réglages.

Par-dessus tout le contenu, deux couches d'effets (`ThreatExplosionOverlay`
et `TotemUseOverlay`, posées dans `MainGameScreen`) affichent l'explosion de
la jauge de menace et l'image du totem utilisé (voir plus bas).

### La page « Des classiques » (`ClassicDiceScreen`)

Pensée comme un aide-mémoire pour une partie sur table, **sans lien avec une
histoire** :

- **Dé de réussite** au choix : d4, dé classique à 6 faces (avec points), d8,
  d10, d12, d20, ou « Autre » pour saisir un nombre de faces libre (de 2 à
  999). Un d6 affiche des points, tout autre dé affiche le nombre tiré. Le
  choix est mémorisé (`ClassicDicePrefs`, package `ui`).
- **Dé du destin** : les 6 symboles habituels.
- Les dés sont de vrais **cubes 3D** qui roulent, rebondissent et s'immobilisent
  sur la face tirée (le résultat est tiré avant l'animation, qui dure 1,8 s).
  On touche un dé pour le lancer ; un bouton rond ⚡ entre les deux lance les
  deux à la fois.
- **Historique** des lancers, avec « Effacer l'historique » (confirmation
  demandée), enregistré dans `classic_dice_state.json`.
- Le bruit des dés suit les réglages de la page Sons.
- Fond : `BackgroundSlot.CLASSIC_DICE`.

### Création d'une histoire (`CreateStoryScreen`)

Un bouton d'aide explique chaque champ (`HelpTexts.CREATE_STORY`).

Champs, de haut en bas : import d'une identité exportée (optionnel), titre,
sous-titre, description de l'univers (texte de lore envoyé à l'IA), **héros**
(optionnel : un ou plusieurs prénoms, « + Ajouter un héros » et ✕ pour en
retirer un ; les champs laissés vides sont ignorés, et sans héros l'IA
s'adresse à « le personnage principal »), longueur de l'histoire, objectif
moral (optionnel), **âge du plus jeune joueur** (voir « Paliers de contenu »),
image de fond (optionnelle), totem de départ
(nom / pouvoirs séparés par des virgules / capacité spéciale + image
optionnelle). Une image venue d'un import d'identité est utilisée tant que
l'utilisateur n'en choisit pas une autre. Le bouton « Créer l'histoire »
enchaîne création, totems et progression importée, puis ferme l'écran.

**Longueur de l'histoire** (`storyLength`, propagé jusqu'à
`StoryEntry.storyLength` et transmis à l'IA via
`PromptBuilder.buildStoryLengthInstructions()`) :

| Valeur | Libellé | Effet |
|---|---|---|
| `short` | Courte (~10 échanges) | Histoire complète qui se conclut naturellement autour du 10ᵉ échange ; un rappel de progression à jour (`buildStoryProgressNote`) est renvoyé à l'IA à chaque appel |
| `medium` | Moyenne (20 à 30) | Idem, mais viser 20 à 30 échanges. **Valeur par défaut à la création** d'une nouvelle histoire |
| `long` | Longue (chapitres) | Pas de limite totale ; l'IA découpe en chapitres (chacun avec une vraie fin) et demande confirmation avant d'enchaîner sur le suivant. Valeur de repli côté moteur pour les histoires déjà existantes (avant l'ajout de ce réglage) |

Une identité importée restaure sa propre longueur d'histoire
(`story_length` dans le JSON exporté) ; si absente, elle retombe sur `long`.

La longueur change aussi la mécanique du symbole ❓ du dé du destin (voir la
section suivante).

### Le symbole ❓ du dé du destin selon la longueur

Le ❓ n'ouvre plus toujours « une quête à faire quand on veut » : son effet
dépend de la longueur de l'histoire.

| Longueur | Effet du ❓ | Détail technique |
|---|---|---|
| `short` | **Événement soudain et inattendu**, qui surgit tout de suite dans la scène en cours. Aucune quête annexe n'est ouverte | pas de `SideQuest` créée ; le lancer porte `RollRecord.suddenEvent = true` ; le ❓ n'est jamais retiré du tirage (l'événement est instantané) |
| `medium` | **Quête secondaire construite en parallèle** de l'histoire principale : l'IA l'entrelace à l'intrigue au fil des scènes | `SideQuest` créée directement `ouverte` (`mode = "parallele"`) ; le ❓ ne retombe plus tant qu'une quête est ouverte |
| `long` | **Quête relativement courte, lancée automatiquement à la fin du chapitre** (2 ou 3 échanges, puis retour au fil principal) | `SideQuest` créée `en_attente` (`mode = "fin_chapitre"`), puis passée à `ouverte` à la fin du chapitre ; le ❓ ne retombe plus tant qu'une quête est ouverte **ou en attente** |

Statuts d'une quête (`SideQuest.status`) : `en_attente` (histoire longue,
pas encore lancée), `ouverte`, `terminee`. Les listes de l'interface qui
s'appuient sur `openSideQuests()` ne voient donc pas les quêtes en attente ;
`pendingSideQuests()` les donne. Une quête sans champ `mode` (ancienne
sauvegarde, identité importée) est traitée comme `parallele`.

**Où vit la longueur.** La source de vérité est `StoryEntry.storyLength`.
`GameEngine` la réapplique à la session avec `DiceSession.setStoryLength()`
après chaque `load()` (`switchStory`, `resetStoryToOrigin`) ; elle est aussi
écrite dans la sauvegarde (`story_length`). `DiceSession` accepte
`short/medium/long` comme `courte/moyenne/longue`
(`normalizeStoryLength()`).

**Ce que l'IA reçoit.**
- Le message système (`PromptBuilder.buildMechanicsContext()`) décrit le rôle du
  ❓ pour la longueur de l'histoire.
- À chaque lancer, `DiceSession.describeRecord()` (via `fateDescription()` et
  `comboNote()` pour les combos 1/6 + ❓) décrit ce qui se passe : événement
  soudain, quête à tisser en parallèle, ou quête en attente de fin de chapitre.
  `narratorNoteForRecord()` ne traite plus que le ❗, pour ne pas dupliquer.

**Lancement en fin de chapitre (histoire longue).**
- *Narration automatique* : tant qu'une quête attend, `buildPendingQuestNote()`
  ajoute à **chaque appel API** un rappel technique (jamais sauvegardé, jamais
  affiché) : ne pas développer la quête avant la fin du chapitre, la lancer dès
  qu'il est conclu, sans attendre le joueur, et terminer ce message par la
  balise `[[QUETE_LANCEE]]`. `runAiNarrator()` retire la balise du texte et
  appelle `DiceSession.launchPendingQuest()`, qui passe la quête à `ouverte`.
  Si l'IA oublie la balise, la quête reste en attente et le rappel continue.
- *Ajout d'un chapitre à la main* (résumé collé, mode manuel) :
  `DiceSession.addStoryEntry()` lance la quête et prépare une consigne, ajoutée
  une seule fois au prochain message envoyé à l'IA
  (`consumeQuestAnnouncement()`) ou au prompt copié (`buildFullPromptText()`).
- Les « chapitres » produits automatiquement par le digest du journal
  (`applyStoryDigest()`, une tranche tous les 6 messages) ne déclenchent
  **jamais** le lancement : ce sont des tranches de journal, pas des fins de
  chapitre narratives.

**Objectif moral** (`moralGoal`, optionnel — propagé jusqu'à
`StoryEntry.moralGoal`) : une valeur ou une notion que l'aventure doit
mettre en avant à travers ses situations (patience, fair-play, partage,
courage face à la peur, entraide...), en plus de l'univers décrit dans le
texte de lore — jamais à sa place. Transmis à l'IA dans
`PromptBuilder.buildMechanicsContext()` par une instruction dédiée
(« OBJECTIF MORAL DU RÉCIT »), juste après les paragraphes de lore, avec la
consigne explicite de le faire vivre par le récit (situations, personnages,
choix) et de ne jamais le tourner en discours moralisateur direct. Laissé
vide, aucune instruction n'est envoyée : comportement inchangé pour toute
histoire qui n'en définit pas. Restauré lui aussi par un import d'identité
(`moral_goal` dans le JSON exporté).

**Plusieurs héros** (`protagonist_names`, tableau ; l'ancien champ
`protagonist_name` est conservé pour les anciennes versions et sert de repli à
la lecture). Avec un seul héros, l'IA le tutoie. Avec plusieurs, elle vouvoie
le groupe quand il agit ensemble et tutoie chacun par son prénom quand l'un
d'eux agit ou choisit seul ; elle demande à chacun son propre lancer à tour de
rôle et préfixe chaque ligne `OPTION:` du prénom du héros concerné
(`PromptBuilder.protagonistAddressing()`).

Le bloc « importer une identité exportée (JSON) » (collage manuel ou choix
d'un fichier) pré-remplit tous les champs ci-dessus, y compris les totems
supplémentaires, quêtes secondaires et journal, appliqués à part juste après
la création (`addCustomTotemAwait` puis `applyImportedProgress`). L'âge
(`player_age`) est exporté avec l'identité, mais la case de confirmation
« 18 ans ou plus » n'est **jamais reprise d'un import** : elle est à recocher.

## Paliers de contenu (`AgeTier.kt`)

À la création, on indique l'**âge du plus jeune joueur** (champ `playerAge` de
`StoryEntry`, clé `player_age` dans les fichiers ; `PlayerAge.normalize()` le
ramène entre 3 et 110 ans, `0` = inconnu). Il détermine un **palier** (`AgeTier`) :

| Palier | Âge | Seuil de menace | Peur et monstres | Échecs | Énigmes |
|---|---|---|---|---|---|
| `CHILD` (enfant) | moins de 12 ans, ou âge inconnu | 10 | basse : créatures drôles, rassurantes | jamais définitifs ni humiliants, personne ne meurt, on peut toujours se rattraper | simples, un seul indice à la fois, indices plus évidents si le joueur hésite |
| `TEEN` (adolescent) | 12 à 17 ans | 8 | moyenne : ambiances tendues, frissons, jamais gore | vraies conséquences, un échec critique isolé n'est jamais fatal ; défaite définitive possible mais **rare**, toujours annoncée par des signes clairs ; sans contenu sexuel | plus élaborées (deux ou trois indices à relier, dilemmes) |
| `ADULT` (adulte) | 18 ans et plus | 6 | élevée quand l'univers s'y prête : horreur, danger mortel | conséquences lourdes et durables ; défaite définitive possible quand les mauvais choix et les mauvais jets s'accumulent (jamais sur un seul échec critique) ; sans contenu sexuel explicite | vraies énigmes, fausses pistes, dilemmes sans bonne réponse évidente |

Ce sont des **plafonds, pas des obligations** : l'univers décrit par le joueur
garde la main (une histoire douce reste douce même pour des adultes). Avec
plusieurs héros, un héros qui tombe n'arrête pas l'histoire tant que d'autres
peuvent continuer. Dans tous les paliers, l'IA ne demande jamais
d'information personnelle, de secret ni de quitter l'histoire.

- **Confirmation adulte** : pour un âge de 18 ans ou plus, la création (et le
  passage au palier adulte en cours d'histoire) exige de cocher « Je confirme
  avoir 18 ans ou plus ». L'âge est seulement déclaré, jamais vérifié.
- **Texte envoyé à l'IA** : `PromptBuilder.buildContentFrame(playerAge)` =
  « TON DU RÉCIT » (`buildToneAndLimits`) + « PEUR ET MONSTRES », « ENJEUX ET
  ÉCHECS » et « PROBLÈMES ET ÉNIGMES » (`buildStakesFrame`). Il est inclus dans
  le message système initial.
- **Changer de palier en cours d'histoire** : bouton « Palier de contenu » de
  l'écran de jeu → `GameViewModel` → `GameEngine.setPlayerAge()`. Trois
  raccourcis (enfant / adolescent / adulte) remplissent le champ d'âge, qui
  reste modifiable à la main. Le moteur met à jour l'histoire
  (`StoryRegistry.updatePlayerAge`), le seuil de la jauge de menace
  (`DiceSession.applyThreatThreshold`, qui ramène la jauge juste sous le nouveau
  seuil si elle le dépassait) et remplace le cadre de contenu dans le message
  système déjà envoyé (`DiceSession.replaceInConversation`, ou reconstruction
  complète pour une conversation écrite par une ancienne version). Si le
  **palier** change, une note ponctuelle (`buildFrameChangeNote`, envoyée une
  seule fois, jamais sauvegardée) demande à l'IA d'appliquer le nouveau ton
  dès sa prochaine réponse, sans le mentionner au joueur. Ce qui est déjà
  écrit ne change pas.
- **Histoires créées avant ce réglage** : âge inconnu = palier enfant, le plus
  prudent.

## La jauge de menace

Gérée par `DiceSession.applyThreat()` à chaque lancer du dé de réussite :

- **1** : +3 points ; **5 ou 6** : −1 point (jamais sous zéro) ;
- quand la jauge atteint le **seuil du palier** (10 enfant, 8 adolescent, 6
  adulte, voir `PlayerAge.threatThreshold()`), une **complication** survient
  puis la jauge retombe à zéro ; le lancer porte `RollRecord.threatTriggered =
  true`. La gravité de la complication suit le palier (surprise amusante pour
  un enfant, danger sérieux aux conséquences durables pour un adulte).
- `PromptBuilder.buildGaugeStateNote()` rappelle à l'IA, **à chaque appel**, le
  niveau réel de la menace (`n/seuil`) et des jauges de totems, avec
  l'interdiction de les modifier ou de les inventer.
- Si le joueur enchaîne plusieurs lancers avant d'écrire, seul le dernier part
  avec le message suivant : `DiceSession.missedThreatEvents()` compte les
  explosions des lancers précédents non transmises, pour que l'IA ne les perde
  pas (`buildMissedThreatNote()`).
- **Effets** (`ThreatExplosion.kt`, `Haptics.kt`) : quand la réponse de l'IA
  qui suit l'explosion s'affiche, le téléphone vibre (deux secousses, permission
  `VIBRATE`) et une explosion plein écran s'anime (`ThreatExplosionOverlay`).
  Les deux se règlent dans Réglages > Sons ; l'animation respecte le réglage
  Android « supprimer les animations ».

## Fin d'histoire et bilan (`BilanDialog.kt`)

L'IA signale par des **balises** en fin de message (définies dans
`PromptBuilder`, retirées du texte affiché par `AiNarrator.runAiNarrator()`) :

| Balise | Sens | Effet côté appli |
|---|---|---|
| `[[FIN_CHAPITRE]]` | conclusion d'un chapitre (histoire longue) | digest immédiat du journal, sans attendre 6 échanges |
| `[[QUETE_LANCEE]]` | la quête courte en attente est lancée (histoire longue) | quête `en_attente` → `ouverte`, fin de chapitre implicite |
| `[[HISTOIRE_TERMINEE]]` | fin **définitive** de l'histoire | `DiceSession.markStoryEnded()`, dernier digest du journal, bilan débloqué |

- Une histoire **courte** ou **moyenne** s'achève naturellement vers le 10ᵉ ou
  le 20ᵉ–30ᵉ échange. Une histoire **longue** n'a pas de fin prévue : la balise
  `[[HISTOIRE_TERMINEE]]` est réservée à une défaite définitive (jamais pour
  conclure un chapitre).
- **Bilan** : `DiceSession.bilanAvailable()` = histoire terminée **et** non
  courte. Le bouton « 🏆 Voir le bilan de l'aventure » (`AiPanel`) ouvre
  `BilanDialog` : nombre de lancers (`totalRolls`), de totems utilisés
  (`totemsUsed`) et de quêtes accomplies (`completedQuestCount()`), transmis par
  la clé `bilan` de `SessionJson`.
- Ces compteurs sont propres à la partie : annuler ou effacer l'historique des
  lancers ne les change pas (un lancer annulé ou relancé par « Second Souffle »
  est en revanche décompté).

## Réponses de l'IA : envoi, réessais, rappels

- **Modèle** : `mistral-small-2603` par défaut (`MistralClient.DEFAULT_MODEL`),
  modifiable sur l'écran de la clé API. `promptCacheKey` = identifiant de
  l'histoire.
- **Streaming** : `MistralClient.chat(onPartial = …)` lit les événements SSE et
  appelle le rappel au plus ~15 fois par seconde. Le résultat final est le même
  avec ou sans streaming.
- **Réponse coupée** (`finish_reason = "length"`, ou coupure en plein stream) :
  l'IA est relancée pour finir son texte, jusqu'à 3 fois, et les morceaux sont
  recollés.
- **Réessai automatique** : erreur réseau ou limite de débit (429), jusqu'à 3
  essais avec attente croissante (ou `Retry-After`) ; après un délai dépassé, un
  seul réessai ; en streaming, seulement tant qu'aucun texte n'a été reçu. Les
  autres erreurs (clé refusée, modèle invalide, panne 5xx) ne sont pas
  retentées ; le joueur voit le message et le bouton « 🔄 Réessayer ».
- **Rappels recalculés à chaque appel** (ajoutés à la copie envoyée à l'API,
  jamais sauvegardés ni affichés) : progression de l'histoire courte ou
  moyenne (`buildStoryProgressNote`), état des jauges et pouvoirs actuels des
  totems (`buildGaugeStateNote`), fin de chapitre (`buildChapterEndNote`) et
  quête en attente (`buildPendingQuestNote`) pour l'histoire longue, et, une
  fois, la note de changement de palier.
- **Fenêtre de contexte** : le message système, le résumé long terme et les 24
  derniers messages (`AI_HISTORY_WINDOW`) partent à l'API ; la conversation
  complète reste sauvegardée et affichée.
- **Journal automatique** : un chapitre de journal et un résumé long terme sont
  écrits tous les 6 échanges environ, et tout de suite en fin de chapitre ou
  d'histoire (appel « fantôme » qui n'écrit jamais dans la conversation).
- **Lecture vocale** (`SpeechManager`, `SpeechText`) : le texte lu est épuré
  (astérisques, titres, puces, émojis retirés ; le dernier séparateur `---`
  devient « À toi ! ») ; le texte affiché ne change pas.

## Réglages (`screens/SettingsScreen.kt`)

Accessible par l'engrenage de `ConfigureKeyScreen` (composable
`SettingsGearButton`). L'écran se compose d'un en-tête fixe (flèche de retour
+ titre) et de **5 pages** qu'on change en glissant, ou en touchant l'onglet
vertical collé au bord de l'écran (« Photos › », « ‹ Polices », « Icône › »…) :
**Polices, Photos, Icône, Sons, Profil**. Le fond de la page Réglages est
lui-même personnalisable.

### Page 1 — Polices (police, couleur, taille)

Trois catégories de texte **indépendantes**, chacune avec sa police, sa
couleur et sa taille (`TextStyleChoice` dans `ui/FontPrefs.kt`) :

| Catégorie | Effet | Défaut | Taille (sp) |
|---|---|---|---|
| Titres (`FontPrefs.title`, `AppFonts.display`) | titres des histoires, en-têtes, titres de sections, boutons, légendes des dés | Bangers | 16 à 34 (22 par défaut) |
| Texte courant (`FontPrefs.body`, `AppFonts.body` / `bodyBold`) | descriptions, libellés d'interface, écrans de réglages, narration hors bulles IA | Nunito | 12 à 20 (15 par défaut) |
| Réponses de l'IA (`FontPrefs.reply`, `AppFonts.reply`) | bulles de conversation avec l'IA et champ où le joueur écrit ses réponses | police du texte courant | 12 à 24 (15 par défaut) |

- Les polices se choisissent parmi les fichiers `.ttf` / `.otf` présents dans
  `app/src/main/assets/fonts/` (chaque police est affichée dans son propre
  style, avec un aperçu). Pour **ajouter une police** : déposer le fichier
  dans ce dossier, elle apparaît d'elle-même dans les listes.
- La couleur se choisit avec une **roue chromatique** ; « Auto » (aucune
  couleur choisie) garde la couleur d'origine de chaque écran.
- Les variantes de style (fichiers se terminant par Bold, Italic, Light, Thin,
  Medium, Black) ne sont pas proposées comme police à part entière. La
  variante grasse du texte est retrouvée automatiquement : si on choisit
  `Foo-Regular.ttf`, `bodyBold` cherche `Foo-Bold.ttf` dans le même dossier,
  sinon il retombe sur la police normale.
- Un réglage « Rétablir » efface le choix de la catégorie.
- Les choix sont mémorisés dans les `SharedPreferences` (`app_font_prefs`)
  par `ui/FontPrefs.kt`. Comme `FontPrefs` expose des états Compose et que
  `rememberAppFonts()` / `rememberAppTextStyles()` les lisent, **tous les
  écrans changent immédiatement**, sans redémarrage. Les tailles sont
  appliquées comme un multiplicateur (`titleSp()`, `bodySp()`) pour garder les
  proportions entre gros et petits titres.
- Si le fichier choisi est illisible ou a disparu, `rememberAppFonts()`
  retombe sur l'ordre de recherche d'origine (`assets/fonts`, puis `res/font`,
  puis police du système).

### Page 2 — Photos (images de fond)

Cinq pages de l'appli ont un fond personnalisable (`ui/BackgroundStore.kt`,
énumération `BackgroundSlot`) :

| Emplacement | Image par défaut | Écran concerné |
|---|---|---|
| Page de la clé API | `raw/bg_key_page.jpg` | `ConfigureKeyScreen` |
| Page Réglages | `raw/bg_settings.jpg` | `SettingsScreen` |
| Nouvelle histoire (carrousel) | `drawable/new_story_bg` (public « garçons » ou pas encore de choix), `drawable/new_story_bg_filles` ou `drawable/new_story_bg_duo` selon le public visé (voir `AudiencePrefs`) | `StorySelectorScreen` (dernière page) |
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
- Le fond de l'écran de jeu est l'image de l'histoire en cours ; il ne se
  règle pas ici.

Pour **rendre le fond d'une autre page personnalisable** : ajouter une entrée
à `BackgroundSlot` (clé, libellé, image par défaut), puis remplacer le
`painterResource(...)` du fond de cette page par
`rememberBackgroundPainter(BackgroundSlot.NOUVELLE_ENTREE)`. La page Photos
affiche automatiquement toutes les entrées.

### Page 3 — Icône de l'application

Grille de 3 × 3 : **9 icônes visibles** à la fois. Il existe 21 icônes au
total (`AppIcon` dans `ui/AppIconStore.kt`) :

- l'icône d'origine et les icônes 7 et 8, **communes** (toujours proposées) ;
- les icônes 1 à 6, chacune déclinée en 3 versions — **filles, garçons, duo**
  (les 2) —, dont seule la version correspondant au public visé
  (`AudiencePrefs.audience`) est proposée. `iconsFor(audience)` construit la
  liste ; sans choix, elle retombe sur « duo ».

Un clic sur une icône l'applique ; l'icône en cours est entourée de blanc
avec une pastille « ✓ ».

**Comment ça marche** : Android ne permet pas de remplacer l'icône d'une
appli par une image arbitraire une fois installée. On déclare donc dans le
manifeste **un `<activity-alias>` par icône** (tous pointant sur
`MainActivity`, chacun avec son propre `android:icon`) ; un seul est activé à
la fois et c'est lui que le lanceur affiche. `AppIconStore.current()` /
`apply()` active l'alias choisi puis désactive les autres avec
`PackageManager.setComponentEnabledSetting(…, DONT_KILL_APP)` : l'appli ne
redémarre pas.

- L'état des alias est conservé par Android (pas de fichier à nous).
- Selon le lanceur du téléphone, la nouvelle icône peut mettre quelques
  secondes à apparaître sur l'écran d'accueil.
- Si un alias manque dans le manifeste, le clic affiche un message d'erreur
  au lieu de planter.

Pour **ajouter une icône** : ajouter ses ressources (icône de lanceur et
miniature `icon_preview_…`), un `<activity-alias android:name=".NomAlias"
android:enabled="false" …>` dans le manifeste, et une entrée dans
l'énumération `AppIcon` (avec son public, ou `null` si elle est commune).

### Page 4 — Sons et effets (`ui/SoundPrefs.kt`, `ui/MusicPlayer.kt`)

Trois cartes, mémorisées dans les `SharedPreferences` `app_sound_prefs`
(`screens/SettingsSoundCards.kt`) :

- **Bruit des dés** : curseur de volume (0 à 100 %) et interrupteur pour le
  couper ; le volume choisi est conservé quand le son est coupé. Le son est
  `res/raw/dice_roll.<extension>` (mp3, wav ou ogg), joué par un `SoundPool`
  dans `MainGameScreen` et `ClassicDiceScreen` au volume
  `SoundPrefs.effectiveVolume`. `DiceSoundPlayer` (dans `SoundPrefs.kt`,
  `MediaPlayer`) ne sert qu'au bouton d'essai de cette page.
- **Musique** : interrupteur, curseur de volume (50 % par défaut, plus discret
  que les dés) et **liste des morceaux** : on peut en écouter un (aperçu, même
  si la musique est coupée) et cocher / décocher ceux qui participent au tirage
  au sort. Les morceaux sont découverts tout seuls : tout fichier
  `res/raw/music_*.mp3` (ou `.ogg`) entre dans la liste, sans rien déclarer
  dans le code.

- **Effets** : trois interrupteurs — **vibration** quand la jauge de menace
  explose, **animation d'explosion** de la menace (`ThreatExplosion.kt`) et
  **animation des totems** (`TotemUseEffect.kt` : l'image du totem, ou son
  emoji, apparaît petite au centre de l'écran, grossit avec un léger rebond dans
  un halo doré à rayons, puis s'efface quand on utilise un totem). Deux boutons
  « Essayer » montrent l'effet même s'il est désactivé. Valeurs par défaut :
  tout activé.

Comportement de la musique (`MusicPlayer`, `MusicHost` posé dans
`AppNavigation`) :

- un morceau tiré au hasard démarre au lancement de l'appli ; à la fin d'un
  morceau, un autre est tiré (jamais le même deux fois de suite s'il y en a
  plusieurs, et en boucle s'il n'y en a qu'un) ;
- elle **continue en entrant dans une histoire** (rien ne la coupe
  automatiquement) ; l'en-tête de l'écran de jeu
  permet de la couper, la relancer (« Lancer une musique » réactive la
  musique si elle était coupée dans les Réglages) ou changer de morceau ;
- quand l'appli passe en arrière-plan, elle continue 3 minutes
  (`BACKGROUND_GRACE_MS`, le temps d'aller copier une clé API ailleurs), puis
  se met en pause ; elle reprend au retour dans l'appli.

### Page 5 — Profil (`ui/AudiencePrefs.kt`)

Le **public visé** (filles / garçons / les 2), choisi une première fois par
`AudienceChoiceScreen` puis modifiable ici (`ProfileCard`). Il détermine les
icônes proposées dans la page Icône et le fond par défaut de la page
« Nouvelle histoire » du carrousel. Mémorisé dans les `SharedPreferences`
`app_audience_prefs`.

## Aides intégrées (`ui/HelpContent.kt`)

Un petit bouton rond (`HelpButton`, icône `res/drawable/ic_help.png`) ouvre
une fenêtre d'explication **propre à l'écran où il est posé** : chaque écran
lui passe son propre titre et son propre texte, pris dans l'objet
`HelpTexts`. Il n'y a donc pas de texte d'aide commun.

| Écran | Titre | Texte (`HelpTexts`) |
|---|---|---|
| `ConfigureKeyScreen` | Comment obtenir une clé API Mistral ? | `API_KEY_TITLE` / `API_KEY` |
| `CreateStoryScreen` | Créer une nouvelle histoire | `CREATE_STORY_TITLE` / `CREATE_STORY` |
| `MainGameScreen` (en-tête, en haut à droite) | Comment fonctionne le jeu ? | `GAME_MECHANICS_TITLE` / `GAME_MECHANICS` |

`GAME_MECHANICS` décrit les deux dés (faces, combinaisons 1 / 6), les valeurs
autorisées, la menace (seuil de 10, 8 ou 6 points selon le palier, vibration et
explosion), les totems (15 points, « Utiliser », animation, Second Souffle,
ajout de totems), les quêtes, l'historique, la narration (boutons d'actions,
résumé automatique), le mode sans clé, le journal, la musique et les Réglages.
`CREATE_STORY` décrit aussi l'âge du plus jeune joueur et les trois paliers. Les
seuils (10 / 8 / 6 pour la menace, 15 pour les totems) et les règles viennent
de `AgeTier.kt`, `DiceSessionModels.kt` et `DiceSession.kt` : **si on les change,
mettre les textes d'aide à jour à la main**.

Pour **ajouter l'aide d'un nouvel écran** : ajouter un couple `XXX_TITLE` /
`XXX` dans `HelpTexts`, puis poser
`HelpButton(title = HelpTexts.XXX_TITLE, text = HelpTexts.XXX)` à l'endroit
voulu de l'écran.

## Fichiers stockés sur l'appareil

| Fichier / dossier | Contenu |
|---|---|
| `app_config.json` | modèle choisi et clé Mistral **chiffrée** (Android Keystore, champ `mistral_api_key_enc`), communs à toutes les histoires. Exclu des sauvegardes Android (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`) : après une restauration sur un autre téléphone, la clé est à ressaisir. Une ancienne clé en clair est chiffrée automatiquement au premier lancement. |
| `dice_state_<slug>.json` | la partie de chaque histoire (dont la longueur `story_length`, l'état des quêtes secondaires, les compteurs du bilan `total_rolls` / `totems_used` et l'indicateur de fin d'histoire) |
| `custom_stories.json`, `custom_story_bg/` | histoires personnalisées (dont `player_age` et `protagonist_names`) et leurs images de fond |
| `totem_images/` | images des totems ajoutés |
| `classic_dice_state.json` | historique de la page « Des classiques » |
| `backgrounds/<emplacement>.jpg` | fonds de pages personnalisés (Réglages > Photos) |
| `SharedPreferences` `app_font_prefs` | polices, couleurs et tailles choisies (titres, texte courant, réponses de l'IA) |
| `SharedPreferences` `app_sound_prefs` | volumes et coupures (dés, musique), vibration, animations d'explosion et de totem, morceaux décochés |
| `SharedPreferences` `app_audience_prefs` | public visé (filles / garçons / les 2) |

**Écriture sécurisée** (`SafeFile.kt`) : les sauvegardes de parties et la liste
des histoires sont écrites dans `F.tmp` (avec `fsync`), puis renommées en `F` :
le fichier est donc toujours soit l'ancienne version complète, soit la nouvelle,
même si l'appli est tuée en pleine écriture. Avant de remplacer `F`, s'il est
valide, il est copié en `F.bak`. À la lecture, si `F` est illisible on essaie
`F.bak` ; si les deux sont illisibles, `F` est mis de côté en `F.corrupt` (jamais
écrasé) et la lecture échoue proprement. `F.bak` n'est consulté **que** si `F`
existe mais est illisible : un fichier volontairement supprimé (réinitialisation,
suppression d'une histoire) ne ressuscite pas.

Désinstaller l'appli efface tout cela. L'icône active est mémorisée par
Android lui-même (état des alias).

## Structure du projet

Les chemins Kotlin ci-dessous sont déduits des noms de packages.

```
DesDeAventure/
├── .github/workflows/
│   ├── build-apk.yml                 # build debug + tests + lint + vérification release (R8)
│   ├── release.yml                   # « Release signée » : APK signé + bundle .aab (manuel)
│   ├── generer-wrapper.yml           # crée gradlew et gradle/wrapper (manuel)
│   ├── nettoyer-runs.yml             # supprime les anciens runs (manuel)
│   └── nettoyer-artefacts.yml        # supprime les anciens artefacts (manuel)
├── .github/dependabot.yml            # propositions de mises à jour
├── gradle/libs.versions.toml         # versions des bibliothèques
├── build.gradle                      # plugins Android / Kotlin (racine)
├── settings.gradle
├── gradle.properties
├── .gitignore
└── app/
    ├── build.gradle                  # config Android, Compose, signature release, tests
    ├── lint.xml                      # réglages du lint
    ├── proguard-rules.pro            # règles R8 du build release (voir « Construire l'APK »)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   # permissions INTERNET et VIBRATE + alias d'icônes (voir plus bas)
        │   ├── assets/fonts/         # polices proposées dans Réglages (Bangers, Nunito, …)
        │   ├── res/                  # icônes, images de fond, sons (voir « Ressources »)
        │   └── java/com/aventure/desdice/
        │       ├── MainActivity.kt       # navigation (AppNavigation)
        │       ├── MainGameScreen.kt     # écran de jeu, palier de contenu, jauge de menace + briques d'UI communes (ComicButton, Section…)
        │       ├── AiPanel.kt            # panneau de narration IA (conversation, streaming, boutons d'actions, bilan, réessayer, réinitialisation)
        │       ├── DicePanel.kt          # DiceResultCard : lancers des dés, résultat, réessai
        │       ├── GameDice.kt           # face du dé de réussite, badges et fiches de totems
        │       ├── GameDialogs.kt        # SymbolPicker et AllowedValuesDialog
        │       ├── TotemsAndQuests.kt    # jauges de totems (TotemGaugesRow) et quêtes secondaires (SideQuestsList)
        │       ├── GameExtras.kt         # NarratorNoteBox, ContinueSection, JournalSection
        │       ├── BilanDialog.kt        # bilan de fin d'aventure
        │       ├── ThreatExplosion.kt    # explosion de la jauge de menace (animation + vibration)
        │       ├── TotemUseEffect.kt     # animation « un totem est utilisé »
        │       ├── Haptics.kt            # vibration (dangerBuzz)
        │       ├── AgeTier.kt            # paliers de contenu (AgeTier, PlayerAge) et seuil de menace
        │       ├── DiceSession.kt        # moteur de jeu (dés, jauges, menace, quêtes, bilan, session, mécanique du ❓ selon la longueur)
        │       ├── DiceSessionModels.kt  # constantes (seuils, faces, combos) et classes de données (RollRecord, SideQuest, AiMessage…)
        │       ├── StoryRegistry.kt      # registre des histoires personnalisées
        │       ├── GameEngine.kt         # porte d'entrée unique du moteur Kotlin
        │       ├── ClassicDiceEngine.kt  # dés classiques (« Des classiques »), délégué par GameEngine
        │       ├── MistralConfig.kt      # clé API (chiffrée) et modèle Mistral, délégué par GameEngine
        │       ├── SecretCipher.kt       # chiffrement de la clé (Android Keystore, AES-256-GCM)
        │       ├── SafeFile.kt           # écriture atomique + copie .bak + quarantaine .corrupt
        │       ├── StoryTransfer.kt      # exports (journal, identité, progression) et import, délégué par GameEngine
        │       ├── PromptBuilder.kt      # textes envoyés à l'IA (mécaniques, cadre de contenu, rappels, balises, prompt complet, kickoff)
        │       ├── AiNarrator.kt         # appels à Mistral : narration, streaming, nettoyage des balises, digest du journal
        │       ├── SessionJson.kt        # état de session envoyé à Compose (sessionToJson)
        │       ├── ImageUtils.kt         # redimensionnement des fonds et des totems
        │       ├── MistralClient.kt      # client API Mistral (streaming SSE, relances, réessais)
        │       ├── JournalExporter.kt    # export PDF du journal (PdfDocument natif)
        │       ├── SpeechManager.kt      # synthèse vocale (lecture des réponses de l'IA)
        │       ├── SpeechText.kt         # nettoyage du texte avant la lecture vocale
        │       ├── model/Story.kt
        │       ├── viewmodel/GameViewModel.kt  # utilise GameEngine, expose sessionState et streamingReply
        │       ├── screens/
        │       │   ├── AudienceChoiceScreen.kt   # tout premier écran : public visé
        │       │   ├── TotemManagementDialog.kt  # gestion des totems ajoutés en cours de partie
        │       │   ├── ClassicDiceScreen.kt      # page "Des classiques"
        │       │   ├── ClassicDiceWidgets.kt     # composants de la page « Des classiques »
        │       │   ├── DiceSpin3D.kt, DieFaces.kt  # dés 3D animés et dessin des faces
        │       │   ├── ConfigureKeyScreen.kt     # saisie de la clé API Mistral (+ engrenage vers Réglages)
        │       │   ├── CreateStoryScreen.kt      # création d'une histoire personnalisée (héros, longueur, âge…)
        │       │   ├── SettingsScreen.kt         # Réglages : squelette, pages, SettingsGearButton
        │       │   ├── SettingsFontsPage.kt      # page Polices
        │       │   ├── SettingsMediaCards.kt     # pages Photos et Icône
        │       │   ├── SettingsSoundCards.kt     # page Sons et effets
        │       │   ├── SettingsStyle.kt          # couleurs communes des Réglages
        │       │   └── StorySelectorScreen.kt    # carrousel des histoires
        │       └── ui/
        │           ├── AppFonts.kt        # chargement des polices (rememberAppFonts, rememberAppTextStyles)
        │           ├── FontPrefs.kt       # polices, couleurs, tailles choisies + liste des polices de assets/fonts
        │           ├── AudiencePrefs.kt   # public visé (Audience, AudiencePrefs)
        │           ├── ClassicDicePrefs.kt # dé de réussite choisi dans « Des classiques »
        │           ├── SoundPrefs.kt      # volumes / coupures, vibration, animations, morceaux cochés, DiceSoundPlayer
        │           ├── MusicPlayer.kt     # musique de fond (MusicPlayer, MusicHost)
        │           ├── HelpContent.kt     # boutons d'aide (HelpButton) et textes (HelpTexts)
        │           ├── BackgroundStore.kt # fonds personnalisés (BackgroundSlot, rememberBackgroundPainter)
        │           └── AppIconStore.kt    # icône de l'appli (AppIcon, activation des alias)
        └── test/                          # tests unitaires et tests d'écrans (voir « Tests et qualité »)
```

### Ressources attendues dans `res/`

- **Fonds par défaut** : `drawable/bg_classic_dice.jpg` (« Des classiques »), `drawable/bg_create_story.jpg` (création d'histoire), `drawable/new_story_bg`, `new_story_bg_filles` et `new_story_bg_duo` (dernière page du carrousel, selon le public visé), `raw/bg_key_page.jpg` (clé API), `raw/bg_settings.jpg` (Réglages).
- **Icônes d'interface** : `drawable/classic_dice_icon` (icône du carrousel), `drawable/ic_settings.png` (engrenage), `drawable/ic_help.png` (bouton d'aide, image du personnage qui réfléchit).
- **Sons** : `raw/dice_roll.ogg` (bruit des dés ; `mp3` et `wav` conviennent aussi) et, pour la musique de fond, tout fichier `raw/music_<nom>.mp3` ou `.ogg` (détecté automatiquement ; sans fichier, la musique est simplement absente).
- **Polices** : Bangers et Nunito (dont `Nunito-Bold.ttf`) dans `assets/fonts/`, plus toute police à proposer dans Réglages.
- **Icône d'origine** :
  - `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` et `ic_launcher_round.png` (Android avant la version 8) ;
  - `mipmap-anydpi-v26/ic_launcher.xml` et `ic_launcher_round.xml` (icône adaptative, Android 8 et plus), qui utilisent `drawable-nodpi/ic_original_bg.jpg` et `ic_original_fg.png`.
- **Icônes alternatives** (icônes 1 à 6 en trois versions filles / garçons / duo, plus les icônes 7 et 8 communes, soit 20 icônes en plus de l'originale) : pour chacune, une icône de lanceur (`mipmap-…`, référencée par l'`android:icon` de son alias dans le manifeste) et une miniature affichée dans Réglages : `drawable-nodpi/icon_preview_N_filles.png`, `icon_preview_N_garcons.png`, `icon_preview_N_duo.png` (N de 1 à 6), `icon_preview_7.png` et `icon_preview_8.png`.
- `values/strings.xml` (`app_name`).

### Le manifeste (`AndroidManifest.xml`)

- Permissions : `INTERNET` (API Mistral) et `VIBRATE` (vibration quand la
  jauge de menace explose, accordée automatiquement à l'installation).
- Sauvegardes Android : `app_config.json` (clé API chiffrée) est exclu de la
  sauvegarde cloud et du transfert vers un nouveau téléphone
  (`res/xml/data_extraction_rules.xml` pour Android 12 et plus,
  `res/xml/backup_rules.xml` avant) ; parties et histoires, elles, sont
  sauvegardées.
- `MainActivity` **n'a plus** de filtre `MAIN` / `LAUNCHER` (sinon l'appli
  apparaîtrait en double dans le lanceur) ; elle reste `exported="true"`.
- Vingt et un `<activity-alias>` portent ce filtre : `.IconOriginal` (activé
  par défaut, `@mipmap/ic_launcher` + `@mipmap/ic_launcher_round`) et vingt
  alias désactivés par défaut : `.Icon1Filles`, `.Icon1Garcons`, `.Icon1Duo`
  … `.Icon6Filles`, `.Icon6Garcons`, `.Icon6Duo`, puis `.Icon7` et `.Icon8`.
- Les noms d'alias doivent rester alignés avec l'énumération `AppIcon`
  (`"IconOriginal"`, `"Icon1Filles"`… dans `AppIconStore.kt`) : le code
  retrouve les alias par `com.aventure.desdice.<nom>`.

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

Le workflow « Build APK » enchaîne : APK debug (publié tout de suite, même si
la suite échoue), **tests unitaires** (`testDebugUnitTest`), **lint**
(`lintDebug`, rapport seulement, ne bloque jamais), vérification du **build
release non signé avec R8** (`assembleRelease`), puis un résumé tests + lint
sur la page du workflow.

### Release signée (`.github/workflows/release.yml`)

Workflow manuel « Release signée » (Actions > Run workflow, avec le nom de
version à afficher) : produit un **APK signé** (« Dés d'Aventure (release) »),
un **bundle `.aab`** pour le Play Store (« Bundle .aab ») et le **mapping R8**,
après vérification de la signature (`apksigner verify`). Quatre secrets de
dépôt sont à créer une fois (Settings > Secrets and variables > Actions) :
`KEYSTORE_BASE64` (fichier `.jks` encodé en base64), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Le `versionCode` est le numéro du run (il
augmente donc à chaque release, condition pour qu'Android accepte une mise à
jour) ; le `versionName` est celui saisi. Sans ces variables (build debug, pull
request, poste local), le build release reste possible mais **non signé**.

Le build release active **R8** (`minifyEnabled true`) ; `shrinkResources` reste
volontairement désactivé car l'appli cherche des polices et des musiques par
leur nom. `app/proguard-rules.pro` garde `R$raw` (la liste des morceaux
`music_*` est lue par réflexion) ; `app/lint.xml` ne contient que des faux
positifs connus.

### Autres workflows manuels

- **Nettoyer les anciens runs** et **Nettoyer les artefacts** : gardent les N
  derniers runs du build (20 par défaut) ; l'option « simulation » affiche ce
  qui serait supprimé sans rien effacer.

## Tests et qualité

Les tests sont dans `app/src/test/` et tournent sur la machine de build, sans
émulateur (`gradle testDebugUnitTest`, ou automatiquement à chaque push) :

- **Moteur** : `GameEngineTest`, `DiceSessionTest`, `ClassicDiceEngineTest`,
  `StoryRegistryTest`, `StoryTransferTest`, `SessionJsonTest`, `PlayerAgeTest`
  (paliers et seuils de menace), `BilanDialogTest` ;
- **IA** : `PromptBuilderTest`, `PromptAndExportTest`, `MistralClientStreamTest`,
  `MistralClientRetryTest`, `MistralClientErrorsTest`, `StreamingDisplayTextTest`,
  `SpeechTextTest` ;
- **Fichiers et sécurité** : `SafeFileTest`, `AppConfigSecurityTest` ;
- **Interface** : `ui/TextStyleChoiceTest` et des tests d'écrans Compose avec
  Robolectric (`screens/AudienceChoiceScreenTest`, `screens/SettingsScreenTest`).

Les paramètres Robolectric sont dans `src/test/resources/robolectric.properties`.

### Versions utilisées

| Élément | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 (Kotlin intégré) |
| Kotlin | 2.4.10 |
| Plugin du compilateur Compose | 2.4.10 (même version que Kotlin) |
| Compose BOM | 2026.09.00 |
| Gradle (dans le workflow) | 9.6.0 |
| JDK | 17 |
| minSdk / targetSdk / compileSdk | 24 / 37 / 37 |
| ABI | arm64-v8a, x86_64 |

Les versions des bibliothèques sont dans `gradle/libs.versions.toml` (celles d'AGP et de
Kotlin restent dans le `build.gradle` racine). Dependabot (`.github/dependabot.yml`) propose
les mises à jour chaque mois sous forme de pull requests, testées par le workflow Build APK.

**Gradle wrapper (optionnel)** : le workflow « Générer le Gradle wrapper » (lancement manuel)
crée `gradlew` et `gradle/wrapper/` et les enregistre dans le dépôt. Tant qu'il n'a pas été
lancé, le workflow Build APK installe lui-même Gradle 9.6.0 ; ensuite il utilise le wrapper,
et la version de Gradle est celle de `gradle/wrapper/gradle-wrapper.properties`.

### Identifiants

- `namespace` : `com.aventure.desdice` (nom des packages, et des alias d'icônes du manifeste).
- `applicationId` : `com.aventure.desdice.histoiresmultiples`. C'est cet identifiant qu'Android utilise pour décider si une appli est « la même » (mise à jour) ou une autre (installée à côté). Il diffère de celui de l'ancienne version WebView (`com.aventure.desdice.auto`) : les deux peuvent cohabiter, mais leurs sauvegardes ne se transmettent pas.

## En cas d'échec du build

1. Ouvrir l'étape en erreur dans l'onglet Actions et lire le message exact.
2. Une nouvelle version de l'Android Gradle Plugin peut exiger un Gradle plus récent : vérifier la version indiquée dans `build-apk.yml` (ou dans `gradle-wrapper.properties` si le wrapper existe). Le plugin Kotlin et le plugin du compilateur Compose doivent toujours avoir la même version.
3. Ne pas retirer `androidx.appcompat` : le thème du manifeste (`Theme.AppCompat.Light.NoActionBar`) en dépend.
4. `Unresolved reference: toBitmap` dans `SettingsScreen.kt` : ajouter la dépendance `androidx.core:core-ktx` (elle sert à dessiner la miniature de l'icône d'origine).
5. `Unresolved reference: R.drawable.icon_preview_…`, `R.drawable.ic_help`, `R.raw.dice_roll` ou `R.raw.bg_settings` : une ressource d'icône, de fond ou de son manque ou est mal nommée (noms en minuscules, sans espace ni tiret). Un fichier `music_…` manquant n'est pas une erreur : la musique est juste absente.
6. Un test échoue : le workflow devient rouge mais l'APK debug reste téléchargeable (il est publié avant les tests). Le résumé de la page du workflow liste les tests en échec.
7. Le build release (R8) échoue alors que le debug passe : une classe lue par réflexion a besoin d'une règle dans `app/proguard-rules.pro`.
8. Un clic sur une icône affiche « les alias du manifeste ne sont pas en place » : le `AndroidManifest.xml` n'est pas celui qui contient les `<activity-alias>`.

## Ouvrir le projet dans Android Studio (optionnel)

Ouvrir le dossier avec *Open*. Sans wrapper Gradle dans le dépôt, Android Studio utilise le
Gradle installé (lancer d'abord le workflow « Générer le Gradle wrapper », puis récupérer
le dépôt, pour avoir la même version qu'en CI).
