package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Half
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.Assume.assumeTrue
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.fail

/**
 * Overlay pictures that describe their color, decoded by Android to the samples their files store.
 *
 * Each picture carries a description Android may convert from when it is left in: an ICC profile, PNG gamma and
 * chromaticities, PNG `cICP`, and AVIF and HEIC `prof` and `nclx`. Read through [decode], each lands on its stored
 * samples and reports the color core reads from its file. On a device because the decode is Android's.
 *
 * A test of a description this Android ignores anyway skips by name, since the decode would pass with or without the
 * hiding. The host byte tests in `HiddenColorDescriptionsHostTest` are the guard for those.
 */
class HiddenColorDescriptionsTest {
  // A half covered texel is where a premultiplied decode loses codes, so it reads back exactly only when the decode
  // keeps the codes straight.
  @Test
  fun aDecodeKeepsAHalfCoveredTexelStraight() {
    val picture = ImageSource.Bytes(OverlayPixels.pngOf(SIDE, SOFT_TEXEL)).decode(context = null).shouldNotBeNull()

    picture.bitmap.getPixel(1, 1).toUInt() shouldBe SOFT_TEXEL.toUInt()
  }

  @Test
  fun everyIccTaggedPngDecodesToItsStoredCodesAndItsColor() {
    for (tagged in IccPictures.ALL) {
      val png = tagged.pngOf(SIDE)
      val picture = withClue("$tagged") { ImageSource.Bytes(png).decode(context = null).shouldNotBeNull() }

      assertSameLight(png, picture, "$tagged")
      withClue("$tagged was converted") { picture.bitmap.getPixel(1, 1).toUInt() shouldBe tagged.texel.toUInt() }
    }
  }

  @Test
  fun everyColorPngDecodesToItsStoredCodesAndItsColor() {
    for (tagged in ColorPictures.PNGS) {
      val png = tagged.pngOf(SIDE)
      val picture = withClue("$tagged") { ImageSource.Bytes(png).decode(context = null).shouldNotBeNull() }

      assertSameLight(png, picture, "$tagged")
      withClue("$tagged was converted") { picture.bitmap.getPixel(1, 1).toUInt() shouldBe tagged.texel.toUInt() }
    }
  }

  @Test
  fun aPngWithGammaAndChromaticitiesIsHiddenFromTheDecoder() {
    assertHidden(ColorPictures.GAMMA_DISPLAY_P3.pngOf(SIDE), ColorPictures.TEXEL, "gAMA and cHRM")
  }

  @Test
  fun aCicpPngIsHiddenFromADecoderThatReadsIt() {
    assertHidden(ColorPictures.CICP_DISPLAY_P3.pngOf(SIDE), ColorPictures.TEXEL, "cICP")
  }

  @Test
  fun anAvifNclxIsHiddenFromADecoderThatReadsIt() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    val file = ColorPictures.NCLX_DISPLAY_P3.bytes()

