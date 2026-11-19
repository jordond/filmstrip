package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Solid colours exported by media3 onto a real file and read back from the first frame.
 */
class AndroidSolidMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun onlySolidsExportAtAFixedFrameInTheirColour() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREEN, 1.seconds)
          frame(OutputFrame.Fixed(FIXED))
        }

      val plan = probe.plan(edit, ExportSpec())
      plan.output.size shouldBe FIXED
      plan.path shouldBe ExportPath.Transcode
      assertSolid(probe.written(edit, ExportSpec()), AuthoredColors.MID_GREEN, "the solid")
    }

  @Test
  fun aLeadingSolidDrawsItsColourOverTheWholeFrameOfTheClipAfterIt() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREY, 1.seconds)
          clip(probe.fixture()) { trim(0.seconds, 1.seconds) }
        }

      assertSolid(probe.written(edit, ExportSpec()), AuthoredColors.MID_GREY, "the leading solid")
    }

  // A translucent solid on the primary track is composited over the fill, the same as a translucent still.
  @Test
  fun aTranslucentSolidShowsTheFillThroughIt() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(HALF_WHITE, 1.seconds)
          fill(Fill.Solid(BLACK))
          frame(OutputFrame.Fixed(FIXED))
        }

      assertSolid(probe.written(edit, ExportSpec()), HALF_GREY, "half white over black")
    }

  private fun assertSolid(
    written: Written,
    color: Int,
    what: String,
  ) {
    val expected = AuthoredColors.channelsOf(color)
    listOf(0.5f to 0.5f, CORNER to CORNER, 1f - CORNER to 1f - CORNER).forEach { (fx, fy) ->
      val read = written.rgbAt(fx, fy).toList()
      withClue("$what read $read at $fx,$fy, expected $expected") {
        read.zip(expected).all { (got, want) -> abs(got - want) <= TOLERANCE }.shouldBeTrue()
      }
    }
  }

  private companion object {
    val FIXED = Size(320, 240)
    const val CORNER = 0.02f
    val TOLERANCE = AuthoredColors.TOLERANCE.toInt()
    val HALF_WHITE = 0x80FFFFFF.toInt()
    val BLACK = 0xFF000000.toInt()
    val HALF_GREY = 0xFF808080.toInt()
  }
}
