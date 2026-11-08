package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicColorPictures
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Twelve-bit AVIF and HEIC overlays decoded by this ffmpeg, which decodes them to sixteen-bit samples and lands them
 * through the matrix and range their `nclx` records.
 *
 * Each still is decoded and its centre texel held against [ycbcrToRgb] of its twelve-bit codes, through the matrix
 * and range core reads for it. The matrices checked are BT.709 at both ranges, BT.601, BT.2020, FCC and SMPTE 240M.
 */
class FfmpegPictureTwelveBitMediaTest {
  @Test
  fun `a twelve bit AVIF keeps its depth and lands through its nclx matrix and range`() {
    for (nclx in NCLXS) {
      val bytes = AvifColorPictures.twelveBit(nclx).bytes()
      assertWideLanded("AVIF $nclx", bytes, AvifColorPictures.TWELVE_BIT_YCBCR)
    }
  }

  @Test
  fun `a twelve bit HEIC keeps its depth and lands through its nclx matrix and range`() {
    for (nclx in NCLXS) {
      val bytes = HeicColorPictures.twelveBit(nclx)
      assertWideLanded("HEIC $nclx", bytes, HeicColorPictures.TWELVE_BIT_YCBCR)
    }
  }

  private fun assertWideLanded(
    name: String,
    bytes: ByteArray,
    ycbcr: List<Int>,
  ) {
    val coding = withClue(name) { pictureYcbcrCoding(bytes).shouldNotBeNull() }
    withClue(name) { coding.bitDepth shouldBe TWELVE_BITS }
    val (y, cb, cr) = ycbcr
    val expected =
      ycbcrToRgb(y, cb, cr, coding.matrix, coding.primaries, coding.transfer, coding.fullRange, TWELVE_BITS).toList()
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    val decoded =
      withClue("this ffmpeg decoded nothing: $read") {
        (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
      }
    val rgba =
      withClue("$name decoded at eight bits") {
        decoded.samples.shouldBeInstanceOf<StoredSamples.Rgba16>()
      }.rgba
    val at = (decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2) * 4
    val landed = (0 until 3).map { ((rgba[at + it].toInt() and SIXTEEN_BIT_MAX) / WIDE_PER_CODE).roundToInt() }
    val near = landed.indices.all { abs(landed[it] - expected[it]) <= PICTURE_DRIFT }
    withClue("$name decoded $landed, not $expected") { near.shouldBeTrue() }
  }

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)
    const val TWELVE_BITS = 12
    const val SIXTEEN_BIT_MAX = 0xFFFF

    // An eight-bit code at sixteen bits.
    const val WIDE_PER_CODE = 257.0

    // BT.709 at both ranges, then BT.601, BT.2020, FCC and SMPTE 240M, each a matrix swscale names.
    val NCLXS =
      listOf(
        CicpTag(1, 13, 1, true),
        CicpTag(1, 13, 1, false),
        CicpTag(1, 13, 6, true),
        CicpTag(9, 13, 9, false),
        CicpTag(1, 13, 4, true),
        CicpTag(1, 13, 7, false),
      )
  }
}