    assertHidden(file, decodeCentre(file.describedAsSrgb()), "AVIF nclx")
  }

  // A sixteen-bit picture comes back as half floats, which hold each sample within a sixteenth of an eight-bit code.
  @Test
  fun aSixteenBitPngDecodesToItsSamplesAtSixteenBits() {
    assumeTrue("Half float bitmaps arrive in API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    for (tagged in ColorPictures.HDR_PNGS) {
      val picture =
        withClue("$tagged") {
          ImageSource.Bytes(tagged.pngOf(SIDE)).decode(context = null).shouldNotBeNull()
        }

      withClue("$tagged") { picture.bitmap.config shouldBe Bitmap.Config.RGBA_F16 }
      val halves = ShortArray(SIDE.width * SIDE.height * RGBA)
      picture.bitmap.copyPixelsToBuffer(ShortBuffer.wrap(halves))
      val read = (0 until RGBA).map { (Half.toFloat(halves[it]) * MAX_SAMPLE).roundToInt() }
      val off = read.indices.maxOf { abs(read[it] - tagged.texel[it]) }
      withClue("$tagged read $read where it stores ${tagged.texel}, $off off") {
        (off <= HALF_FLOAT_STEP) shouldBe true
      }
    }
  }

  @Test
  fun everyAvifDecodesToItsStoredSamplesAndItsColor() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    for (tagged in ColorPictures.AVIFS + ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA) {
      val file = tagged.bytes()
      val picture = assertStoredSamples(file, "$tagged")

      // Android's AVIF decoder lands the file where its API level reads the matrix and range the file records.
      val conversion = withClue("$tagged") { file.conversionHere().shouldNotBeNull() }
      withClue("$tagged") { picture.limits shouldBe conversion.limits }
      val want = tagged.ycbcr.argbThrough(conversion.matrix, conversion.fullRange, file)
      val read = picture.bitmap.getPixel(picture.bitmap.width / 2, picture.bitmap.height / 2)
      val off = channelsOff(read, want)
      withClue("$tagged read ${read.hex()} where its YCbCr is ${want.hex()}, $off off") {
        (off <= CODED_TOLERANCE) shouldBe true
      }
    }
  }

  @Test
  fun everyHeicDecodesToItsStoredSamplesAndItsColor() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

    val first = ColorPictures.HEICS.first().bytes()
    val decodes = BitmapFactory.decodeByteArray(first, 0, first.size) != null
    assumeTrue(HEIC_FIXTURE_UNDECODED, decodes)

    for (tagged in ColorPictures.HEICS + ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA) {
      assertStoredSamples(tagged.bytes(), "$tagged")
    }
  }

  // Skips when the description makes no difference to this Android's decode, since the hiding is then untested here.
  private fun assertHidden(
    file: ByteArray,
    stored: Int,
    what: String,
  ) {
    val unhidden = decodeCentre(file)
    assumeTrue("This Android decodes $what as if it were absent", unhidden != stored)

    val picture =
      withClue("the $what picture did not decode") {
        ImageSource.Bytes(file).decode(context = null).shouldNotBeNull()
      }
    withClue("the $what picture was converted") { picture.bitmap.centre().toUInt() shouldBe stored.toUInt() }
  }

  // The stored samples of a YCbCr picture are what Android makes of the same file described as sRGB, its codestream
  // stating the nearest matrix its decoder reads of the picture's own, beside the primaries the decoder pairs with
  // that. Its nclx keeps the sRGB primaries, so the decoder converts nothing.
  private fun assertStoredSamples(
    file: ByteArray,
    what: String,
  ): DecodedPicture {
    val coding = withClue(what) { pictureYcbcrCoding(file).shouldNotBeNull() }
    val avif = coding.codec == CodecKind.Av1
    val tenBit = coding.tenBitSamples
    val sdk = Build.VERSION.SDK_INT
    val srgb = file.describedAsSrgb()
    val restated =
      heifWithRestatedColor(srgb) { _, fullRange, _ ->
        checkNotNull(restatedForDecoder(coding.reading, fullRange, sdk, avif, tenBit)).copy(nclx = false)
      }
    val described =
      when (restated) {
        is RestatedHeif.Restated -> restated.bytes
        is RestatedHeif.Unrestated -> restated.bytes.also { withClue(what) { it shouldBeSameInstanceAs srgb } }
        is UnturnedHeif.Refused -> fail("$what was refused, ${restated.message}")
      }
    val stored = decodeCentre(described)
    val picture = withClue("$what did not decode") { ImageSource.Bytes(file).decode(context = null).shouldNotBeNull() }

    assertSameLight(file, picture, what)
    withClue("$what was converted") { picture.bitmap.centre().toUInt() shouldBe stored.toUInt() }
    return picture
  }

  // The color a decode reports is held to core's read of the file through the light it gives a spread of texels, so
  // two pictures without a color do not pass by both being null.
  private fun assertSameLight(
    file: ByteArray,
    picture: DecodedPicture,
    what: String,
  ) {
    withClue("$what's color") { pictureLight(picture.color, PROBE) shouldBe pictureLight(pictureColor(file), PROBE) }
  }

  private fun decodeCentre(file: ByteArray): Int {
    val options = BitmapFactory.Options().apply { inPremultiplied = false }
    val bitmap =
      withClue("the picture did not decode") {
        BitmapFactory.decodeByteArray(file, 0, file.size, options).shouldNotBeNull()
      }
    return bitmap.centre().also { bitmap.recycle() }
  }

  private fun Bitmap.centre(): Int = getPixel(width / 2, height / 2)

  private companion object {
    val SIDE = Size(4, 4)
    const val SOFT_TEXEL = 0x8040C080.toInt()
    const val RGBA = 4
    const val MAX_SAMPLE = 65535f
    const val HALF_FLOAT_STEP = 16
    const val CODED_TOLERANCE = 2
    val PROBE = intArrayOf(ColorPictures.TEXEL, 0xFF204080.toInt(), 0xFFC0A060.toInt(), 0xFFFFFFFF.toInt())
  }
}

/**
 * This HEIF with its `colr prof` box renamed to a type no reader knows and its `nclx` primaries and transfer set to
 * sRGB's, the matrix and range kept, or itself when it carries neither.
 */
internal fun ByteArray.describedAsSrgb(): ByteArray =
  copyOf().apply {
    indexOf("prof").takeIf { it >= 0 }?.let { "xxxx".encodeToByteArray().copyInto(this, it - BOX_TYPE_BEFORE_COLOR) }
    indexOf("nclx").takeIf { it >= 0 }?.let { byteArrayOf(0, SRGB_PRIMARIES, 0, SRGB_TRANSFER).copyInto(this, it + 4) }
  }

private fun ByteArray.indexOf(type: String): Int = String(this, Charsets.ISO_8859_1).indexOf(type)

// A colr box's type sits right before its color type.
private const val BOX_TYPE_BEFORE_COLOR = 4
private const val SRGB_PRIMARIES: Byte = 1
private const val SRGB_TRANSFER: Byte = 13
