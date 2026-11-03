package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.webFixtureComposition
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Both of the browser's clocks, on the page's real time.
 */
class PreviewClockTest {
  @Test
  fun `a wall clock never started holds where it was moved`() =
    contractTest {
      val clock = MonotonicClock()

      clock.position shouldBe Duration.ZERO
      clock.moveTo(300.milliseconds)
      delay(WAIT)

      clock.position shouldBe 300.milliseconds
      clock.isRunning shouldBe false
    }

  @Test
  fun `a running wall clock counts on from where it started`() =
    contractTest {
      val clock = MonotonicClock()

      clock.start(500.milliseconds)
      delay(WAIT)

      clock.isRunning shouldBe true
      clock.position shouldBeGreaterThanOrEqualTo 500.milliseconds + WAIT - SLACK
      clock.position shouldBeLessThan 500.milliseconds + 1.seconds
    }

  @Test
  fun `a stopped wall clock holds where it reached`() =
    contractTest {
      val clock = MonotonicClock()
      clock.start(500.milliseconds)
      delay(WAIT)

      clock.stop()
      val reached = clock.position
      delay(WAIT)

      clock.position shouldBe reached
      clock.isRunning shouldBe false
      reached shouldBeGreaterThanOrEqualTo 500.milliseconds + WAIT - SLACK
    }

  @Test
  fun `moving a running wall clock counts on from the new position`() =
    contractTest {
      val clock = MonotonicClock()
      clock.start(Duration.ZERO)
      delay(WAIT)

      clock.moveTo(1.seconds)
      clock.position shouldBeLessThan 1.seconds + SLACK
      delay(WAIT)

      clock.isRunning shouldBe true
      clock.position shouldBeGreaterThanOrEqualTo 1.seconds + WAIT - SLACK
    }

  @Test
  fun `an audio clock not started holds where it was moved`() =
    contractTest {
      val (_, preview) = browserPreviewOf(webFixtureComposition(audio = AudioSpec.Keep))
      try {
        val clock = AudioClock(preview.audio().shouldNotBeNull())

        clock.moveTo(300.milliseconds)
        delay(WAIT)

        clock.position shouldBe 300.milliseconds
        clock.isRunning shouldBe false
      } finally {
        preview.release()
      }
    }

  @Test
  fun `a started audio clock reads the graph's own clock and runs only while the graph does`() =
    contractTest {
      val (_, preview) = browserPreviewOf(webFixtureComposition(audio = AudioSpec.Keep))
      try {
        val audio = preview.audio().shouldNotBeNull()
        val clock = AudioClock(audio)

        clock.start(500.milliseconds)
        val before = audio.position
        val read = clock.position
        val after = audio.position

        read shouldBeGreaterThanOrEqualTo before
        read shouldBeLessThanOrEqualTo after
        clock.isRunning shouldBe audio.isRunning
      } finally {
        preview.release()
      }
    }

  @Test
  fun `a stopped audio clock holds the graph's position`() =
    contractTest {
      val (_, preview) = browserPreviewOf(webFixtureComposition(audio = AudioSpec.Keep))
      try {
        val audio = preview.audio().shouldNotBeNull()
        val clock = AudioClock(audio)
        clock.start(Duration.ZERO)

        val before = audio.position
        clock.stop()
        val held = clock.position
        delay(WAIT)

        held shouldBeGreaterThanOrEqualTo before
        clock.position shouldBe held
        clock.isRunning shouldBe false
      } finally {
        preview.release()
      }
    }

  private companion object {
    val WAIT = 60.milliseconds

    // What a timer firing early or a coarsened performance.now() can take off a real wait.
    val SLACK = 20.milliseconds
  }
}
