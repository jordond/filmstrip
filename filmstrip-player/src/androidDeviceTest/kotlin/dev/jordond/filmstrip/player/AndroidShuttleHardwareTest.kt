package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How many pictures a second a shuttle puts on a real surface with the VHS look on, on a real phone.
 *
 * Counted off the surface, since media3 answers a seek it has not drawn yet. The floor is what picture search needs
 * to read as motion, and a phone is what it is measured on, so an emulator does not run this.
 */
class AndroidShuttleHardwareTest {
  @Test
  fun aShuttleShowsAtLeastTenPicturesASecondUnderVhs() =
    runTest(timeout = TIMEOUT) {
      assumeFalse("an emulator's software renderer is not what the floor is measured on", runsOnEmulator)
      withContext(Dispatchers.Default) {
        withShuttleSurface(effects = listOf(Vhs()), configure = { filters() }) { surface ->
          for (rate in RATES) {
            // Far enough from the edge the shuttle runs towards that it is still moving when the window closes.
            val window = (LONG_CLIP_LENGTH - MARGIN * 2) / rate.toDouble().let(Math::abs)
            surface.seekAndAwait(if (rate > 0) MARGIN else LONG_CLIP_LENGTH - MARGIN)

            val shown =
              surface.picturesDuring {
                surface.player.beginShuttle(rate)
                delay(window)
                surface.player.endShuttle()
              }

            val perSecond = shown / window.toDouble(DurationUnit.SECONDS)
            withClue("a shuttle at ${rate}x showed $shown pictures in $window") {
              perSecond shouldBeGreaterThanOrEqual FLOOR_FPS
            }
          }
        }
      }
    }

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val MARGIN: Duration = 1.seconds
    val RATES = listOf(3f, -3f, 7f, -7f)

    const val FLOOR_FPS = 10.0
  }
}
