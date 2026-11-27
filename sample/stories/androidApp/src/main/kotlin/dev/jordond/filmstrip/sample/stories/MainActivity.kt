package dev.jordond.filmstrip.sample.stories

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import dev.jordond.filmstrip.sample.ui.Tokens

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    val bars = SystemBarStyle.dark(Tokens.ground.toArgb())
    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    super.onCreate(savedInstanceState)
    setContent { App() }
  }
}
