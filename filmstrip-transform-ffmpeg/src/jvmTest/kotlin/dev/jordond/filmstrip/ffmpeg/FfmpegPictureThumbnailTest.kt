package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeifThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * AVIF and HEIC stills that carry a thumbnail item ahead of their primary item, which the decoder reads past.
 *
 * ffmpeg 9.0 opens every item of a HEIF file as a stream of its own, so a read that binds the first stream it finds
 * reads the thumbnail. Each still here is decoded and measured at its primary item's size, with the texel the same
 * still without a thumbnail decodes to, flat and tiled. Every thumbnail is a size the primary item is not. ffmpeg 6.1
 * has no HEIF demuxer and no tile grids, which skips those as an assumption.
 */
class FfmpegPictureThumbnailTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a HEIC with a thumbnail ahead of it decodes and measures as its primary item, tiled or not`() {
    if (!fixture.available()) return
    val heic = ColourPictures.HEIC_NCLX_BT709_LIMITED
    assumeHeif()
    val thumbnail = ColourPictures.NCLX_BT709_LIMITED.thumbnail()
    assertPrimary("HEIC", heic.bytes(thumbnail), heic.bytes(), thumbnail)

    assumeTiles(heic.gridBytes())
    assertPrimary("tiled HEIC", heic.gridBytes(heic.thumbnail()), heic.gridBytes(), heic.thumbnail())
  }

  @Test
  fun `an AVIF with a thumbnail ahead of it decodes and measures as its primary item, tiled or not`() {
    if (!fixture.available()) return
    val avif = ColourPictures.NCLX_BT709_LIMITED
    val thumbnail = ColourPictures.HEIC_NCLX_BT709_LIMITED.thumbnail()
    assertPrimary("AVIF", avif.bytes(thumbnail), avif.bytes(), thumbnail)

    assumeTiles(avif.gridBytes())
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
    assertNotEquals(thumbnail.size, primary.size, "$name's thumbnail is the primary item's size")
    val read = decoded(bytes)
    assertEquals(primary.size, read.size, "$name decoded at its thumbnail's size")
    assertEquals(centreOf(primary), centreOf(read), "$name decoded a texel the primary item does not hold")
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    assertEquals(primary.size, (measured as? OverlayPictureRead.Read)?.value, "$name measured: $measured")
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing: $read")
  }

  private fun centreOf(decoded: DecodedPicture): Int {
    val argb = assertIs<StoredSamples.Argb>(decoded.samples, "an eight-bit still decoded deeper").argb
    val size: Size = decoded.size
    return argb[size.height / 2 * size.width + size.width / 2]
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
  }
}
