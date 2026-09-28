package com.aventure.desdice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.R

/**
 * Petit bouton d'aide rond (icône fournie par l'utilisateur, res/drawable/ic_help.png) qui
 * ouvre une boîte de dialogue avec un texte d'explication, propre à l'écran où il est posé.
 * Réutilisé sur 3 écrans : configuration de la clé API, création d'une histoire, et pages
 * d'une histoire en cours (voir HelpTexts pour le contenu de chacun).
 *
 * IMPORTANT : nécessite le fichier ic_help.png dans app/src/main/res/drawable/ (fourni à
 * part -- l'icône "personnage qui réfléchit" donnée par l'utilisateur).
 */
@Composable
fun HelpButton(title: String, text: String, size: androidx.compose.ui.unit.Dp = 30.dp) {
    var show by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Image(
        painter = painterResource(R.drawable.ic_help),
        contentDescription = "Aide : $title",
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { show = true }
            )
    )

    if (show) {
        HelpDialog(title = title, text = text, onDismiss = { show = false })
    }
}

@Composable
private fun HelpDialog(title: String, text: String, onDismiss: () -> Unit) {
    val fonts = rememberAppFonts()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, fontFamily = fonts.display, fontSize = 22.sp)
        },
        text = {
            Text(
                text = text,
                fontFamily = fonts.body,
                fontSize = 15.sp,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Fermer", fontFamily = fonts.bodyBold)
            }
        }
    )
}

/** Les 3 textes d'aide, un par écran. À adapter/compléter au fil de l'eau. */
object HelpTexts {

    val API_KEY_TITLE = "Comment obtenir une clé API Mistral ?"
    val API_KEY = """
L'IA qui raconte l'histoire n'est pas fournie par cette appli : elle vient de Mistral AI, une entreprise française. Pour l'utiliser, il te faut une clé API personnelle -- gratuite à obtenir.

MARCHE À SUIVRE

1. Va sur console.mistral.ai depuis un navigateur (ordinateur ou téléphone).
2. Crée un compte : une adresse email et un mot de passe suffisent (ou connecte-toi avec un compte Google/Microsoft existant).
3. Une fois connecté, cherche dans le menu la section "API Keys" (parfois affichée "Clés API").
4. Clique sur "Create new key" ("Créer une nouvelle clé"), donne-lui un nom si on te le demande -- par exemple "Histoires Multiples".
5. La clé s'affiche UNE SEULE FOIS : copie-la tout de suite (un bouton "Copier" est en général juste à côté).
6. Reviens dans cette appli et colle la clé dans l'écran de configuration.

EST-CE GRATUIT ?

Mistral propose un palier gratuit ("Experiment") qui permet d'utiliser l'API sans payer, avec une limite d'usage par mois. Pour jouer de temps en temps, ce palier gratuit suffit largement : au moment où ce texte a été écrit, il correspond à environ 8,50 € de consommation gratuite par mois avant qu'une facturation ne s'enclenche.

Attention : Mistral ne publie pas ce chiffre officiellement, et ses conditions peuvent changer avec le temps. Pour connaître le montant et la limite réellement en vigueur sur ton compte, va dans le panneau d'administration (console.mistral.ai), section "Limits" ou "Facturation".

Aucune carte bancaire n'est nécessaire pour créer un compte et une première clé. Mistral peut en demander une plus tard, seulement si tu veux dépasser le palier gratuit.

À RETENIR

- Cette clé est personnelle : ne la partage avec personne, un peu comme un mot de passe.
- Si tu la perds ou veux la changer, tu peux simplement en créer une nouvelle sur le même site, puis la remplacer ici.
""".trimIndent()

