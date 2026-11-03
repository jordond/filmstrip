package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.IccPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/**
 * Color described pictures decoded by the Apple overlay raster and read back as the image each frame kind draws.
 *
 * ICC tagged pictures, opaque and at half alpha, and every [ColorPictures] PNG enter an SDR frame at the codes
 * [pictureSrgbCodes] gives their texel and an HDR frame at the signal [pictureLight] puts it at, each read through the
 * description core reads out of the same bytes. Nothing here needs an encoder, so the HDR answer is asserted on every
 * Apple target.
 */
@OptIn(ExperimentalForeignApi::class)
class OverlayPictureProfileTest {
  @Test
  fun `the unmanaged options are a Foundation dictionary keyed by strings`() {
    val options = unmanagedOptions().shouldBeObjC<NSDictionary>()

    options.count shouldBe 1uL
    options.objectForKey(checkNotNull(kCIImageColorSpace)).shouldBeObjC<NSNull>()
    options.allKeys.forEach { key ->
      withClue("$key is a ${key?.let { it::class.simpleName }}, not a string") { (key is String) shouldBe true }
    }
  }

  @Test
  fun `a decoded picture carries no color space`() {
    val pixels = ImageSource.ofBytes(IccPictures.DISPLAY_P3.pngOf(RASTER)).read().orFail()

    withClue("the picture was decoded into a color space, not as its codes") { pixels.codes.colorSpace.shouldBeNull() }
  }

  @Test
  fun `a tagged picture enters an sdr frame at the shared srgb codes`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    ICC_CASES.forEach { (name, png, texel) ->
      val color = withClue("core reads no description out of $name") { pictureColor(png).shouldNotBeNull() }
      val expected = pictureSrgbCodes(color, intArrayOf(texel)).single()
      val stored = AuthoredColors.channelsOf(texel)
      withClue(
        "$name draws ${texel.hex()} at ${expected.hex()}, too close to its stored codes to show the profile was read",
      ) {
        stored.indices.any { abs(stored[it] - AuthoredColors.channelsOf(expected)[it]) > 1 } shouldBe true
      }
      sdrMiss(name, png, expected, context)?.let(misses::add)
    }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `a tagged picture enters an hdr frame at the shared light`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    HdrTransfer.entries.forEach { transfer ->
      ICC_CASES.forEach { (name, png, texel) ->
        val light = pictureLight(pictureColor(png), intArrayOf(texel))
        hdrMiss("$transfer $name", png, transfer, light, context)?.let(misses::add)
      }
    }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // Every PNG reads its description out of chunks other than an ICC profile, or an iCCP that wins over them.
  @Test
  fun `a png described by its chunks enters an sdr frame at the shared srgb codes`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    PNG_CASES.forEach { (name, png, texel) ->
      val expected = pictureSrgbCodes(pictureColor(png), intArrayOf(texel)).single()
      sdrMiss(name, png, expected, context)?.let(misses::add)
    }
    ColorPictures.HDR_PNGS.forEach { picture ->
      val png = picture.pngOf(RASTER)
      val expected = pictureSrgbCodes(pictureColor(png), picture.samples()).single()
      sdrMiss("$picture", png, expected, context)?.let(misses::add)
    }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `a png described by its chunks enters an hdr frame at the shared light`() {
    val context = renderingContext(floatFormat = true)
    val misses = mutableListOf<String>()

    HdrTransfer.entries.forEach { transfer ->
      PNG_CASES.forEach { (name, png, texel) ->
        hdrMiss("$transfer $name", png, transfer, pictureLight(pictureColor(png), intArrayOf(texel)), context)
          ?.let(misses::add)
      }
      ColorPictures.HDR_PNGS.forEach { picture ->
        val png = picture.pngOf(RASTER)
        hdrMiss("$transfer $picture", png, transfer, pictureLight(pictureColor(png), picture.samples()), context)
          ?.let(misses::add)
      }
    }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // Each description has to move the texel, or a backend ignoring it would pass the cases above.
  @Test
  fun `every png not described as srgb moves its texel`() {
    ColorPictures.PNGS.filter { it !in SRGB_BY_DESIGN }.forEach { picture ->
      val drawn =
        AuthoredColors.channelsOf(
          pictureSrgbCodes(pictureColor(picture.pngOf(RASTER)), intArrayOf(picture.texel)).single(),
        )
      val stored = AuthoredColors.channelsOf(picture.texel)

      withClue("$picture draws $drawn from its $stored") {
        stored.indices.any { abs(stored[it] - drawn[it]) > 1 } shouldBe true
      }
    }
    ColorPictures.HDR_PNGS.forEach { picture ->
      val light = pictureLight(pictureColor(picture.pngOf(RASTER)), picture.samples())
      val untagged = pictureLight(null, picture.samples())

      withClue("$picture lands where untagged does") {
        (0 until ALPHA).any { abs(light[it] - untagged[it]) > 1f } shouldBe true
      }
    }
  }

  @Test
  fun `a picture whose samples cannot be read back draws its stored codes on either frame`() {
    val context = renderingContext(floatFormat = true)
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val decoded = ImageSource.ofBytes(png).read().orFail()
    val pixels = OverlayPixels(decoded.held, readColor = { pictureColor(png) }, reader = { null })
    val stored = AuthoredColors.channelsOf(IccPictures.DISPLAY_P3.texel)

    val sdr = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
    val hdr = pixels.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020).centreIn(context, ColorSpace.Bt2020)

    listOf("sdr" to sdr, "hdr" to hdr).forEach { (frame, read) ->
      val codes = List(ALPHA) { read[it] / read[ALPHA] * MAX_CODE }
      withClue("the $frame frame drew $codes, where the picture's stored codes are $stored") {
        codes.indices.all { abs(codes[it] - stored[it]) <= SDR_TOLERANCE } shouldBe true
      }
    }
  }

