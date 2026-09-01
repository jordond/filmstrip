package dev.jordond.filmstrip.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
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

  @Test
  fun `a host's own callbacks are bracketed the same way`() =
    runTest {
      val seeks = mutableListOf<Duration>()
      val calls = mutableListOf<String>()
      var scrub: ScrubState? = null

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        scrub =
          rememberScrubState(
            onSeek = { position ->
              seeks += position
              calls += "seek"
            },
            onBegin = { calls += "begin" },
            onEnd = { calls += "end" },
          )
      }

      val state = checkNotNull(scrub)

      state.onScrubTo(1.seconds)
      calls shouldBe emptyList()

      state.onScrubStart()
      state.onScrubTo(1.seconds)
      state.onScrubTo(2.seconds)
      state.onScrubEnd()

      state.isScrubbing shouldBe false
      seeks shouldBe listOf(1.seconds, 2.seconds)
      calls shouldBe listOf("begin", "seek", "seek", "end")

      runtime.dispose()
    }

  @Test
  fun `the state keeps its identity while the callbacks it calls stay current`() =
    runTest {
      val seeks = mutableListOf<String>()
      var target by mutableStateOf("first")
      var scrub: ScrubState? = null

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        val current = target
        scrub = rememberScrubState(onSeek = { seeks += current })
      }

      val state = checkNotNull(scrub)
      state.onScrubStart()
      state.onScrubTo(1.seconds)

      target = "second"
      runtime.settle()

      // A lambda rebuilt on every recomposition must not rebuild the state under a gesture, and
      // the gesture in flight must still reach the lambda the last composition passed.
      scrub shouldBe state
      state.onScrubTo(2.seconds)
      seeks shouldBe listOf("first", "second")

      runtime.dispose()
    }
}