    val CREATE_STORY_TITLE = "Créer une nouvelle histoire"
    val CREATE_STORY = """
Cet écran sert à poser les bases de la prochaine histoire, avant que l'IA ne commence à la raconter. Plus les informations données ici sont claires, plus l'histoire qui en sortira leur ressemblera.

LES CHAMPS DE L'ÉCRAN

- Importer une identité exportée (optionnel) : reprend d'un coup tout le contenu d'une identité déjà exportée depuis une autre histoire (titre, univers, totem...), plutôt que de tout ressaisir.
- Titre et sous-titre : servent surtout à retrouver l'histoire dans la liste par la suite.
- Description de l'univers : c'est le texte envoyé à l'IA narratrice pour planter le décor -- le monde dans lequel l'aventure se déroule (une forêt magique, une école de sorciers, l'espace, un royaume de chevaliers...). Plus c'est concret, plus l'IA a de quoi construire des lieux et des personnages cohérents.
- Héros (optionnel) : un ou plusieurs prénoms, avec « + Ajouter un héros » pour en mettre davantage (et ✕ pour en retirer un). À laisser vide si les joueurs préfèrent les choisir eux-mêmes une fois l'histoire commencée. Avec plusieurs héros, l'IA vouvoie le groupe quand il agit ensemble, et tutoie chacun par son prénom quand l'un d'eux agit ou choisit seul -- chaque héros a alors ses propres choix et ses propres lancers de dés.
- Longueur de l'histoire : Courte (une dizaine d'échanges), Moyenne (20 à 30), ou Longue (racontée en chapitres, chacun avec une vraie fin, sans limite totale -- l'IA demande avant de commencer le chapitre suivant).
- Objectif moral (optionnel) : une valeur ou une notion que l'aventure met en avant à travers ses situations -- patience, fair-play, partage, courage face à la peur, entraide... L'IA la fait vivre par le récit, jamais par un discours moralisateur direct.
- Image de fond (optionnel) : illustre l'écran de cette histoire.
- Totem de départ : le nom du tout premier totem du héros, ses pouvoirs (séparés par des virgules), une capacité spéciale (optionnel), et une image (optionnel).

CONSEILS

- Une ou deux phrases suffisent pour l'univers ou l'objectif moral : inutile d'écrire un roman, l'IA invente et complète le reste au fil de l'histoire.
- Rester volontairement vague sur certains points garde plus de surprises.
- Une fois l'histoire créée, mieux vaut relire ces informations avant de valider : elles ne se modifient pas aussi facilement par la suite.
""".trimIndent()

    val GAME_MECHANICS_TITLE = "Comment fonctionne le jeu ?"
    val GAME_MECHANICS = """
Chaque tour de jeu repose sur deux dés et une conversation avec une IA narratrice.

LE DÉ DE RÉUSSITE

Il indique si une action réussit (1 à 6). Chaque point du dé porte le symbole d'un totem plutôt qu'un simple point noir : le mode de tirage (un seul symbole, un tirage aléatoire par point, ou un mélange) se choisit dans les réglages du dé.

LE DÉ DU DESTIN

Il tombe sur l'un de 6 symboles et pimente la scène en cours. Le symbole ❓ ("Question") se comporte différemment selon la longueur choisie à la création de l'histoire :
- Histoire COURTE : il déclenche un événement soudain et inattendu, tout de suite dans la scène.
- Histoire MOYENNE : il ouvre une quête secondaire qui se construit petit à petit, en parallèle de l'histoire principale.
- Histoire LONGUE : il met de côté une quête secondaire courte, qui se lance automatiquement à la fin du chapitre en cours.

Certaines combinaisons des deux dés (un 1 ou un 6 avec le ❓, par exemple) ont un effet un peu spécial, décrit au moment du lancer.

LA MENACE ET LES QUÊTES

Une jauge de menace peut monter au fil de l'histoire. Les quêtes secondaires, une fois lancées, apparaissent dans leur propre liste jusqu'à ce qu'elles soient marquées terminées.

LES TOTEMS

Chaque totem a sa fiche (pouvoirs, capacité spéciale), consultable en touchant son badge sous le titre. De nouveaux totems peuvent s'ajouter en cours d'histoire.

LA NARRATION

L'IA propose parfois des actions sous forme de boutons à toucher directement ; il reste toujours possible d'écrire sa propre réponse à la place, dans le champ de saisie. Le bouton "Démarrer l'histoire" (ou "Continuer l'aventure ailleurs" sans clé API) lance ou relance le tout premier message envoyé à l'IA.

En arrière-plan, un résumé du chapitre s'écrit automatiquement tous les 6 échanges environ, et une dernière fois quand l'histoire se termine, pour que l'IA garde le fil même sur une très longue histoire.

LES RÉGLAGES

Le bouton ⚙️ des réglages permet de changer les polices, les couleurs et tailles de texte, le volume des dés et de la musique (avec le choix des morceaux tirés au sort), et l'icône de l'appli.
""".trimIndent()
}
