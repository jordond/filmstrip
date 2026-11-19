package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.encodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
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

  // The solid opens the export and the HDR clip after it sets the grade, so the solid's frames are read off that grade.
  @Test
  fun aSolidAheadOfAnHdrClipLandsWhereTheSharedLightPutsItOnBothTransfers() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(AuthoredColors.MID_GREEN)))
        assertHdrSolid(transfer, AuthoredColors.MID_GREEN, Fill.Solid(BLACK), expected.toList(), "$transfer solid")
      }
    }

  @Test
  fun aTranslucentSolidOnAPqGradeLandsOnTheFillBlendedInLight() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val transfer = HdrTransfer.Pq
      val cell = HdrOverlayCells.Cell(HdrOverlayCells.WHITE, BLACK, HALF_OPACITY)
      val what =
        "half white over black, a signal blend lands at ${cell.signalBlendCodes(transfer).toList()} and an sRGB " +
          "blend at ${cell.srgbBlendCodes(transfer).toList()}"
      assertHdrSolid(transfer, HALF_WHITE, Fill.Solid(BLACK), cell.codes(transfer).toList(), what)
    }

  private suspend fun assertHdrSolid(
    transfer: HdrTransfer,
    color: Int,
    fill: Fill,
    expected: List<Int>,
    what: String,
  ) {
    val clip = makeHdrClip(transfer) { CLIP_LIGHT }
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Solid(color, 300.milliseconds)), Clip(MediaSource.Bytes(clip))))),
        audio = AudioSpec.Remove,
        fill = fill,
      )
    val frames = decodeTenBitFrames(outputOf(hdrExportOf(composition, transfer)), transfer)
    val solid = frames.minBy { abs(it.timestampUs - SOLID_READ_US) }
    val (cb, cr) = solid.chromaAt(x = 0.5, y = 0.5)
    val read = listOf(solid.lumaAt(x = 0.5, y = 0.5), cb, cr)

    withClue("$what read $read, expected $expected") {
      read.zip(expected).all { (got, want) -> abs(got - want) <= HDR_CODE_TOLERANCE } shouldBe true
    }
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
    const val HALF_OPACITY = 128f / 255f

    // The middle of the solid's 300 ms, clear of the cut to the clip.
    const val SOLID_READ_US = 150_000.0

    // What an encode leaves on a flat ten-bit patch, the chroma tolerance the HDR fill reads use.
    const val HDR_CODE_TOLERANCE = 4
    val CLIP_LIGHT = floatArrayOf(240f, 205f, 275f)
  }
}
