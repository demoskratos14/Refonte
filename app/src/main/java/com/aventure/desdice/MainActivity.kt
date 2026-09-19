package com.aventure.desdice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        Python.getInstance().getModule("game_api").callAttr("init_app_dir")

        speechManager = SpeechManager(this)

        setContent {
            DesDiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GameUI(speechManager)
                }
            }
        }
    }
}

@Composable
fun GameUI(speechManager: SpeechManager) {
    var gameState by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val result = Python.getInstance()
    .getModule("game_api")
    .callAttr("index")
    .toString()
            gameState = result
        } catch (e: Exception) {
            gameState = "Error: ${e.message}"
        }
    }

    Text(text = gameState)
}

@Preview(showBackground = true)
@Composable
fun GameUIPreview() {
    DesDiceTheme {
        GameUI(speechManager = SpeechManager(LocalContext.current))
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