  @Test
  fun `a picture read from a file uri carries its description`() {
    val png = IccPictures.DISPLAY_P3.pngOf(RASTER)
    val path = written(png, "uri")
    try {
      val uri = NSURL.fileURLWithPath(path).absoluteString.shouldNotBeNull()
      val pixels = ImageSource.ofUri(uri).read().orFail("the file uri")

      withClue("the file uri was decoded into a color space, not as its codes") {
        pixels.codes.colorSpace.shouldBeNull()
      }
      assertDescribedAs(pictureColor(png), pixels.color, "the file uri")
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

    withClue("the path was decoded into a color space, not as its codes") { pixels.codes.colorSpace.shouldBeNull() }
    assertDescribedAs(pictureColor(png), pixels.color, "the removed path")
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

      assertDescribedAs(pictureColor(png), pixels.color, "the rewritten path")
      val context = renderingContext(floatFormat = true)
      val drawn = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
      val stored =
        withClue("the rewritten path read no samples") {
          pixels.stored.shouldNotBeNull()
        }.copyOf(CHANNEL_COUNT)
      withClue("the rewritten path read samples other than its own") {
        stored.map { it.toInt() and SAMPLE_MASK } shouldBe DISPLAY_P3_SAMPLES
      }
      val expected = AuthoredColors.channelsOf(pictureSrgbCodes(pictureColor(png), stored).single())
      withClue("the rewritten path drew ${drawn.toList()}, where its own picture draws $expected") {
        expected.indices.all { abs(drawn[it] / drawn[ALPHA] * MAX_CODE - expected[it]) <= SDR_TOLERANCE } shouldBe true
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun assertDescribedAs(
    expected: PictureColor?,
    actual: PictureColor?,
    name: String,
  ) {
    withClue("$name read as untagged") { actual.shouldNotBeNull() }
    withClue("$name drew elsewhere") { pictureSrgbCodes(actual, PROBES) shouldBe pictureSrgbCodes(expected, PROBES) }
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
      AuthoredColors.channelsOf(IccPictures.DISPLAY_P3.texel).map { it * EIGHT_BIT_STEP } + SAMPLE_MASK
    const val HALF_ALPHA = 0x80000000.toInt()
    const val RGB_MASK = 0xFFFFFF

    // Colors a description other than the expected one draws somewhere else.
    val PROBES = intArrayOf(0xFFC08040.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), AuthoredColors.MID_GREY)

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
      ColorPictures.PNGS.map { Triple("$it", it.pngOf(RASTER), it.texel) } +
        ColorPictures.CICP_DISPLAY_P3.let { picture ->
          val texel = (picture.texel and RGB_MASK) or HALF_ALPHA
          Triple(
            "$picture at half alpha",
            dev.jordond.filmstrip.test.OverlayPixels
              .pngOf(RASTER, texel, picture.profile, picture.color),
            texel,
          )
        }

    val SRGB_BY_DESIGN = setOf(ColorPictures.GAMMA_SRGB, ColorPictures.SRGB_CHUNK, ColorPictures.SRGB_OVER_GAMMA)
  }
}
