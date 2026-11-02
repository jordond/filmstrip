package dev.jordond.filmstrip.effects.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Half
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.RestatedHeif
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.ycbcrToRgb
import org.junit.Assume.assumeTrue
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Overlay pictures that describe their colour, decoded by Android to the samples their files store.
 *
 * Each picture carries a description Android may convert from when it is left in: an ICC profile, PNG gamma and
 * chromaticities, PNG `cICP`, and AVIF and HEIC `prof` and `nclx`. Read through [decode], each lands on its stored
 * samples and reports the colour core reads from its file. On a device because the decode is Android's.
 *
 * A test of a description this Android ignores anyway skips by name, since the decode would pass with or without the
 * hiding. The host byte tests in `HiddenColourDescriptionsHostTest` are the guard for those.
 */
class HiddenColourDescriptionsTest {
  // A half covered texel is where a premultiplied decode loses codes, so it reads back exactly only when the decode
  // keeps the codes straight.
  @Test
  fun aDecodeKeepsAHalfCoveredTexelStraight() {
    val picture = assertNotNull(ImageSource.Bytes(OverlayPixels.pngOf(SIDE, SOFT_TEXEL)).decode(context = null))

    assertEquals(SOFT_TEXEL.toUInt(), picture.bitmap.getPixel(1, 1).toUInt())
  }

  @Test
  fun everyIccTaggedPngDecodesToItsStoredCodesAndItsColour() {
    for (tagged in IccPictures.ALL) {
      val png = tagged.pngOf(SIDE)
      val picture = assertNotNull(ImageSource.Bytes(png).decode(context = null), "$tagged")

      assertSameLight(png, picture, "$tagged")
      assertEquals(tagged.texel.toUInt(), picture.bitmap.getPixel(1, 1).toUInt(), "$tagged was converted")
    }
  }

  @Test
  fun everyColourPngDecodesToItsStoredCodesAndItsColour() {
    for (tagged in ColourPictures.PNGS) {
      val png = tagged.pngOf(SIDE)
      val picture = assertNotNull(ImageSource.Bytes(png).decode(context = null), "$tagged")

      assertSameLight(png, picture, "$tagged")
      assertEquals(tagged.texel.toUInt(), picture.bitmap.getPixel(1, 1).toUInt(), "$tagged was converted")
    }
  }

  @Test
  fun aPngWithGammaAndChromaticitiesIsHiddenFromTheDecoder() {
    assertHidden(ColourPictures.GAMMA_DISPLAY_P3.pngOf(SIDE), ColourPictures.TEXEL, "gAMA and cHRM")
  }

  @Test
  fun aCicpPngIsHiddenFromADecoderThatReadsIt() {
    assertHidden(ColourPictures.CICP_DISPLAY_P3.pngOf(SIDE), ColourPictures.TEXEL, "cICP")
  }

  @Test
  fun anAvifNclxIsHiddenFromADecoderThatReadsIt() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    val file = ColourPictures.NCLX_DISPLAY_P3.bytes()

