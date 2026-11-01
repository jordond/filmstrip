package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.IccPictures
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.CoreImage.kCIImageColorSpace
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.allKeys
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Colour described pictures decoded by the Apple overlay raster and read back as the image each frame kind draws.
 *
 * ICC tagged pictures, opaque and at half alpha, and every [ColourPictures] PNG enter an SDR frame at the codes
 * [pictureSrgbCodes] gives their texel and an HDR frame at the signal [pictureLight] puts it at, each read through the
 * description core reads out of the same bytes. Nothing here needs an encoder, so the HDR answer is asserted on every
 * Apple target.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class OverlayPictureProfileTest {
  @Test
  fun `the unmanaged options are a Foundation dictionary keyed by strings`() {
    val options = assertIs<NSDictionary>(unmanagedOptions())

    assertEquals(1uL, options.count)
    assertIs<NSNull>(options.objectForKey(checkNotNull(kCIImageColorSpace)))
    options.allKeys.forEach { key ->
      assertTrue(key is String, "$key is a ${key?.let { it::class.simpleName }}, not a string")
    }
  }

  @Test
  fun `a decoded picture carries no colour space`() {
    val pixels = ImageSource.ofBytes(IccPictures.DISPLAY_P3.pngOf(RASTER)).read().orFail()

    assertNull(pixels.codes.colorSpace, "the picture was decoded into a colour space, not as its codes")
  }

  @Test
  fun `a tagged picture enters an sdr frame at the shared srgb codes`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    ICC_CASES.forEach { (name, png, texel) ->
      val colour = assertNotNull(pictureColour(png), "core reads no description out of $name")
      val expected = pictureSrgbCodes(colour, intArrayOf(texel)).single()
      val stored = AuthoredColours.channelsOf(texel)
      assertTrue(
        stored.indices.any { abs(stored[it] - AuthoredColours.channelsOf(expected)[it]) > 1 },
        "$name draws ${texel.hex()} at ${expected.hex()}, too close to its stored codes to show the profile was read",
      )
      sdrMiss(name, png, expected, context)?.let(misses::add)
    }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `a tagged picture enters an hdr frame at the shared light`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    HdrTransfer.entries.forEach { transfer ->
      ICC_CASES.forEach { (name, png, texel) ->
        val light = pictureLight(pictureColour(png), intArrayOf(texel))
        hdrMiss("$transfer $name", png, transfer, light, context)?.let(misses::add)
      }
    }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Every PNG reads its description out of chunks other than an ICC profile, or an iCCP that wins over them.
  @Test
  fun `a png described by its chunks enters an sdr frame at the shared srgb codes`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    PNG_CASES.forEach { (name, png, texel) ->
      val expected = pictureSrgbCodes(pictureColour(png), intArrayOf(texel)).single()
      sdrMiss(name, png, expected, context)?.let(misses::add)
    }
    ColourPictures.HDR_PNGS.forEach { picture ->
      val png = picture.pngOf(RASTER)
      val expected = pictureSrgbCodes(pictureColour(png), picture.samples()).single()
      sdrMiss("$picture", png, expected, context)?.let(misses::add)
    }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `a png described by its chunks enters an hdr frame at the shared light`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    HdrTransfer.entries.forEach { transfer ->
      PNG_CASES.forEach { (name, png, texel) ->
        hdrMiss("$transfer $name", png, transfer, pictureLight(pictureColour(png), intArrayOf(texel)), context)
          ?.let(misses::add)
      }
      ColourPictures.HDR_PNGS.forEach { picture ->
        val png = picture.pngOf(RASTER)
        hdrMiss("$transfer $picture", png, transfer, pictureLight(pictureColour(png), picture.samples()), context)
          ?.let(misses::add)
      }
    }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Each description has to move the texel, or a backend ignoring it would pass the cases above.
  @Test
  fun `every png not described as srgb moves its texel`() {
    ColourPictures.PNGS.filter { it !in SRGB_BY_DESIGN }.forEach { picture ->
      val drawn =
        AuthoredColours.channelsOf(
          pictureSrgbCodes(pictureColour(picture.pngOf(RASTER)), intArrayOf(picture.texel)).single(),
        )
      val stored = AuthoredColours.channelsOf(picture.texel)

      assertTrue(stored.indices.any { abs(stored[it] - drawn[it]) > 1 }, "$picture draws $drawn from its $stored")
    }
    ColourPictures.HDR_PNGS.forEach { picture ->
      val light = pictureLight(pictureColour(picture.pngOf(RASTER)), picture.samples())
      val untagged = pictureLight(null, picture.samples())

      assertTrue((0 until ALPHA).any { abs(light[it] - untagged[it]) > 1f }, "$picture lands where untagged does")
    }
  }

  @Test
  fun `a picture whose samples cannot be read back draws its stored codes on either frame`() {
    val context = renderingContext(floatFormat = true)
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val decoded = ImageSource.ofBytes(png).read().orFail()
    val pixels = OverlayPixels(decoded.held, readColour = { pictureColour(png) }, reader = { null })
    val stored = AuthoredColours.channelsOf(IccPictures.DISPLAY_P3.texel)

    val sdr = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
    val hdr = pixels.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020).centreIn(context, ColorSpace.Bt2020)

    listOf("sdr" to sdr, "hdr" to hdr).forEach { (frame, read) ->
      val codes = List(ALPHA) { read[it] / read[ALPHA] * MAX_CODE }
      assertTrue(
        codes.indices.all { abs(codes[it] - stored[it]) <= SDR_TOLERANCE },
        "the $frame frame drew $codes, where the picture's stored codes are $stored",
      )
    }
  }

  @Test
  fun `a picture read from a file uri carries its description`() {
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val path = written(png, "uri")
    try {
      val uri = assertNotNull(NSURL.fileURLWithPath(path).absoluteString)
      val pixels = ImageSource.ofUri(uri).read().orFail("the file uri")

      assertNull(pixels.codes.colorSpace, "the file uri was decoded into a colour space, not as its codes")
      assertDescribedAs(pictureColour(png), pixels.colour, "the file uri")
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  // The file is gone before the picture is first drawn, so the description has to come from what decode read.
  @Test
  fun `a path keeps the description it was decoded with after the file is removed`() {
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val path = written(png, "path")
    val pixels = ImageSource.of(path).read().orFail("the path")
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)

    assertNull(pixels.codes.colorSpace, "the path was decoded into a colour space, not as its codes")
    assertDescribedAs(pictureColour(png), pixels.colour, "the removed path")
  }

  // Rewritten in place, the same file under the same path, so a decode that mapped it would read the new bytes or fault
  // on the truncate.
  @Test
  fun `a path keeps the picture it was decoded with after the file is rewritten in place`() {
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val path = written(png, "rewritten")
    try {
      val pixels = ImageSource.of(path).read().orFail("the path")
      NSFileHandle.fileHandleForWritingAtPath(path)?.run {
        truncateFileAtOffset(0u)
        closeFile()
      }
      val other = IccPictures.LINEAR_SRGB.pngOf(RASTER)
      other.usePinned { NSData.create(bytes = it.addressOf(0), length = other.size.toULong()) }.writeToFile(path, false)

      assertDescribedAs(pictureColour(png), pixels.colour, "the rewritten path")
      val context = renderingContext(floatFormat = true)
      val drawn = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
      val stored = assertNotNull(pixels.stored, "the rewritten path read no samples").copyOf(CHANNEL_COUNT)
      assertEquals(
        DISPLAY_P3_SAMPLES,
        stored.map { it.toInt() and SAMPLE_MASK },
        "the rewritten path read samples other than its own",
      )
      val expected = AuthoredColours.channelsOf(pictureSrgbCodes(pictureColour(png), stored).single())
      assertTrue(
        expected.indices.all { abs(drawn[it] / drawn[ALPHA] * MAX_CODE - expected[it]) <= SDR_TOLERANCE },
        "the rewritten path drew ${drawn.toList()}, where its own picture draws $expected",
      )
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun assertDescribedAs(
    expected: PictureColour?,
    actual: PictureColour?,
    name: String,
  ) {
    assertNotNull(actual, "$name read as untagged")
    assertContentEquals(pictureSrgbCodes(expected, PROBES), pictureSrgbCodes(actual, PROBES), "$name drew elsewhere")
  }

  @OptIn(BetaInteropApi::class)
  private fun written(
    png: ByteArray,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-overlay-profile-$name.png"
    png.usePinned { NSData.create(bytes = it.addressOf(0), length = png.size.toULong()) }.writeToFile(path, true)
    return path
  }

  private companion object {
    val RASTER = Size(4, 2)
    const val CHANNEL_COUNT = 4
    const val ALPHA = 3
    const val MAX_CODE = 255f
    val DISPLAY_P3_SAMPLES =
      AuthoredColours.channelsOf(IccPictures.DISPLAY_P3.texel).map { it * EIGHT_BIT_STEP } + SAMPLE_MASK
    const val HALF_ALPHA = 0x80000000.toInt()
    const val RGB_MASK = 0xFFFFFF

    // Colours a description other than the expected one draws somewhere else.
    val PROBES = intArrayOf(0xFFC08040.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), AuthoredColours.MID_GREY)

    // ICC tagged pictures, opaque and at half alpha, so the premultiply is read too.
    val ICC_CASES: List<Triple<String, ByteArray, Int>> =
      listOf(IccPictures.DISPLAY_P3, IccPictures.LINEAR_SRGB).flatMap { picture ->
        val half = (picture.texel and RGB_MASK) or HALF_ALPHA
        listOf(
          Triple("$picture", picture.pngOf(RASTER), picture.texel),
          Triple(
            "$picture at half alpha",
            dev.jordond.filmstrip.test.OverlayPixels
              .pngOf(RASTER, half, picture.profile),
            half,
          ),
        )
      }

    // Every eight-bit chunk described PNG opaque, and the cICP Display P3 one at half alpha too.
    val PNG_CASES: List<Triple<String, ByteArray, Int>> =
      ColourPictures.PNGS.map { Triple("$it", it.pngOf(RASTER), it.texel) } +
        ColourPictures.CICP_DISPLAY_P3.let { picture ->
          val texel = (picture.texel and RGB_MASK) or HALF_ALPHA
          Triple(
            "$picture at half alpha",
            dev.jordond.filmstrip.test.OverlayPixels
              .pngOf(RASTER, texel, picture.profile, picture.colour),
            texel,
          )
        }

    val SRGB_BY_DESIGN = setOf(ColourPictures.GAMMA_SRGB, ColourPictures.SRGB_CHUNK, ColourPictures.SRGB_OVER_GAMMA)
  }
}
