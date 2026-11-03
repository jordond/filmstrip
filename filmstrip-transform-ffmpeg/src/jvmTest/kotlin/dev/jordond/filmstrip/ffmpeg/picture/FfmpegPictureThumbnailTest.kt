package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * AVIF and HEIC stills that carry a thumbnail item ahead of their primary item, which the decoder reads past.
 *
 * ffmpeg 9.0 opens every item of a HEIF file as a stream of its own, so a read that binds the first stream it finds
 * reads the thumbnail. Each still here is decoded and measured at its primary item's size, with the texel the same
 * still without a thumbnail decodes to, flat and tiled. Every thumbnail is a size the primary item is not.
 */
class FfmpegPictureThumbnailTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a HEIC with a thumbnail ahead of it decodes and measures as its primary item, tiled or not`() {
    if (!fixture.available()) return
    val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED
    val thumbnail = ColorPictures.NCLX_BT709_LIMITED.thumbnail()
    assertPrimary("HEIC", heic.bytes(thumbnail), heic.bytes(), thumbnail)

    assertPrimary("tiled HEIC", heic.gridBytes(heic.thumbnail()), heic.gridBytes(), heic.thumbnail())
  }

  @Test
  fun `an AVIF with a thumbnail ahead of it decodes and measures as its primary item, tiled or not`() {
    if (!fixture.available()) return
    val avif = ColorPictures.NCLX_BT709_LIMITED
    val thumbnail = ColorPictures.HEIC_NCLX_BT709_LIMITED.thumbnail()
    assertPrimary("AVIF", avif.bytes(thumbnail), avif.bytes(), thumbnail)

    assertPrimary("tiled AVIF", avif.gridBytes(avif.thumbnail()), avif.gridBytes(), avif.thumbnail())
  }

  // [bytes] decoded and measured at the size [alone], the same still without [thumbnail], decodes to, and holding the
  // texel it holds.
  private fun assertPrimary(
    name: String,
    bytes: ByteArray,
    alone: ByteArray,
    thumbnail: HeifThumbnail,
  ) {
    val primary = decoded(alone)
    withClue("$name's thumbnail is the primary item's size") { primary.size shouldNotBe thumbnail.size }
    val read = decoded(bytes)
    withClue("$name decoded at its thumbnail's size") { read.size shouldBe primary.size }
    withClue("$name decoded a texel the primary item does not hold") { centreOf(read) shouldBe centreOf(primary) }
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    withClue("$name measured: $measured") { (measured as? OverlayPictureRead.Read)?.value shouldBe primary.size }
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return withClue("this ffmpeg decoded nothing: $read") {
      (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
    }
  }

  private fun centreOf(decoded: DecodedPicture): Int {
    val argb =
      withClue("an eight-bit still decoded deeper") {
        decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>()
      }.argb
    val size: Size = decoded.size
    return argb[size.height / 2 * size.width + size.width / 2]
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
  }
}
