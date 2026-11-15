package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.assertReadsSignal
import dev.jordond.filmstrip.ffmpeg.support.lumaSampleSignal
import dev.jordond.filmstrip.ffmpeg.support.midFallOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.readRgb
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import dev.jordond.filmstrip.ffmpeg.support.yccOf
import io.kotest.assertions.withClue
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import io.kotest.matchers.doubles.shouldBeLessThan as shouldBeBelow

/**
 * Reads the frames ffmpeg draws for a [Hi8] straight off the preview pump against [TapePictureSignal], where its
 * dropouts are shorter and its luma sharper than the tape the lowering was first written for.
 */
class FfmpegTapeFormatsMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a hi8 dropout lands on the signal in the middle of its fall`() =
    runBlocking<Unit> {
      val (hi8, y, column) = midFallOf(WIDTH, HEIGHT, tickOf(READ_SLOT)) { Hi8(intensity = INTENSITY, seed = it) }
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.frameAt(draw.composition(listOf(clip), listOf(hi8)), Duration.ZERO, READ_SLOT)

      assertDropoutReads(frame, hi8, WIDTH, HEIGHT, y, column)
      assertReadsSignal(frame, hi8, WIDTH, HEIGHT, READ_SLOT, ::rampAt)
    }

  @Test
  fun `a smaller preview draws the same hi8 dropout at its own rows`() =
    runBlocking<Unit> {
      val width = WIDTH / 2
      val height = HEIGHT / 2
      val (hi8, y, column) = midFallOf(width, height, tickOf(READ_SLOT)) { Hi8(intensity = INTENSITY, seed = it) }
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val composition = draw.composition(listOf(clip), listOf(hi8))
      val frame = draw.frameAt(composition, Duration.ZERO, READ_SLOT, ExportSpec(heightPx = height))

      assertDropoutReads(frame, hi8, width, height, y, column)
      assertReadsSignal(frame, hi8, width, height, READ_SLOT, ::rampAt)
    }

  private fun assertDropoutReads(
    frame: ByteArray,
    hi8: TapeLook,
    width: Int,
    height: Int,
    y: Int,
    column: Int,
  ) {
    val read = yccOf(readRgb(frame, width, column, y))
    val wanted = yccOf(lumaSampleSignal(hi8, tickOf(READ_SLOT), column, y, width, height, ::rampAt))
    withClue("the dropout on row $y at column $column of seed ${hi8.seed}") {
      abs(read[0] - wanted[0]) shouldBeBelow VHS_LUMA_CODES
    }
  }

  private companion object {
    const val WIDTH = VHS_WIDTH
    const val HEIGHT = VHS_HEIGHT
    const val READ_SLOT = 30
    const val INTENSITY = 0.6f
  }
}
