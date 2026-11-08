package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.color.IDENTITY_MATRIX
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.pictureCodesFor
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.ycbcrCodes
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.canvasTexels
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifCanvasFill
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The canvas color of AVIF stills whose primary item is an `iovl`, decoded by this ffmpeg.
 *
 * The fill is sRGB, and each test reads it where no input covers the canvas against the codes core's shared functions
 * give for the picture's own color and coding.
 */
class FfmpegPictureOverlayColorMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a Display P3 overlay paints its sRGB canvas color in the codes core converts it to`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_DISPLAY_P3
    val side = picture.thumbnail().size.width
    val bytes =
      HeifOverlays.overlay(
        "avif",
        picture.thumbnail(),
        listOf(0 to 0),
        Size(2 * side, side),
        MID_FILL,
        picture.nclx,
      )
    val codes = pictureCodesFor(MID_FILL[0], MID_FILL[1], MID_FILL[2], pictureColor(bytes))
    val expected = codes.map { (it / WIDE_PER_BYTE.toDouble()).roundToInt() }
    withClue("$expected is the fill unconverted") { expected shouldNotBe MID_FILL.take(3).map { it / WIDE_PER_BYTE } }
    assertFill(bytes, side, expected)
  }

  @Test
  fun `a one input overlay reads at the same color as a two input one of the same tiles`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_DISPLAY_P3
    val tile = picture.thumbnail()
    val side = tile.size.width
    val canvas = Size(2 * side, side)
    val one = HeifOverlays.overlay("avif", tile, listOf(0 to 0), canvas, MID_FILL, picture.nclx)
    val two = HeifOverlays.overlay("avif", tile, listOf(0 to 0, side to 0), canvas, MID_FILL, picture.nclx)
    val read = { bytes: ByteArray ->
      val texel = texelOf(decodedBy(decoder, bytes), side / 2, side / 2)
      pictureSrgbCodes(pictureColor(bytes), intArrayOf(texel)).single()
    }
    withClue("the tile is not stored in sRGB") {
      read(one) shouldNotBe
        texelOf(decodedBy(decoder, one), side / 2, side / 2)
    }
    read(one) shouldBe read(two)
  }

  // A reader draws an AVIF or HEIC in the color core reads out of its container, which for an iovl is its own colr
  // ahead of its inputs', so the fill converts into that one and not into what ffmpeg carries from the inputs.
  @Test
  fun `an overlay tagged apart from its inputs paints its canvas color in the overlay's own color`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val nclx = CicpTag(DISPLAY_P3, SRGB_TRANSFER, BT709_MATRIX, fullRange = false)
    val bytes = HeifOverlays.overlay("avif", picture.thumbnail(), listOf(0 to 0), Size(2 * side, side), MID_FILL, nclx)
    val color = pictureColor(bytes).shouldNotBeNull()
    withClue("the inputs alone are tagged otherwise") { pictureColor(picture.bytes()) shouldNotBe color }
    val expected =
      pictureCodesFor(MID_FILL[0], MID_FILL[1], MID_FILL[2], color).map { (it / WIDE_PER_BYTE.toDouble()).roundToInt() }
    assertFill(bytes, side, expected)
  }

  @Test
  fun `a canvas rounds each channel to the nearest eight bit code`() {
    val fill = HeifCanvasFill(32767, 32768, 257 * 200 + 128, 0x1234)
    val texel = { format: String -> canvasTexels(fill, color = null, format).shouldNotBeNull().single().toList() }
    texel("rgb24") shouldBe bytes(0x7F, 0x80, 0xC8)
    texel("rgba") shouldBe bytes(0x7F, 0x80, 0xC8, 0x12)
    canvasTexels(fill, color = null, "yuv444p").shouldBeNull()
  }

  private fun bytes(vararg values: Int): List<Byte> = values.map { it.toByte() }

  @Test
  fun `a ten bit planes route overlay paints its canvas as the plane codes core gives`() {
    if (!fixture.available()) return
    val tile = HeifThumbnail("av01", TEN_BIT_AV1C, TEN_BIT_CODESTREAM, Size(8, 8))
    for (fullRange in listOf(true, false)) {
      val nclx = CicpTag(DISPLAY_P3, SRGB_TRANSFER, IDENTITY_MATRIX, fullRange)
      val bytes = HeifOverlays.overlay("avif", tile, listOf(0 to 0), Size(16, 8), MID_FILL, nclx)
      val coding = pictureYcbcrCoding(bytes).shouldNotBeNull()
      val bitDepth = coding.bitDepth.shouldNotBeNull()
      withClue("$nclx reads its planes") { pictureRoute(coding).shouldBeInstanceOf<PictureRoute.Planes>() }
      val (red, green, blue) = pictureCodesFor(MID_FILL[0], MID_FILL[1], MID_FILL[2], pictureColor(bytes))
      val codes = coding.reading.ycbcrCodes(red, green, blue, bitDepth, coding.fullRange)
      val expected = coding.reading.evaluator(bitDepth, coding.fullRange).rgb(codes[0], codes[1], codes[2])
      val drawn = decodedBy(decoder, bytes)
      val rgba = drawn.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba
      val at = (4 * drawn.size.width + 12) * 4
      withClue("$nclx, canvas codes ${codes.toList()}") {
        List(3) { rgba[at + it].toInt() and 0xFFFF } shouldBe expected.map { (it * WIDE).roundToInt() }
        rgba[at + 3].toInt() and 0xFFFF shouldBe WIDE
      }
      val input = (4 * drawn.size.width + 4) * 4
      withClue("$nclx, inside the input") {
        List(3) { rgba[input + it].toInt() and 0xFFFF } shouldNotBe List(3) { rgba[at + it].toInt() and 0xFFFF }
      }
    }
  }

  // The canvas of [bytes], an overlay of one input [side] texels wide on a canvas twice that, reads [expected] in the
  // middle of its uncovered half, each channel within one code.
  private fun assertFill(
    bytes: ByteArray,
    side: Int,
    expected: List<Int>,
  ) {
    val fill = texelOf(decodedBy(decoder, bytes), side + side / 2, side / 2)
    for ((channel, wanted) in expected.withIndex()) {
      val read = fill ushr (16 - 8 * channel) and 0xFF
      withClue("channel $channel of ${fill.toUInt().toString(16)}, $expected") {
        abs(read - wanted) shouldBeLessThanOrEqual 1
      }
    }
  }

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)

    // A mid saturation sRGB fill, neither a primary nor white, that Display P3 stores in other codes.
    val MID_FILL = listOf(0xC000, 0x7000, 0x5000, 65535)
    const val WIDE = 65535
    const val WIDE_PER_BYTE = 257
    const val DISPLAY_P3 = 12
    const val SRGB_TRANSFER = 13
    const val BT709_MATRIX = 1

    // The ten-bit 4:4:4 AV1 still the identity tests decode, 600, 300 and 900 at every sample, and its av1C.
    val TEN_BIT_CODESTREAM = hex("0a053808bf6e903214100000000ff88ee1f7cb9ac19a970152a7393a20")
    val TEN_BIT_AV1C = hex("0000000c" + "61763143" + "81204000")

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
