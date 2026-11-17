package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.motion.Easing
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What a frame inside a [Vhs] settle costs at 1080p against a frame of the constant look, exported one second of frames
 * each. Run with `-Pmeasure`, and read the figures it prints.
 */
class FfmpegVhsSettleCostMeasureTest {
  private val draw = FilterFrames()

  @Test
  fun `a frame inside a settle costs no more than a few of a constant look at 1080p`() =
    runBlocking<Unit> {
      val constant = secondsPerFrame(Vhs(intensity = 0.35f))
      val inside = secondsPerFrame(Vhs(intensity = 0.35f, settle = TapeSettle(1f, 4.seconds, Easing.EaseInOut)))
      val after = secondsPerFrame(Vhs(intensity = 0.35f, settle = TapeSettle(1f, 0.1.seconds, Easing.EaseInOut)))
      val gone = secondsPerFrame(Vhs(intensity = 0f, settle = TapeSettle(1f, 0.1.seconds, Easing.EaseInOut)))

      println(
        "VHS SETTLE COST per frame at 1080p: constant $constant s, inside a settle $inside s, after one $after s, " +
          "after a settle to nothing $gone s",
      )
      (inside / constant) shouldBeLessThan MAXIMUM_RATIO
    }

  private suspend fun secondsPerFrame(look: Vhs): Double {
    val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(look), seconds = CLIP_SECONDS)
    val composition = draw.composition(listOf(clip))
    val spent = measureTimeMillis { draw.exportedFrames(composition, listOf(0), ExportSpec()) }
    return spent / 1000.0 / (CLIP_SECONDS * FRAME_RATE)
  }

  private companion object {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val CLIP_SECONDS = 1
    const val FRAME_RATE = 30
    const val MAXIMUM_RATIO = 6.0
  }
}
