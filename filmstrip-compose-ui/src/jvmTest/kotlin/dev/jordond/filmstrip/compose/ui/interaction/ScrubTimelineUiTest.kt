package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.rememberScrubState
import dev.jordond.filmstrip.compose.ui.CLIP_LENGTH
import dev.jordond.filmstrip.compose.ui.ScrubbingEngine
import dev.jordond.filmstrip.compose.ui.filmstripWith
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.testComposition
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ScrubTimelineUiTest {
  @Test
  fun `a press begins one scrub, a drag seeks inside it, and lifting off ends it`() =
    runComposeUiTest {
      val engine = ScrubbingEngine()
      lateinit var scrub: ScrubState
      setContent { scrub = scrubbable(engine) }

      onRoot().performTouchInput { down(Offset(100f, 24f)) }
      waitForIdle()

      engine.scrubStarts shouldBe 1
      engine.scrubEnds shouldBe 0
      scrub.isScrubbing shouldBe true
      engine.seeks.first() shouldBe 1.seconds

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(150f, 0f))
      }
      waitForIdle()

      onRoot().performTouchInput { up() }
      waitForIdle()

      engine.scrubStarts shouldBe 1
      engine.scrubEnds shouldBe 1
      scrub.isScrubbing shouldBe false

      // Where the finger ended, not where it started. A loop that only seeked on the press would
      // leave this at one second.
      (engine.seeks.last() - 2500.milliseconds).absoluteValue shouldBeLessThan 2.milliseconds
    }

  @Test
  fun `a press on an offset timeline seeks player time, not timeline time`() =
    runComposeUiTest {
      val engine = ScrubbingEngine()
      setContent { scrubbable(engine, sourceOffset = { 1500.milliseconds }) }

      onRoot().performTouchInput { down(Offset(350f, 24f)) }
      waitForIdle()

      engine.seeks.first() shouldBe 2.seconds
    }

  @Test
  fun `a sourceOffset that changes mid-gesture is honoured by the next drag`() =
    runComposeUiTest {
      val engine = ScrubbingEngine()
      var offset by mutableStateOf(Duration.ZERO)
      setContent { scrubbable(engine, sourceOffset = { offset }) }

      onRoot().performTouchInput { down(Offset(100f, 24f)) }
      waitForIdle()
      engine.seeks.first() shouldBe 1.seconds

      // The trim handle moves mid-drag, changing what player time zero means without a new
      // gesture. A lambda captured once at press would still be seeking against the old offset.
      offset = 1500.milliseconds

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveTo(Offset(300f, 24f))
      }
      waitForIdle()

      engine.seeks.last() shouldBe 1500.milliseconds
    }

  @Test
  fun `a scrub ends when the timeline leaves the composition under the finger`() =
    runComposeUiTest {
      val engine = ScrubbingEngine()
      var attached by mutableStateOf(true)

      setContent {
        val filmstrip = remember(engine) { filmstripWith(engine) }
        val composition = remember { testComposition() }
        val scrub = rememberScrubState(rememberFilmstripPlayer(filmstrip, composition))
        if (attached) {
          Box(
            Modifier
              .size(width = 400.dp, height = 48.dp)
              .scrubTimeline(scrub, TimelineScale(CLIP_LENGTH, PIXELS_PER_SECOND)),
          )
        }
      }

      onRoot().performTouchInput { down(Offset(100f, 24f)) }
      waitForIdle()
      engine.scrubStarts shouldBe 1
      engine.scrubEnds shouldBe 0

      // Navigating away mid-drag. The gesture never gets an up, so only the coroutine being torn
      // down can settle the player, and a player left scrubbing keeps its seeks inexact.
      attached = false
      waitForIdle()

      engine.scrubEnds shouldBe 1
    }

  @Composable
  private fun scrubbable(
    engine: ScrubbingEngine,
    sourceOffset: () -> Duration = { Duration.ZERO },
  ): ScrubState {
    val filmstrip = remember(engine) { filmstripWith(engine) }
    val composition = remember { testComposition() }
    val scrub = rememberScrubState(rememberFilmstripPlayer(filmstrip, composition))

    Box(
      Modifier
        .size(width = 400.dp, height = 48.dp)
        .scrubTimeline(scrub, TimelineScale(CLIP_LENGTH, PIXELS_PER_SECOND), sourceOffset = sourceOffset),
    )

    return scrub
  }

  private companion object {
    const val PIXELS_PER_SECOND = 100f
  }
}
