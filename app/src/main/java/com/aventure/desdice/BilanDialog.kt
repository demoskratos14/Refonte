// BilanDialog.kt
// Écran de fin d'histoire : le bilan de l'aventure (nombre de lancers, totems utilisés,
// quêtes accomplies). Ouvert depuis le bouton « 🏆 Voir le bilan de l'aventure » qui apparaît
// dans le panneau de narration (AiPanel) quand l'IA a conclu l'histoire -- jamais d'office, pour
// ne pas cacher le texte de conclusion que le joueur est en train de lire.
//
// Les chiffres viennent de la session (DiceSession : totalRolls, totemsUsed, completedQuestCount)
// via la clé "bilan" de SessionJson. Le bilan n'est proposé que pour les histoires moyennes et,
// après une défaite définitive, longues (voir DiceSession.bilanAvailable).

package com.aventure.desdice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ui.rememberAppFonts

/** « 0 lancer », « 1 lancer », « 2 lancers » : en français, zéro et un sont au singulier. */
internal fun bilanCountText(count: Int, singular: String, plural: String): String =
    "$count " + if (count > 1) plural else singular

@Composable
internal fun BilanDialog(
    storyTitle: String,
    rolls: Int,
    totemsUsed: Int,
    questsDone: Int,
    onDismiss: () -> Unit
) {
    val fonts = rememberAppFonts()
    val rowShape = RoundedCornerShape(12.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = {
            Text(
                "🏆 Bilan de l'aventure",
                style = TextStyle(fontFamily = fonts.display, fontSize = 24.sp, color = Ink)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (storyTitle.isNotBlank()) {
                    Text(
                        storyTitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFamily = fonts.bodyBold, fontSize = 17.sp, color = Ink)
                    )
                }
                BilanRow("🎲", bilanCountText(rolls, "lancer de dé", "lancers de dés"), rowShape, fonts.bodyBold)
                BilanRow("🔮", bilanCountText(totemsUsed, "totem utilisé", "totems utilisés"), rowShape, fonts.bodyBold)
                BilanRow("📜", bilanCountText(questsDone, "quête accomplie", "quêtes accomplies"), rowShape, fonts.bodyBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Ainsi s'achève l'aventure.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = fonts.body, fontSize = 15.sp, color = Ink)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Fermer",
                    style = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, color = Ink)
                )
            }
        }
    )
}

@Composable
private fun BilanRow(
    emoji: String,
    text: String,
    shape: RoundedCornerShape,
    fontFamily: androidx.compose.ui.text.font.FontFamily
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Gold.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(emoji, style = TextStyle(fontSize = 26.sp))
        Text(text, style = TextStyle(fontFamily = fontFamily, fontSize = 18.sp, color = Ink))
    }
}
