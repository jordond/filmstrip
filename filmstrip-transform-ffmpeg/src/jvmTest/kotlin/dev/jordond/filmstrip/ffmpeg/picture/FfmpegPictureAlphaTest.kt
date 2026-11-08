package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.fullRangeAlpha
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.ffmpeg.support.alphaStill
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * AVIF and HEIC stills carrying an alpha item beside their color item, decoded by this ffmpeg.
 *
 * Each still's alpha item is one of [AlphaPlanes], and every texel's alpha is checked against the code the plane
 * stores at its column and row, expanded through [fullRangeAlpha] for a limited range plane. Its color is checked
 * against the same still decoded without its alpha item.
 */
class FfmpegPictureAlphaTest {
  private val decoder = FfmpegPictureDecoder(hostFfmpeg)

  @Test
  fun `an AVIF alpha item lands as the alpha of every texel`() {
    val item = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    assertAlpha(item, AlphaPlanes.avif(), AlphaPlanes::avifAlpha)
  }

  @Test
  fun `a HEIC alpha item lands as the alpha of every texel`() {
    val item = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    assertAlpha(item, AlphaPlanes.heic(), AlphaPlanes::heicAlpha)
  }

  @Test
  fun `a limited range AVIF alpha item lands expanded to full range`() {
    val item = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    assertAlpha(item, AlphaPlanes.avif(fullRange = false)) { x, y -> expanded(AlphaPlanes.avifAlpha(x, y)) }
  }

  @Test
  fun `a limited range HEIC alpha item lands expanded to full range`() {
    val item = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    assertAlpha(item, AlphaPlanes.heic(fullRange = false)) { x, y -> expanded(AlphaPlanes.heicAlpha(x, y)) }
  }

  @Test
  fun `a limited range code stretches from clear at 16 to opaque at 235`() {
    fullRangeAlpha(16 * WIDE_PER_BYTE, 8) shouldBe 0
    fullRangeAlpha(235 * WIDE_PER_BYTE, 8) shouldBe WIDE.toInt()
    fullRangeAlpha(128 * WIDE_PER_BYTE, 8) shouldBe (112 * WIDE / 219).roundToInt()
    fullRangeAlpha(4 * WIDE_PER_BYTE, 8) shouldBe 0
    fullRangeAlpha(250 * WIDE_PER_BYTE, 8) shouldBe WIDE.toInt()
    val tenBit = { code: Int -> (code * WIDE / 1023).roundToInt() }
    fullRangeAlpha(tenBit(64), 10) shouldBe 0
    fullRangeAlpha(tenBit(940), 10) shouldBe WIDE.toInt()
    fullRangeAlpha(tenBit(502), 10) shouldBe (438 * WIDE / 876).roundToInt()
  }

  @Test
  fun `an AVIF decoded from its planes takes its alpha item too`() {
    val item = ColorPictures.NCLX_DISPLAY_P3.thumbnail()
    val ycgco = { alpha: HeifAlphaPlane? ->
      HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, YCGCO, alpha = alpha)
    }
    pictureRoute(pictureYcbcrCoding(ycgco(null))).shouldBeInstanceOf<PictureRoute.Planes>()
    assertAlpha(decoded(ycgco(null)), decoded(ycgco(AlphaPlanes.avif())), AlphaPlanes::avifAlpha)
  }

  @Test
  fun `a premultiplied AVIF comes back straight`() {
    val item = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    val plain = argb(decoded(alphaStill(item, null)))
    val straight = argb(decoded(alphaStill(item, AlphaPlanes.avif(premultiplied = true))))
    for (at in plain.indices) {
      val alpha = AlphaPlanes.avifAlpha(at % item.size.width, at / item.size.width)
      withClue("alpha of texel $at") { (straight[at] ushr ALPHA_SHIFT) shouldBe alpha }
      for (shift in COLOR_SHIFTS) {
        val stored = plain[at] ushr shift and BYTE
        val expected = (minOf(1.0, stored / BYTE.toDouble() / (alpha * WIDE_PER_BYTE / WIDE)) * BYTE).roundToInt()
        withClue("channel at $shift of texel $at") { (straight[at] ushr shift and BYTE) shouldBe expected }
      }
    }
  }

  @Test
  fun `an AVIF grid assembles its alpha tiles in rows and crops them to its size`() {
    val picture = ColorPictures.NCLX_UNSPECIFIED
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
    drawn.size shouldBe size
    val tile = item.size.width
    assertAlpha(decoded(grid(null)), drawn) { x, y ->
      AlphaPlanes.avifTileAlpha(x / tile + 3 * (y / tile), x % tile, y % tile)
    }
  }

  private fun assertAlpha(
    item: HeifThumbnail,
    alpha: HeifAlphaPlane,
    expected: (Int, Int) -> Int,
  ) = assertAlpha(decoded(alphaStill(item, null)), decoded(alphaStill(item, alpha)), expected)

  // [drawn] carries [plain]'s color at every texel and the alpha [expected] gives its column and row.
  private fun assertAlpha(
    plain: DecodedPicture,
    drawn: DecodedPicture,
    expected: (Int, Int) -> Int,
  ) {
    withClue("${drawn.limits}") { drawn.limits.isEmpty().shouldBeTrue() }
    val color = argb(plain)
    val texels = argb(drawn)
    val width = drawn.size.width
    for (at in texels.indices) {
      withClue("alpha of texel $at") { (texels[at] ushr ALPHA_SHIFT) shouldBe expected(at % width, at / width) }
      withClue("color of texel $at") { (texels[at] and RGB) shouldBe (color[at] and RGB) }
    }
  }

  // The eight-bit alpha a limited range eight-bit [code] draws at.
  private fun expanded(code: Int): Int = (fullRangeAlpha(code * WIDE_PER_BYTE, 8) / WIDE * BYTE).roundToInt()

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return withClue("$read") { read.shouldBeInstanceOf<OverlayPictureRead.Read<DecodedPicture>>() }.value
  }

  private fun argb(picture: DecodedPicture): IntArray = picture.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb

  private companion object {
    val YCGCO = CicpTag(1, 13, 8, true)
    val COLOR_SHIFTS = listOf(16, 8, 0)
    const val ALPHA_SHIFT = 24
    const val BYTE = 0xFF
    const val RGB = 0xFFFFFF
    const val WIDE = 65535.0
    const val WIDE_PER_BYTE = 257
  }
}
