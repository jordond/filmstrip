package dev.jordond.filmstrip.sample

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
    setContent { App(SampleGraph.state()) }
  }
}

// The state outlives the activity, so an export keeps running across a rotation.
private object SampleGraph {
  private var state: SampleAppState? = null

  fun state(): SampleAppState =
    state ?: createSampleAppState().also { state = it }
}
