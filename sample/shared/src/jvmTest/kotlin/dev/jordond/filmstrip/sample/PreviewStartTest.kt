package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the editor's hoisted effect does around a pick, checked here since the effect itself only
 * runs inside a composition.
 *
 * It restarts on the same two values every time: the source and whether a probe is still running.
 * This drives both through a pick the way a real one does and counts how many players that opens.
 */
@OptIn(InternalFilmstripApi::class)
class PreviewStartTest {
  @Test
  fun `a probe still in flight never opens a player the next restart immediately replaces`() {
    val resolved = CompletableDeferred<ProbeResult>()
    val filmstrip = Filmstrip {
      addMediaProberFactory { MediaProber { resolved.await() } }
    }
    val recorder = DiagnosticsRecorder()
    val state = SampleAppState(filmstrip, recorder, scope = CoroutineScope(Dispatchers.Unconfined))

    // What the effect's body does on every restart.
    fun onRestart() {
      if (!state.probing) state.startPreview()
    }

    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")
    onRestart()
    assertEquals(0, recorder.events.count { it.label == "player.opened" }, "opened while the probe was still running")

    resolved.complete(ProbeResult.Failure(ExportError.SourceUnreadable("/tmp/clip.mp4", "unreadable in a test")))
    onRestart()
    assertEquals(1, recorder.events.count { it.label == "player.opened" })
  }
}
