package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PLANES_FILL_REASON
import dev.jordond.filmstrip.ffmpeg.internal.picture.TRANSLUCENT_FILL_REASON
import dev.jordond.filmstrip.ffmpeg.internal.picture.UNASSEMBLED_REASON
import dev.jordond.filmstrip.ffmpeg.internal.picture.assembledOverlay
import dev.jordond.filmstrip.ffmpeg.internal.picture.canvasColor
import dev.jordond.filmstrip.ffmpeg.internal.picture.overlayFillReason
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.HeifCanvasFill
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.HeifOverlayInput
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifPicture
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test

/**
 * AVIF and HEIC stills whose primary item is an `iovl`, decoded by this ffmpeg.
 *
 * Each canvas is three inputs wide and two high, with three copies of one flat still placed so that some of the canvas
 * shows its fill, including texels in the middle column. A texel an input covers holds what the still alone decodes
 * to, and one none covers holds the canvas color within [PICTURE_DRIFT].
 */
class FfmpegPictureOverlayTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `an AVIF overlay draws each input at its offset over its canvas color`() {
    if (!fixture.available()) return
    assertOverlay("AVIF", "avif", ColorPictures.NCLX_BT709_LIMITED)
  }

  @Test
  fun `a HEIC overlay draws each input at its offset over its canvas color`() {
    if (!fixture.available()) return
    assertOverlay("HEIC", "heic", ColorPictures.HEIC_NCLX_BT709_LIMITED)
  }

  @Test
  fun `an input that starts above and left of the canvas is cut at its edge`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val bytes = overlay("avif", picture, listOf(-side / 2 to -side / 2), Size(side, side), wide = true)
    val drawn = decoded(bytes)
    drawn.size shouldBe Size(side, side)
    withClue("the top left, inside the input") { texel(drawn, 0, 0) shouldBe centreOf(decoded(picture.bytes())) }
    withClue("the bottom right, past the input") { assertFill(texel(drawn, side - 1, side - 1)) }
  }

  @Test
  fun `a translucent canvas color that shows is refused and one the inputs cover is drawn`() {
    if (!fixture.available()) return
    val picture = ColorPictures.NCLX_BT709_LIMITED
    val side = picture.thumbnail().size.width
    val clear = listOf(0, 0, 0, 0)
    val shown = overlay("avif", picture, listOf(0 to 0), Size(2 * side, side), clear)
    val read = decoder.decode(ImageSource.ofBytes(shown))
    read.shouldBeInstanceOf<OverlayPictureDecode.Undrawn>().reason shouldBe TRANSLUCENT_FILL_REASON
    val covered = overlay("avif", picture, listOf(0 to 0, side to 0), Size(2 * side, side), clear)
    decoded(covered).size shouldBe Size(2 * side, side)
  }

  @Test
  fun `an overlay with no inputs is refused rather than assembled`() {
    val bytes = overlay("avif", ColorPictures.NCLX_BT709_LIMITED, emptyList(), Size(8, 8))
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe UNASSEMBLED_REASON
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    measured.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe UNASSEMBLED_REASON
  }

  @Test
  fun `an overlay graph reads each input alone and shifts past the farthest one left or above`() {
    val fill = HeifCanvasFill(65535, 32896, 0, 65535)
    val size = Size(8, 8)
    val many =
      HeifOverlay(listOf(HeifOverlayInput(2, 0, 0, size), HeifOverlayInput(3, -4, 10, size)), Size(20, 16), fill)
    assembledOverlay(many, "null", rgb = false) shouldBe
      "[0:#2]null[input0];[0:#3]null[input1];[input0][input1]xstack=inputs=2:layout=4_0|0_10:fill=0xFF8000," +
      "pad=w='max(iw,24)':h='max(ih,16)':x=0:y=0:color=0xFF8000,crop=20:16:4:0"
    val one = HeifOverlay(listOf(HeifOverlayInput(5, 3, -2, size)), Size(12, 10), fill)
    assembledOverlay(one, "null", rgb = true) shouldBe
      "[0:#5]null,format=pix_fmts=rgb24|rgb48le[input0];[input0]pad=w='max(iw+3,12)':h='max(ih+0,12)':x=3:y=0:" +
      "color=0xFF8000,crop=12:10:0:2"
  }

  @Test
  fun `a canvas color rounds each channel to the nearest eight bit code`() {
    canvasColor(HeifCanvasFill(0, 128, 65535, 65535)) shouldBe "0x0000FF"
    canvasColor(HeifCanvasFill(32767, 32768, 257 * 200 + 128, 0)) shouldBe "0x7F80C8"
  }

  @Test
  fun `only a canvas color that shows can refuse an overlay`() {
    val size = Size(8, 8)
    val clear = HeifCanvasFill(0, 0, 0, 0)
    val opaque = HeifCanvasFill(0, 0, 0, 65535)
    val covering = listOf(HeifOverlayInput(2, 0, 0, size), HeifOverlayInput(3, 8, 0, size))
    overlayFillReason(HeifOverlay(covering, Size(16, 8), clear), planes = true).shouldBeNull()
    overlayFillReason(HeifOverlay(covering, Size(16, 9), clear), planes = false) shouldBe TRANSLUCENT_FILL_REASON
    overlayFillReason(HeifOverlay(covering, Size(17, 8), opaque), planes = true) shouldBe PLANES_FILL_REASON
    overlayFillReason(HeifOverlay(covering, Size(17, 8), opaque), planes = false).shouldBeNull()
  }

  // [picture] as an overlay of three copies on a canvas three of them wide and two high, checked at an input's corner,
  // the middle of the canvas, the fill beside it and the fill below it.
  private fun assertOverlay(
    name: String,
    brand: String,
    picture: HeifPicture,
  ) {
    val side = picture.thumbnail().size.width
    val canvas = Size(3 * side, 2 * side)
    val bytes = overlay(brand, picture, listOf(0 to 0, 2 * side to side, side to side / 2), canvas)
    val drawn = decoded(bytes)
    val input = centreOf(decoded(picture.bytes()))
    withClue("$name size") { drawn.size shouldBe canvas }
    withClue("$name first input") { texel(drawn, 0, 0) shouldBe input }
    withClue("$name second input") { texel(drawn, 3 * side - 1, 2 * side - 1) shouldBe input }
    val middle = texel(drawn, canvas.width / 2, canvas.height / 2)
    withClue("$name middle, inside the third input") { middle shouldBe input }
    withClue("$name fill right of the middle") { assertFill(texel(drawn, 5 * side / 2, side / 2)) }
    withClue("$name fill below the middle") { assertFill(texel(drawn, canvas.width / 2, 3 * side / 2)) }
    withClue("$name fill at the third input's bottom edge") { assertFill(texel(drawn, side, 3 * side / 2)) }
    val measured = decoder.measure(ImageSource.ofBytes(bytes))
    withClue("$name measured: $measured") { (measured as? OverlayPictureRead.Read)?.value shouldBe canvas }
  }

  private fun overlay(
    brand: String,
    picture: HeifPicture,
    offsets: List<Pair<Int, Int>>,
    canvas: Size,
    fill: List<Int> = FILL.map { it * WIDE_PER_BYTE } + WIDE,
    wide: Boolean = false,
  ): ByteArray = HeifOverlays.overlay(brand, picture.thumbnail(), offsets, canvas, fill, picture.nclx, wide)

  private fun assertFill(argb: Int) {
    for ((channel, expected) in FILL.withIndex()) {
      val read = argb ushr (16 - 8 * channel) and 0xFF
      withClue("channel $channel of ${argb.toUInt().toString(16)}") {
        abs(read - expected) shouldBeLessThanOrEqual PICTURE_DRIFT
      }
    }
    withClue("the fill is opaque") { argb ushr 24 shouldBe 0xFF }
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return withClue("this ffmpeg decoded nothing: $read") {
      (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
    }
  }

  private fun texel(
    decoded: DecodedPicture,
    x: Int,
    y: Int,
  ): Int {
    val argb =
      withClue("an eight-bit still decoded deeper") {
        decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>()
      }.argb
    return argb[y * decoded.size.width + x]
  }

  private fun centreOf(decoded: DecodedPicture): Int = texel(decoded, decoded.size.width / 2, decoded.size.height / 2)

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")

    // A saturated orange, so a fill painted through another matrix than the picture's lands well off it.
    val FILL = listOf(230, 120, 30)
    const val WIDE_PER_BYTE = 257
    const val WIDE = 65535
  }
}
