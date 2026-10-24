package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.UNLISTED_ALPHA
import dev.jordond.filmstrip.ffmpeg.internal.alphaFrame
import dev.jordond.filmstrip.ffmpeg.internal.fullRangeAlpha
import dev.jordond.filmstrip.ffmpeg.internal.pictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.tiledAlpha
import dev.jordond.filmstrip.ffmpeg.internal.unlistedAlpha
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.HeifAlpha
import dev.jordond.filmstrip.internal.HeifGrid
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import org.junit.Assume.assumeFalse
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AVIF and HEIC stills carrying an alpha item beside their colour item, decoded by this ffmpeg.
 *
 * Each still's alpha item is one of [AlphaPlanes], and every texel's alpha is checked against the code the plane
 * stores at its column and row, expanded through [fullRangeAlpha] for a limited range plane. Its colour is checked
 * against the same still decoded without its alpha item. On a build that lists no stream for the item the still is
 * checked to draw opaque and name [UNLISTED_ALPHA], and the tests that read the alpha skip. The HEIC tests skip on a
 * build with no HEIF demuxer.
 */
class FfmpegPictureAlphaTest {
  private val decoder = FfmpegPictureDecoder("ffmpeg")

  @Test
  fun `an AVIF alpha item lands as the alpha of every texel`() {
    assumeAlphaStreams()
    val item = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    assertAlpha(item, AlphaPlanes.avif(), AlphaPlanes::avifAlpha)
  }

  @Test
  fun `a HEIC alpha item lands as the alpha of every texel`() {
    assumeHeif()
    val item = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    assertAlpha(item, AlphaPlanes.heic(), AlphaPlanes::heicAlpha)
  }

  @Test
  fun `a limited range AVIF alpha item lands expanded to full range`() {
    assumeAlphaStreams()
    val item = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    assertAlpha(item, AlphaPlanes.avif(fullRange = false)) { x, y -> expanded(AlphaPlanes.avifAlpha(x, y)) }
  }

  @Test
  fun `a limited range HEIC alpha item lands expanded to full range`() {
    assumeHeif()
    val item = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    assertAlpha(item, AlphaPlanes.heic(fullRange = false)) { x, y -> expanded(AlphaPlanes.heicAlpha(x, y)) }
  }

  @Test
  fun `a limited range code stretches from clear at 16 to opaque at 235`() {
    assertEquals(0, fullRangeAlpha(16 * WIDE_PER_BYTE, 8))
    assertEquals(WIDE.toInt(), fullRangeAlpha(235 * WIDE_PER_BYTE, 8))
    assertEquals((112 * WIDE / 219).roundToInt(), fullRangeAlpha(128 * WIDE_PER_BYTE, 8))
    assertEquals(0, fullRangeAlpha(4 * WIDE_PER_BYTE, 8))
    assertEquals(WIDE.toInt(), fullRangeAlpha(250 * WIDE_PER_BYTE, 8))
    val tenBit = { code: Int -> (code * WIDE / 1023).roundToInt() }
    assertEquals(0, fullRangeAlpha(tenBit(64), 10))
    assertEquals(WIDE.toInt(), fullRangeAlpha(tenBit(940), 10))
    assertEquals((438 * WIDE / 876).roundToInt(), fullRangeAlpha(tenBit(502), 10))
  }

