package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.player.PlaybackEvent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PlaybackEventEffectTest {
  @Test
  fun `the first load a bound player issues is not emitted before the effect subscribes`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      val received = mutableListOf<PlaybackEvent>()

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val player = rememberFilmstripPlayer(filmstrip, composition)
        PlaybackEventEffect(player) { event -> received += event }
      }

      // Two loads: the one preview() issues while the player is being built, which no collector
      // could have seen, and the one the binding issues once the composition is running.
      engine.loads.size shouldBe 2
      received shouldBe listOf(PlaybackEvent.FirstFrameRendered)

      runtime.dispose()
    }

  @Test
  fun `a surface state stops covering once the first frame lands`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      var covered = true

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val player = rememberFilmstripPlayer(filmstrip, composition)
        covered = rememberVideoSurfaceState(player).coverSurface
      }

      covered shouldBe false

      runtime.dispose()
    }
}
