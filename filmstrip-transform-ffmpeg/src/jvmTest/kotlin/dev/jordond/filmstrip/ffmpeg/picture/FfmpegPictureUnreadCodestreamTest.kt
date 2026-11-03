package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.picture.ycbcrScale
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.convertedFilter
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.landedRgb
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test

/**
 * An AVIF with no `nclx` whose sequence header core does not read, decoded by this ffmpeg.
 *
 * Its header is the BT.709 limited one of [AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED] with a padding byte that
 * is not zero after its trailing bits. Core gives up on it, while dav1d only checks trailing bits in strict mode.
 * Core's coding of it holds the BT.601 limited stand-ins of a silent codestream, so the picture must go through
 * untouched and land where the decoder reads its header, not as BT.601 limited.
 */
class FfmpegPictureUnreadCodestreamTest {
  private val fixture = FfmpegExportFixture()
  private val picture = AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED
  private val item = picture.thumbnail()

  private fun still(codestream: ByteArray): ByteArray =
    HeifStills.still("avif", item.itemType, item.configuration, codestream, item.size)

  @Test
  fun `an AVIF whose codestream core cannot read goes through untouched`() {
    val unread = pictureYcbcrCoding(still(padded(item.codestream))).shouldNotBeNull()
    unread.codec shouldBe CodecKind.Av1
    unread.codestream.shouldBeNull()
    unread.colorKnown.shouldBeFalse()
    convertedFilter(unread) shouldBe PASS_THROUGH
    val read = pictureYcbcrCoding(still(item.codestream)).shouldNotBeNull()
    read.colorKnown.shouldBeTrue()
    convertedFilter(read) shouldBe ycbcrScale(read)
  }

  @Test
  fun `an AVIF whose codestream core cannot read lands where its decoder reads it`() {
    if (!fixture.available()) return
    val expected = landedRgb(still(item.codestream), picture.ycbcr).toList()
    val coding = pictureYcbcrCoding(still(item.codestream)).shouldNotBeNull()
    val (y, cb, cr) = picture.ycbcr
    val standIn = ycbcrToRgb(y, cb, cr, BT601, coding.primaries, coding.transfer, fullRange = false).toList()
    val apart = expected.indices.any { abs(expected[it] - standIn[it]) > PICTURE_DRIFT }
    withClue("BT.709 reads alike as BT.601") { apart.shouldBeTrue() }
    val read = FfmpegPictureDecoder("ffmpeg").decode(ImageSource.ofBytes(still(padded(item.codestream))))
    val decoded =
      withClue("this ffmpeg decoded nothing: $read") {
        (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
      }
    val argb = decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb
    val centre = argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2]
    val landed = intArrayOf(centre shr 16, centre shr 8, centre).map { it and 0xFF }
    val near = landed.indices.all { abs(landed[it] - expected[it]) <= PICTURE_DRIFT }
    withClue("decoded $landed, not $expected") { near.shouldBeTrue() }
  }

  // The codestream with a padding byte of 1 after its leading sequence header's trailing bits, its size one larger.
  private fun padded(codestream: ByteArray): ByteArray {
    codestream[0].toInt() shouldBe SEQUENCE_HEADER_WITH_SIZE
    val size = codestream[1].toInt()
    withClue("a header of $size bytes needs a wider size field") { (size in 1 until LEB128_CONTINUES).shouldBeTrue() }
    val header = byteArrayOf(codestream[0], (size + 1).toByte()) + codestream.copyOfRange(2, 2 + size) + 1
    return header + codestream.copyOfRange(2 + size, codestream.size)
  }

  private companion object {
    const val SEQUENCE_HEADER_WITH_SIZE = 0x0A
    const val LEB128_CONTINUES = 0x7F

    // Read as BT.601 limited, this texel moves nine codes, well past PICTURE_DRIFT.
    const val BT601 = 6
  }
}