  @Test
  fun `an AVIF decoded from its planes takes its alpha item too`() {
    assumeAlphaStreams()
    val item = ColourPictures.NCLX_DISPLAY_P3.thumbnail()
    val ycgco = { alpha: HeifAlphaPlane? ->
      HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, YCGCO, alpha = alpha)
    }
    assertIs<PictureRoute.Planes>(pictureRoute(pictureYcbcrCoding(ycgco(null))))
    assertAlpha(decoded(ycgco(null)), decoded(ycgco(AlphaPlanes.avif())), AlphaPlanes::avifAlpha)
  }

  @Test
  fun `a premultiplied AVIF comes back straight`() {
    assumeAlphaStreams()
    val item = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    val plain = argb(decoded(alphaStill(item, null)))
    val straight = argb(decoded(alphaStill(item, AlphaPlanes.avif(premultiplied = true))))
    for (at in plain.indices) {
      val alpha = AlphaPlanes.avifAlpha(at % item.size.width, at / item.size.width)
      assertEquals(alpha, straight[at] ushr ALPHA_SHIFT, "alpha of texel $at")
      for (shift in COLOUR_SHIFTS) {
        val stored = plain[at] ushr shift and BYTE
        val expected = (minOf(1.0, stored / BYTE.toDouble() / (alpha * WIDE_PER_BYTE / WIDE)) * BYTE).roundToInt()
        assertEquals(expected, straight[at] ushr shift and BYTE, "channel at $shift of texel $at")
      }
    }
  }

  @Test
  fun `an AVIF grid assembles its alpha tiles in rows and crops them to its size`() {
    val picture = ColourPictures.NCLX_UNSPECIFIED
    assumeTiles(picture.gridBytes())
    assumeAlphaStreams()
    val item = picture.thumbnail()
    val size = Size(20, 12)
    val grid = { alpha: List<HeifAlphaPlane>? ->
      HeifStills.grid(
        "avif",
        item.itemType,
        item.configuration,
        item.codestream,
        item.size,
        columns = 3,
        rows = 2,
        size = size,
        alpha = alpha,
      )
    }
    val drawn = decoded(grid(List(6) { AlphaPlanes.avifTile(it) }))
    assertEquals(size, drawn.size)
    val tile = item.size.width
    assertAlpha(decoded(grid(null)), drawn) { x, y ->
      AlphaPlanes.avifTileAlpha(x / tile + 3 * (y / tile), x % tile, y % tile)
    }
  }

  @Test
  fun `a build that lists no alpha stream draws the AVIF opaque and names its limit`() {
    assumeFalse("this ffmpeg lists a stream for the alpha item", listsAlphaStreams())
    val item = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    val plain = decoded(alphaStill(item, null))
    val opaque = decoded(alphaStill(item, AlphaPlanes.avif()))
    assertEquals(listOf(UNLISTED_ALPHA), opaque.limits)
    assertTrue(plain.limits.isEmpty())
    assertEquals(argb(plain).toList(), argb(opaque).toList())
    assertTrue(argb(opaque).all { it ushr ALPHA_SHIFT == BYTE }, "an opaque decode carries alpha")
  }

  @Test
  fun `alpha tiles stack in rows by the grid's columns and crop to its size`() {
    val grid = HeifGrid((7L..12L).toList(), columns = 3, rows = 2, size = Size(20, 16))
    assertEquals(
      "[0:#7][0:#8][0:#9][0:#10][0:#11][0:#12]xstack=inputs=6:layout=0_0|w0_0|w0+w0_0|0_h0|w0_h0|w0+w0_h0," +
        "crop=20:16:0:0,setparams=range=pc,format=gray16le",
      tiledAlpha(grid),
    )
    assertEquals(
      "[0:#4]crop=6:5:0:0,setparams=range=pc,format=gray16le",
      tiledAlpha(HeifGrid(listOf(4L), 1, 1, Size(6, 5))),
    )
    assertEquals(
      listOf("-map", "0:#2", "-vf", "setparams=range=pc,format=gray16le") +
        listOf("-frames:v", "1", "-c:v", "png", "-f", "image2pipe", "-"),
      alphaFrame(HeifAlpha(2L, grid = null, premultiplied = false, fullRange = false, bitDepth = 8)),
    )
  }

  @Test
  fun `only a map that matches no stream reads as an unlisted alpha item`() {
    assertTrue(unlistedAlpha("Stream map '0:#2' matches no streams.\nTo ignore this, add a trailing '?' to the map."))
    assertFalse(unlistedAlpha("Invalid data found when processing input"))
  }

  private fun assertAlpha(
    item: HeifThumbnail,
    alpha: HeifAlphaPlane,
    expected: (Int, Int) -> Int,
  ) = assertAlpha(decoded(alphaStill(item, null)), decoded(alphaStill(item, alpha)), expected)

  // [drawn] carries [plain]'s colour at every texel and the alpha [expected] gives its column and row.
  private fun assertAlpha(
    plain: DecodedPicture,
    drawn: DecodedPicture,
    expected: (Int, Int) -> Int,
  ) {
    assertTrue(drawn.limits.isEmpty(), "${drawn.limits}")
    val colour = argb(plain)
    val texels = argb(drawn)
    val width = drawn.size.width
    for (at in texels.indices) {
      assertEquals(expected(at % width, at / width), texels[at] ushr ALPHA_SHIFT, "alpha of texel $at")
      assertEquals(colour[at] and RGB, texels[at] and RGB, "colour of texel $at")
    }
  }

  // The eight-bit alpha a limited range eight-bit [code] draws at.
  private fun expanded(code: Int): Int = (fullRangeAlpha(code * WIDE_PER_BYTE, 8) / WIDE * BYTE).roundToInt()

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return assertIs<OverlayPictureRead.Read<DecodedPicture>>(read, "$read").value
  }

  private fun argb(picture: DecodedPicture): IntArray = assertIs<StoredSamples.Argb>(picture.samples).argb

  private companion object {
    val YCGCO = CicpTag(1, 13, 8, true)
    val COLOUR_SHIFTS = listOf(16, 8, 0)
    const val ALPHA_SHIFT = 24
    const val BYTE = 0xFF
    const val RGB = 0xFFFFFF
    const val WIDE = 65535.0
    const val WIDE_PER_BYTE = 257
  }
}
