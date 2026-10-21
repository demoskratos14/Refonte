package com.aventure.desdice.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aventure.desdice.ThreatExplosion
import com.aventure.desdice.TotemEffect
import com.aventure.desdice.ui.AppFonts
import com.aventure.desdice.ui.DiceSoundPlayer
import com.aventure.desdice.ui.MusicPlayer
import com.aventure.desdice.ui.SoundPrefs
import org.json.JSONObject

// Page « Sons » des réglages : bruit des dés, effets spéciaux (vibration) et
// musique de fond. Voir SettingsScreen.kt.

/** Page « Sons », 2e carte : couper ou baisser la musique de fond, choisir les morceaux tirés au sort. */
@Composable
internal fun MusicCard(fonts: AppFonts) {
    val context = LocalContext.current
    val soundPrefs = remember(context) { SoundPrefs.get(context) }
    val hasTracks = remember { MusicPlayer.hasTracks() }
    val muted = soundPrefs.musicMuted
    val volume = soundPrefs.musicVolume

    SettingsCard(title = "\uD83C\uDFB5 Musique", fonts = fonts) {
        Text(
            text = "Un morceau au hasard se lance à l'ouverture de l'appli et continue de jouer pendant " +
                "les histoires. Dans la page de jeu, sous « Changer d'histoire », tu peux la couper, " +
                "la relancer ou passer à un autre morceau.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        if (!hasTracks) {
            Text(
                text = "Aucun morceau trouvé. Ajoute des fichiers nommés music_quelquechose.mp3 (ou .ogg) " +
                    "dans app/src/main/res/raw.",
                fontSize = 13.sp,
                color = Color(0xFFFFC9C9),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (muted) "Musique coupée" else "Musique activée",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !muted,
                onCheckedChange = {
                    soundPrefs.changeMusicMuted(!it)
                    MusicPlayer.onMusicMutedChanged(context)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SRed,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .height(2.dp)
                .background(SLineColor)
        )

        Text(
            text = "Volume : ${(volume * 100).toInt()} %",
            fontFamily = fonts.bodyBold,
            color = Color.White.copy(alpha = if (muted) 0.5f else 1f),
            style = TextStyle(shadow = STextShadow)
        )
        Slider(
            value = volume,
            onValueChange = {
                soundPrefs.changeMusicVolume(it)
                MusicPlayer.updateVolume(context)
            },
            valueRange = 0f..1f,
            enabled = !muted,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledThumbColor = Color.White.copy(alpha = 0.5f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (hasTracks) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
                    .height(2.dp)
                    .background(SLineColor)
            )
            Text(
                text = "Morceaux tirés au sort",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Décoche ceux que tu ne veux pas entendre : ils ne seront jamais tirés.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(bottom = 10.dp)
            )
            val tracks = remember { MusicPlayer.listTracks() }
            tracks.forEach { track ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    val isThisPlaying = MusicPlayer.isPlaying && MusicPlayer.currentTrackName == track.name
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                            .clickable { MusicPlayer.togglePreview(context, track) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = if (isThisPlaying) "⏸" else "▶", fontSize = 14.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = track.label,
                        fontFamily = fonts.body,
                        color = Color.White,
                        style = TextStyle(shadow = STextShadow),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = soundPrefs.isTrackEnabled(track.name),
                        onCheckedChange = { soundPrefs.setTrackEnabled(track.name, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SRed,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
        if (hasTracks && !muted) {
            Spacer(Modifier.height(6.dp))
            SettingsButton(
                text = if (MusicPlayer.isPlaying) "Autre morceau" else "Lancer la musique",
                onClick = { MusicPlayer.startRandom(context) },
                fonts = fonts,
                secondary = true
            )
        }
    }
}

/** Page « Sons » : effets quand la jauge de menace explose (vibration + animation). */
@Composable
internal fun VibrationCard(fonts: AppFonts) {
    val context = LocalContext.current
    val soundPrefs = remember(context) { SoundPrefs.get(context) }
    val vibration = soundPrefs.vibrationEnabled
    val animation = soundPrefs.explosionAnimationEnabled
    val totemAnimation = soundPrefs.totemAnimationEnabled
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = SRed,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
    )

    SettingsCard(title = "\uD83D\uDCA5 Effets spéciaux", fonts = fonts) {
        Text(
            text = "Quand la jauge de menace explose, le téléphone peut vibrer et une explosion peut " +
                "apparaître à l'écran, au moment où la réponse de l'IA s'affiche. Quand tu utilises " +
                "un totem, son image peut apparaître en grand.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (vibration) "Vibration activée" else "Vibration coupée",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = vibration,
                onCheckedChange = { soundPrefs.changeVibrationEnabled(it) },
                colors = switchColors
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (animation) "Animation activée" else "Animation coupée",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = animation,
                onCheckedChange = { soundPrefs.changeExplosionAnimationEnabled(it) },
                colors = switchColors
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (totemAnimation) "Animation des totems activée" else "Animation des totems coupée",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = totemAnimation,
                onCheckedChange = { soundPrefs.changeTotemAnimationEnabled(it) },
                colors = switchColors
            )
        }
        Spacer(Modifier.height(6.dp))
        SettingsButton(
            text = "Essayer l'explosion",
            onClick = { ThreatExplosion.fire(context, ignoreSettings = true) },
            fonts = fonts,
            secondary = true
        )
        Spacer(Modifier.height(6.dp))
        SettingsButton(
            text = "Essayer le totem",
            onClick = {
                TotemEffect.fire(
                    context,
                    JSONObject().put("label", "Totem").put("emoji", "\uD83E\uDD85"),
                    ignoreSettings = true
                )
            },
            fonts = fonts,
            secondary = true
        )
        Text(
            text = "L'animation est aussi supprimée si les animations sont désactivées dans les réglages d'Android.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.8f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Page « Sons » : couper ou baisser le bruit des dés. */
@Composable
internal fun SoundCard(fonts: AppFonts) {
    val context = LocalContext.current
    val soundPrefs = remember(context) { SoundPrefs.get(context) }
    val muted = soundPrefs.diceMuted
    val volume = soundPrefs.diceVolume

    SettingsCard(title = "\uD83D\uDD0A Bruit des dés", fonts = fonts) {
        Text(
            text = "Coupe le bruit des dés, ou baisse-le pour jouer plus discrètement. " +
                "Le volume choisi est conservé quand tu coupes le son.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = fonts.body,
            style = TextStyle(shadow = STextShadow),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (muted) "Son coupé" else "Son activé",
                fontFamily = fonts.bodyBold,
                color = Color.White,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !muted,
                onCheckedChange = { soundPrefs.setMuted(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SRed,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .height(2.dp)
                .background(SLineColor)
        )

        Text(
            text = "Volume : ${(volume * 100).toInt()} %",
            fontFamily = fonts.bodyBold,
            color = Color.White.copy(alpha = if (muted) 0.5f else 1f),
            style = TextStyle(shadow = STextShadow)
        )
        Slider(
            value = volume,
            onValueChange = { soundPrefs.setVolume(it) },
            valueRange = 0f..1f,
            enabled = !muted,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                disabledThumbColor = Color.White.copy(alpha = 0.5f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        SettingsButton(
            text = "Écouter",
            onClick = { DiceSoundPlayer.play(context) },
            fonts = fonts,
            secondary = true
        )
        if (muted) {
            Text(
                text = "Le son est coupé : rien ne sera joué tant que tu ne le réactives pas.",
                fontSize = 13.sp,
                color = Color(0xFFFFC9C9),
                fontFamily = fonts.body,
                style = TextStyle(shadow = STextShadow),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
