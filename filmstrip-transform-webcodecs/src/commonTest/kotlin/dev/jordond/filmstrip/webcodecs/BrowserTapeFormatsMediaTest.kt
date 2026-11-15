package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BrowserTapeFormatsMediaTest : BrowserTapePictureFrames() {
  @Test
  fun aHi8DropoutLandsOnTheSignalInTheMiddleOfItsFall() =
    runTest {
      val (hi8, y, column) = midFall(WIDTH, HEIGHT) { seed -> Hi8(intensity = FORMAT_INTENSITY, seed = seed) }
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(hi8)))
      val picture = drawn(edit(Clip(clip)))

      codesAt(frame, picture, hi8, y, column) shouldBeLessThan SIGNAL_CODES
      worstCodes(frame, picture, hi8) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aBetamaxDropoutLandsOnTheSignalInTheMiddleOfItsFall() =
    runTest {
      val (betamax, y, column) = midFall(WIDTH, HEIGHT) { seed -> Betamax(intensity = FORMAT_INTENSITY, seed = seed) }
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(betamax)))
      val picture = drawn(edit(Clip(clip)))

      codesAt(frame, picture, betamax, y, column) shouldBeLessThan SIGNAL_CODES
      worstCodes(frame, picture, betamax) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameHi8DropoutAtItsOwnVirtualRows() =
    runTest {
      val smallWidth = WIDTH * SMALL_HEIGHT / HEIGHT
      val (hi8, y, column) =
        midFall(
          smallWidth,
          SMALL_HEIGHT,
        ) { seed -> Hi8(intensity = FORMAT_INTENSITY, seed = seed) }
      val clip = rampClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(hi8)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size shouldBe Size(smallWidth, SMALL_HEIGHT)
      codesAt(frame, picture, hi8, y, column) shouldBeLessThan SIGNAL_CODES
      worstCodes(frame, picture, hi8) shouldBeLessThan SIGNAL_CODES
    }

  // The first seed, row and column where a dropout of the look [lookOf] builds is in the middle of its fall at the read
  // time, on a frame [width] by [height].
  private fun midFall(
    width: Int,
    height: Int,
    lookOf: (Int) -> TapeLook,
  ): Triple<TapeLook, Int, Int> {
    for (seed in 1..SEEDS) {
      val look = lookOf(seed)
      val params = TapePictureParams.of(look)
      for (y in 0 until height) {
        val row = TapePictureSignal.virtualRow(y, height)
        for (column in 1 until width) {
          val here = TapePictureSignal.dropout(seed, TICK, row, (column + 0.5) / width, params)
          val before = TapePictureSignal.dropout(seed, TICK, row, (column - 0.5) / width, params)
          if (here in MID_FALL && before > here) return Triple(look, y, column)
        }
      }
    }
    error("no dropout in the middle of its fall in $SEEDS seeds")
  }
}

private const val FORMAT_INTENSITY = 0.6f
private const val SEEDS = 200
private val MID_FALL = 0.3..0.7
