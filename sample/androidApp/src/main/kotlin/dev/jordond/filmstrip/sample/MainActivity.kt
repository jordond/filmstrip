package dev.jordond.filmstrip.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
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
