package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.camcorder.OsdCorner
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.ffmpeg.support.CAMCORDER_CLEAR
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertOnCamcorderRaster
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A [Camcorder] counting frames on ffmpeg, where the frame count is a looped sidecar of its own, read against
 * [CamcorderOsd.rasterAt] wherever the count sits.
 */
class FfmpegCamcorderLoopMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a frame count past the right edge draws nothing and the rest of the line lands`() =
    runBlocking<Unit> {
      val camcorder =
        Camcorder(listOf(OsdElement.Counter(withFrames = true, corner = OsdCorner.BottomLeft)), textSize = 0.15f)

      assertMidSecond(camcorder, draw.clipOf(TALL_WIDTH, TALL_HEIGHT, RAMP, seconds = 1), TALL_WIDTH, TALL_HEIGHT)
    }

  @Test
  fun `a left corner count keeps landing as the counter grows an hour digit`() =
    runBlocking<Unit> {
      val counter = OsdElement.Counter(start = 9.hours + 59.minutes + 58.seconds, true, OsdCorner.TopLeft)
      val camcorder = Camcorder(listOf(counter), textSize = 0.1f)
      val clip = rampClip()

      assertMidSecond(camcorder, clip, WIDTH, HEIGHT, BEFORE_TEN_SLOT)
      assertMidSecond(camcorder, clip, WIDTH, HEIGHT, AFTER_TEN_SLOT)
    }

  @Test
  fun `a line drawn over the frame count wins where they overlap`() =
    runBlocking<Unit> {
      val label = OsdElement.Label("OVERLAP OVERLAP OVERLAP", OsdCorner.TopLeft)
      val counter = OsdElement.Counter(withFrames = true)
      val camcorder = Camcorder(listOf(counter, label), textSize = 0.1f)
      val time = slotTime(MID_SLOT)
      val alone = { element: OsdElement ->
        CamcorderOsd(Camcorder(listOf(element), textSize = 0.1f), FILTER_RATE.toFloat()).rasterAt(time, WIDTH, HEIGHT)
      }
      val counted = alone(counter)
      val labelled = alone(label)

      var shared = 0
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          if (counted.argbAt(x, y) != CAMCORDER_CLEAR && labelled.argbAt(x, y) != CAMCORDER_CLEAR) shared++
        }
      }
      withClue("the label has to cover pixels of the count for this to tell") { shared shouldBeGreaterThan 0 }
      assertMidSecond(camcorder, rampClip(), WIDTH, HEIGHT)
    }

  private suspend fun assertMidSecond(
    camcorder: Camcorder,
    clip: Clip,
    width: Int,
    height: Int,
    slot: Int = MID_SLOT,
  ) {
    val osd = CamcorderOsd(camcorder, FILTER_RATE.toFloat())
    assertOnCamcorderRaster(
      draw.planesAt(draw.composition(listOf(clip), listOf(camcorder)), slot),
      draw.planesAt(draw.composition(listOf(clip)), slot),
      osd.rasterAt(slotTime(slot), width, height),
    )
  }

  private fun rampClip() = draw.clipOf(WIDTH, HEIGHT, RAMP, seconds = CLIP_SECONDS)

  private fun slotTime(slot: Int): Duration = outputSlotTime(slot.toLong(), FILTER_RATE)

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val TALL_WIDTH = 1080
    const val TALL_HEIGHT = 1920
    const val CLIP_SECONDS = 3
    val RAMP = { x: Double -> doubleArrayOf(0.25 + x / 2, 0.5, 0.75 - x / 2) }

    // Frame 15 of the first second, as far from a second as a frame gets.
    const val MID_SLOT = 15

    // Mid second either side of the counter reaching 10:00:00, which it does two seconds in.
    const val BEFORE_TEN_SLOT = 45
    const val AFTER_TEN_SLOT = 75
  }
}