    assertHidden(file, decodeCentre(file.describedAsSrgb()), "AVIF nclx")
  }

  // A sixteen-bit picture comes back as half floats, which hold each sample within a sixteenth of an eight-bit code.
  @Test
  fun aSixteenBitPngDecodesToItsSamplesAtSixteenBits() {
    assumeTrue("Half float bitmaps arrive in API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    for (tagged in ColourPictures.HDR_PNGS) {
      val picture = assertNotNull(ImageSource.Bytes(tagged.pngOf(SIDE)).decode(context = null), "$tagged")

      assertEquals(Bitmap.Config.RGBA_F16, picture.bitmap.config, "$tagged")
      val halves = ShortArray(SIDE.width * SIDE.height * RGBA)
      picture.bitmap.copyPixelsToBuffer(ShortBuffer.wrap(halves))
      val read = (0 until RGBA).map { (Half.toFloat(halves[it]) * MAX_SAMPLE).roundToInt() }
      val off = read.indices.maxOf { abs(read[it] - tagged.texel[it]) }
      assertTrue(off <= HALF_FLOAT_STEP, "$tagged read $read where it stores ${tagged.texel}, $off off")
    }
  }

  @Test
  fun everyAvifDecodesToItsStoredSamplesAndItsColour() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    for (tagged in ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA) {
      val file = tagged.bytes()
      val picture = assertStoredSamples(file, "$tagged")

      // Android's AVIF decoder lands the file where its API level reads the matrix and range the file records.
      val landing = assertNotNull(file.landingHere(), "$tagged")
      assertEquals(landing.limits, picture.limits, "$tagged")
      val want = tagged.ycbcr.argbThrough(landing.matrix, landing.fullRange, file)
      val read = picture.bitmap.getPixel(picture.bitmap.width / 2, picture.bitmap.height / 2)
      val off = channelsOff(read, want)
      assertTrue(off <= CODED_TOLERANCE, "$tagged read ${read.hex()} where its YCbCr is ${want.hex()}, $off off")
    }
  }

  @Test
  fun everyHeicDecodesToItsStoredSamplesAndItsColour() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

    val first = ColourPictures.HEICS.first().bytes()
    val decodes = BitmapFactory.decodeByteArray(first, 0, first.size) != null
    assumeTrue(HEIC_FIXTURE_UNDECODED, decodes)

    for (tagged in ColourPictures.HEICS + ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA) {
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

    val picture = assertNotNull(ImageSource.Bytes(file).decode(context = null), "the $what picture did not decode")
    assertEquals(stored.toUInt(), picture.bitmap.centre().toUInt(), "the $what picture was converted")
  }

  // The stored samples of a YCbCr picture are what Android makes of the same file described as sRGB, its codestream
  // stating the nearest matrix its decoder reads of the picture's own, beside the primaries the decoder pairs with
  // that. Its nclx keeps the sRGB primaries, so the decoder converts nothing.
  private fun assertStoredSamples(
    file: ByteArray,
    what: String,
  ): DecodedPicture {
    val coding = assertNotNull(pictureYcbcrCoding(file), what)
    val avif = coding.codec == CodecKind.Av1
    val tenBit = coding.tenBitSamples
    val sdk = Build.VERSION.SDK_INT
    val srgb = file.describedAsSrgb()
    val restated =
      heifWithRestatedColour(srgb) { _, fullRange, _ ->
        checkNotNull(restatedForDecoder(coding.reading, fullRange, sdk, avif, tenBit)).copy(nclx = false)
      }
    val described =
      when (restated) {
        is RestatedHeif.Restated -> restated.bytes
        is RestatedHeif.Unrestated -> restated.bytes.also { assertSame(srgb, it, what) }
        is UnturnedHeif.Refused -> fail("$what was refused, ${restated.message}")
      }
    val stored = decodeCentre(described)
    val picture = assertNotNull(ImageSource.Bytes(file).decode(context = null), "$what did not decode")

    assertSameLight(file, picture, what)
    assertEquals(stored.toUInt(), picture.bitmap.centre().toUInt(), "$what was converted")
    return picture
  }

  // The colour a decode reports is held to core's read of the file through the light it gives a spread of texels, so
  // two pictures without a colour do not pass by both being null.
  private fun assertSameLight(
    file: ByteArray,
    picture: DecodedPicture,
    what: String,
  ) {
    assertContentEquals(pictureLight(pictureColour(file), PROBE), pictureLight(picture.colour, PROBE), "$what's colour")
  }

  private fun decodeCentre(file: ByteArray): Int {
    val options = BitmapFactory.Options().apply { inPremultiplied = false }
    val bitmap = assertNotNull(BitmapFactory.decodeByteArray(file, 0, file.size, options), "the picture did not decode")
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
    val PROBE = intArrayOf(ColourPictures.TEXEL, 0xFF204080.toInt(), 0xFFC0A060.toInt(), 0xFFFFFFFF.toInt())
  }
}

/**
 * This HEIF with its `colr prof` box renamed to a type no reader knows and its `nclx` primaries and transfer set to
 * sRGB's, the matrix and range kept, or itself when it carries neither.
 */
internal fun ByteArray.describedAsSrgb(): ByteArray =
  copyOf().apply {
    indexOf("prof").takeIf { it >= 0 }?.let { "xxxx".encodeToByteArray().copyInto(this, it - BOX_TYPE_BEFORE_COLOUR) }
    indexOf("nclx").takeIf { it >= 0 }?.let { byteArrayOf(0, SRGB_PRIMARIES, 0, SRGB_TRANSFER).copyInto(this, it + 4) }
  }

