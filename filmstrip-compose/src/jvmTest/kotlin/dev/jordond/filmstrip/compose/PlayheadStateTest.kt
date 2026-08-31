package dev.jordond.filmstrip.compose

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class PlayheadStateTest {
  @Test
  fun `a held position provider costs no recomposition when the playhead moves`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      var compositions = 0
      var provider: (() -> Duration)? = null

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val player = rememberFilmstripPlayer(filmstrip, composition)
        val playhead = rememberPlayheadState(player)
        compositions++
        provider = playhead.positionProvider()
      }

      val settled = compositions
      engine.reportPosition(600.milliseconds)
      runtime.settle()

      compositions shouldBe settled
      provider?.invoke() shouldBe 600.milliseconds

      runtime.dispose()
    }

  @Test
  fun `progress reads the loaded length`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      var playhead: PlayheadState? = null

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val player = rememberFilmstripPlayer(filmstrip, composition)
        playhead = rememberPlayheadState(player)
      }

      engine.reportPosition(CLIP_LENGTH / 4)
      runtime.settle()

      playhead?.duration shouldBe CLIP_LENGTH
      playhead?.progress shouldBe 0.25f

      runtime.dispose()
    }
}
