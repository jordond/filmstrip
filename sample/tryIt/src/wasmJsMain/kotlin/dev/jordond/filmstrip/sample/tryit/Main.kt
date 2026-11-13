package dev.jordond.filmstrip.sample.tryit

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.sample.exportSink
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The app the Try it TV frames, which draws the picture and takes its steps from the page through [TryBridge].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val scope = MainScope()
  val filmstrip = Filmstrip()
  val engine = TryEngine(scope, exporter = { edit, spec -> writeExport(filmstrip, edit, spec, exportSink()) })
  val bridge = TryBridge(engine::apply, engine::onPageHidden, engine::onGesture)
  bridge.start()

  ComposeViewport(document.body!!) {
    TryScreen(engine, filmstrip)
    // Composing needs no WebGL and a frame's callback runs ahead of its draw, so the second frame is what shows the
    // first one drew. An app that cannot draw fails before that, while the page still hears it.
    LaunchedEffect(Unit) {
      withFrameNanos { }
      withFrameNanos { }
      bridge.send(encodeReady())
    }
  }

  scope.launch {
    statusesOf(engine.state).collect { bridge.send(encodeStatus(it)) }
  }

  scope.launch { engine.onCapabilities(filmstrip.capabilities()) }
  engine.load()
}
