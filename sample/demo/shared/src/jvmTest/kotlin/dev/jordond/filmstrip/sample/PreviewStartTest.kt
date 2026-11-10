package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What the editor's hoisted effect does around a pick, checked here since the effect itself only
 * runs inside a composition.
 *
 * It restarts on the same two values every time: the source and whether a probe is still running.
 * This drives both through a pick the way a real one does and counts how many players that opens.
 */
@OptIn(InternalFilmstripApi::class)
class PreviewStartTest {
  private val resolved = CompletableDeferred<ProbeResult>()
  private val recorder = DiagnosticsRecorder()
  private val state =
    SampleAppState(
      filmstrip = Filmstrip { addMediaProberFactory { MediaProber { resolved.await() } } },
      recorder = recorder,
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  // What the effect's body does on every restart.
  private fun onRestart() {
    if (!state.probing) state.startPreview()
  }

  private val playersOpened: Int get() = recorder.events.count { it.label == "player.opened" }

  @Test
  fun `a probe still in flight never opens a player the next restart immediately replaces`() {
    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")
    onRestart()
    playersOpened shouldBe 0

    resolved.complete(ProbeResult.Success(MediaInfo(5.seconds, null, emptyList(), isExportable = true)))
    onRestart()
    playersOpened shouldBe 1
  }

  @Test
  fun `a clip that cannot load says why opens no player and leaves play off`() {
    state.onPicked(MediaSource.of("/tmp/clip.mkv"), "clip.mkv")
    resolved.complete(ProbeResult.Failure(FilmstripError.SourceUnreadable("/tmp/clip.mkv", "No readable track.")))
    onRestart()

    playersOpened shouldBe 0
    state.sourceFailure shouldBe "No readable track."
    state.canPlay shouldBe false
  }
}
