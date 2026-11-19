package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import dev.jordond.filmstrip.edit.OutputFrame as Frame

/**
 * Solid colours exported in the browser and decoded back.
 */
class BrowserSolidMediaTest {
  @Test
  fun onlySolidsExportAtAFixedFrameInTheirColour() =
    runTest {
      val frames = decodeFrames(outputOf(exportOf(edit(AuthoredColors.MID_GREEN), MediaSink.Uri(""))))

      frames.isNotEmpty() shouldBe true
      frames.first().width shouldBe FIXED.width
      frames.first().height shouldBe FIXED.height
      frames.forEach { assertSolid(it, rgbOf(AuthoredColors.MID_GREEN)) }
    }

  // A translucent solid on the primary track is composited over the fill, the same as a translucent still.
  @Test
  fun aTranslucentSolidShowsTheFillThroughIt() =
    runTest {
      val frames = decodeFrames(outputOf(exportOf(edit(HALF_WHITE, Fill.Solid(BLACK)), MediaSink.Uri(""))))

      frames.forEach { assertSolid(it, Rgb(128, 128, 128)) }
    }

  private fun assertSolid(
    frame: OutputFrame,
    expected: Rgb,
  ) {
    listOf(0.5 to 0.5, CORNER to CORNER, 1 - CORNER to 1 - CORNER).forEach { (x, y) ->
      assertReads(expected, frame.at(x, y), "the solid at $x,$y", AuthoredColors.TOLERANCE)
    }
  }

  private fun edit(
    color: Int,
    fill: Fill = Fill.Solid(BLACK),
  ): Composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Solid(color, 300.milliseconds))))),
      audio = AudioSpec.Remove,
      fill = fill,
      frame = Frame.Fixed(FIXED),
    )

  private companion object {
    // Whole macroblocks, so no corner read lands in the padding an encoder codes past the last row.
    val FIXED = Size(64, 48)
    const val CORNER = 0.03
    const val HALF_WHITE = 0x80FFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
  }
}
