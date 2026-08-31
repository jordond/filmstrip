package dev.jordond.filmstrip.compose

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ScrubStateTest {
  @Test
  fun `a gesture brackets its seeks with the engine's scrubbing mode`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      var scrub: ScrubState? = null

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val player = rememberFilmstripPlayer(filmstrip, composition)
        scrub = rememberScrubState(player)
      }

      val state = checkNotNull(scrub)

      state.onScrubTo(1.seconds)
      engine.seeks shouldBe emptyList()

      state.onScrubStart()
      engine.isScrubbing shouldBe true
      state.onScrubTo(1.seconds)
      state.onScrubTo(2.seconds)
      state.onScrubEnd()

      engine.isScrubbing shouldBe false
      state.isScrubbing shouldBe false
      engine.seeks shouldBe listOf(1.seconds, 2.seconds)

      runtime.dispose()
    }
}