/**
 * The packed opaque ARGB colour these luma, blue difference and red difference codes turn into through the ITU-T H.273
 * [matrix] beside [primaries] and [transfer] at full or limited range, as [ycbcrToRgb] decodes them.
 */
internal fun List<Int>.argbThrough(
  matrix: Int,
  primaries: Int,
  transfer: Int,
  fullRange: Boolean,
): Int =
  ycbcrToRgb(this[0], this[1], this[2], matrix, primaries, transfer, fullRange)
    .fold(OPAQUE) { argb, code -> (argb shl 8) or code }

/**
 * The packed opaque ARGB colour these codes turn into through [matrix] at full or limited range, as [argbThrough] gives
 * it beside the primaries and transfer core reads for [picture].
 */
internal fun List<Int>.argbThrough(
  matrix: Int,
  fullRange: Boolean,
  picture: ByteArray,
): Int {
  val coding = checkNotNull(pictureYcbcrCoding(picture)) { "core reads no YCbCr coding for this picture" }
  return argbThrough(matrix, coding.primaries, coding.transfer, fullRange)
}

/**
 * The most any of red, green and blue differ between two packed colours.
 */
internal fun channelsOff(
  read: Int,
  want: Int,
): Int = intArrayOf(16, 8, 0).maxOf { abs((read ushr it and 0xFF) - (want ushr it and 0xFF)) }

internal fun Int.hex(): String = toUInt().toString(16)

/**
 * How Android's decoder on this device lands this picture, as [forAndroidDecoder] finds when it prepares it.
 *
 * Null when the picture is neither an AVIF nor a HEIC, or its landing is left to the decoder.
 */
internal fun ByteArray.landingHere(): DecoderLanding? = forAndroidDecoder(Build.VERSION.SDK_INT).landing

/**
 * How Android's decoder on this device lands the reading core gives this AVIF or HEIC, at [fullRange] or the range core
 * reads for it, as [decoderLanding] gives it.
 *
 * Null when core refuses the picture, as for one naming the identity matrix on subsampled samples or a matrix core
 * reads no picture through.
 */
internal fun ByteArray.ownLanding(fullRange: Boolean? = null): DecoderLanding? {
  val coding = checkNotNull(pictureYcbcrCoding(this)) { "core reads no YCbCr coding for this picture" }
  if (coding.refusal != null) return null
  val range = fullRange ?: coding.fullRange
  return decoderLanding(coding.reading, range, Build.VERSION.SDK_INT, coding.avif, coding.tenBitSamples)
}

/**
 * Whether a picture whose [ownLanding] is this is refused rather than drawn, because core refuses it when this is null
 * or because it lands past the bound.
 */
internal val DecoderLanding?.refused: Boolean
  get() = this == null || refusal != null

/**
 * What reading this picture as an overlay does misses when it should be refused, as [refused] says of [landing], or
 * null when it comes to a [RefusedPicture] naming the [DecoderLanding.refusal] of [landing], or core's own refusal
 * when [landing] is null.
 */
internal fun ByteArray.refusalMiss(
  what: String,
  landing: DecoderLanding?,
): String? {
  val want = landing?.refusal ?: pictureYcbcrCoding(this)?.refusal
  val picture = ImageSource.Bytes(this).read(context = null)
  val reason = (picture as? RefusedPicture)?.reason
  return "$what came to $picture, ${reason ?: "drawn"}, where it should be refused as: $want".takeIf { reason != want }
}

/**
 * Reads this picture as an overlay does, or null when it comes to anything but a [DecodedPicture].
 */
internal fun ImageSource.decode(context: Context?): DecodedPicture? = read(context) as? DecodedPicture

private fun ByteArray.indexOf(type: String): Int = String(this, Charsets.ISO_8859_1).indexOf(type)

private const val OPAQUE = 0xFF

// A colr box's type sits right before its colour type.
private const val BOX_TYPE_BEFORE_COLOUR = 4
private const val SRGB_PRIMARIES: Byte = 1
private const val SRGB_TRANSFER: Byte = 13
