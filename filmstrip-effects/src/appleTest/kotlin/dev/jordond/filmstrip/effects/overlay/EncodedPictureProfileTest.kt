package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.STILLS
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TOLERANCE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.WRITTEN_HEICS
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.toCode
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.decodeHex
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicIdentityPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Pictures in formats other than PNG, read for the color description the Apple overlay raster draws them with.
 *
 * A JPEG XL holds its color inside the codestream, so its description is the profile ImageIO decodes it with. JPEG and
 * HEIC written by ImageIO hold theirs in the container, and each is held to Core Graphics' Display P3, compared by the
 * codes it draws. Every still in [ColorPictures.RASTER_REPRESENTATIVES], and [ColorPictures.HEIC_NCLX_BT709_FULL] as
 * a grid, decodes to within rounding of a decode [AppleStillSamples] gives, and enters an SDR frame and both HDR frames
 * at what core makes of the samples the raster read of it, read through the description core reads out of its bytes.
 * The JPEG XL is held the same way. Each representative HEIC with an `nclx`, handed to ImageIO as written without the
 * raster's rewrite, decodes with its `nclx` matrix in its codestream's range, to within rounding of a decode
 * [AppleStillSamples.asWritten] gives. Decoded over and over in both orders, each of those stills, each representative
 * HEIC handed over as written, and a JPEG ImageIO writes, reads the same samples every time. A ten-bit HEIC
 * reads back at its ten-bit codes. A still that is unreadable, refused or whose decode is unmeasured on this platform
 * is one more miss, so one run reports every still.
 */
@OptIn(ExperimentalForeignApi::class)
class EncodedPictureProfileTest {
  // A lossless four by two JPEG XL codestream painted 0xC08040, its color encoding naming Display P3 with no ICC
  // profile of its own.
  private val jpegXl = ImageSource.ofBytes(JPEG_XL.decodeHex())

  @Test
  fun `a jpeg xl carries the profile its codestream names`() {
    withClue("the JPEG XL carries a container description") { pictureColor(JPEG_XL.decodeHex()).shouldBeNull() }
    val pixels = jpegXl.read().orFail("the JPEG XL")

    withClue("the JPEG XL was decoded into a color space, not as its codes") { pixels.codes.colorSpace.shouldBeNull() }
    assertDrawsAsDisplayP3(pixels.color, "the JPEG XL")
  }

  @Test
  fun `a jpeg xl decodes to its stored codes`() {
    assertRasterReads()
    val pixels = jpegXl.read().orFail("the JPEG XL")
    val samples =
      withClue("the JPEG XL read no samples") {
        pixels.centreSamples().shouldNotBeNull()
      }.map { it.toCode() }
    val stored = AuthoredColors.channelsOf(TEXEL)

    withClue("the JPEG XL decoded to $samples, where it stores $stored") {
      stored.indices.all { abs(samples[it] - stored[it]) <= TOLERANCE } shouldBe true
    }
  }

  // Read back in the frame's codes, the texel lands where the profile puts the samples the raster read, so Core Image
  // handed the codes over as they are.
  @Test
  fun `a jpeg xl enters every frame at the shared function of its samples`() {
    val context = renderingContext(floatFormat = true)
    val pixels = jpegXl.read().orFail("the JPEG XL")
    val misses = drawMisses("the JPEG XL", pixels, AppleDisplayP3.color(), context)

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `pictures imageio writes in display p3 carry their profile`() {
    WRITTEN.forEach { type ->
      val bytes = withClue("ImageIO wrote no $type") { AppleDisplayP3.picture(type, SIZE, TEXEL).shouldNotBeNull() }
      val container = if (pictureColor(bytes) == null) "no container description" else "a container description"
      println("$type written by ImageIO carries $container")

      val pixels = ImageSource.ofBytes(bytes).read().orFail("the $type picture")
      assertDrawsAsDisplayP3(pixels.color, type)
    }
  }

  @Test
  fun `a heic written in display p3 keeps its profile in the container`() {
    val bytes = withClue("ImageIO wrote no $HEIC") { AppleDisplayP3.picture(HEIC, SIZE, TEXEL).shouldNotBeNull() }

    assertDrawsAsDisplayP3(pictureColor(bytes), "the $HEIC container")
  }

  // Color managed on the way, a described still would land codes away from every decode Apple's decoder makes of it.
  @Test
  fun `an avif or heic still decodes to its samples with no color management`() {
    assertRasterReads()
    val misses =
      STILLS.mapNotNull { still ->
        val picture = ImageSource.ofBytes(still.bytes).read()
        val pixels = (picture as? PictureRead.Read)?.value ?: return@mapNotNull picture.unreadMiss(still.name)
        val read = pixels.centreSamples() ?: return@mapNotNull "${still.name} read no samples"
        AppleStillSamples.missOrUnmeasured {
          val decoded = read.map { it.toCode() }
          val models = still.decodes(read).map { model -> model.map { it.toCode() } }
          val near = models.any { model -> decoded.indices.all { abs(decoded[it] - model[it]) <= TOLERANCE } }
          if (near) null else "${still.name} decoded to $decoded, where Apple's decodes of it are $models"
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // One picture gives one answer whatever was decoded before it. A HEIC and an AVIF are read from ImageIO's own
  // bitmap, and a JPEG through Core Image's decode. Where a read lands is held by the model cases, so a miss here is
  // only ever a picture read more than one way. ImageSequenceReadTest takes the same reads with a HEICS among them.
  @Test
  fun `the raster reads each heif and jpeg the same way every time`() {
    assertRasterReads()
    val misses = orderMisses(orderedReads(listOf(JPEG)))

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // Handed over as written, Apple's HEIC decoder takes the matrix from nclx and the range from the codestream.
  @Test
  fun `apple's heic decoder reads a heic handed over as written with its nclx matrix and codestream range`() {
    assertRasterReads()
    val misses =
      WRITTEN_HEICS.filter { it.nclx != null }.mapNotNull { heic ->
        val picture = writtenPixels(heic.bytes())
        val pixels = (picture as? PictureRead.Read)?.value ?: return@mapNotNull picture.unreadMiss("$heic as written")
        val read = pixels.centreSamples() ?: return@mapNotNull "$heic read no samples as written"
        AppleStillSamples.missOrUnmeasured {
          val decoded = read.map { it.toCode() }
          val models = AppleStillSamples.asWritten(heic, read).map { model -> model.map { it.toCode() } }
          val near = models.any { model -> decoded.indices.all { abs(decoded[it] - model[it]) <= TOLERANCE } }
          if (near) null else "$heic decoded to $decoded as written, where its matrix and codestream range give $models"
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // Each still is drawn from the samples the raster read of it, so the decoder path it took does not move the answer.
  @Test
  fun `an avif or heic still enters every frame at the shared function of its samples`() {
    val context = renderingContext(floatFormat = true)
    val misses =
      STILLS.flatMap { still ->
        val picture = ImageSource.ofBytes(still.bytes).read()
        val pixels = (picture as? PictureRead.Read)?.value ?: return@flatMap listOf(picture.unreadMiss(still.name))
        drawMisses(still.name, pixels, pictureColor(still.bytes), context)
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // ImageIO hands a ten-bit HEIC back at ten bits, so the raster keeps the codes an eight-bit read would round away.
  @Test
  fun `a ten bit heic reads back at its ten bit codes`() {
    assertRasterReads()
    val heic = HeicIdentityPictures.tenBitBytes(CicpTag(1, 13, 0, true))
    val read =
      withClue("the ten-bit HEIC read no samples") {
        (
          ImageSource
            .ofBytes(heic)
            .read()
            .orFail("the ten-bit HEIC")
            .centreSamples()
        ).shouldNotBeNull()
      }
    // Under the identity matrix at full range, red, green and blue are the red difference, luma and blue difference.
    val (y, cb, cr) = HeicIdentityPictures.TEN_BIT_YCBCR
    val expected = listOf(cr, y, cb).map { (it * MAX_SAMPLE / TEN_BIT_MAX).roundToInt() }

    withClue("the ten-bit HEIC lost depth") { read.take(RGB).map { it.toInt() and SAMPLE_MASK } shouldBe expected }
  }

  // A still whose description core reads has to move its color, or a backend ignoring it would pass the cases above.
  // Each decode Apple's decoder may hand back for it has to move.
  @Test
  fun `a described still moves its decoded color`() {
    val misses =
      STILLS.filter { pictureColor(it.bytes) != null }.mapNotNull { still ->
        val color = pictureColor(still.bytes)
        AppleStillSamples.missOrUnmeasured {
          still.decodes(null).firstNotNullOfOrNull { samples ->
            val drawn = AuthoredColors.channelsOf(pictureSrgbCodes(color, samples).single())
            val untagged = AuthoredColors.channelsOf(pictureSrgbCodes(null, samples).single())
            val moved = untagged.indices.any { abs(untagged[it] - drawn[it]) > 2 }
            if (moved) null else "${still.name} draws $drawn from its decoded $untagged"
          }
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  private fun assertDrawsAsDisplayP3(
    color: PictureColor?,
    name: String,
  ) {
    withClue("$name read as untagged") { color.shouldNotBeNull() }
    withClue("$name does not draw as Display P3") {
      pictureSrgbCodes(color, PROBES) shouldBe pictureSrgbCodes(AppleDisplayP3.color(), PROBES)
    }
  }

  /**
   * Why [pixels] misses the codes the shared functions give the samples it read at its centre through [color], on an
   * SDR frame and on both HDR frames, empty when it lands on all three.
   */
  private fun drawMisses(
    name: String,
    pixels: OverlayPixels,
    color: PictureColor?,
    context: CIContext,
  ): List<String> {
    val samples = pixels.centreSamples() ?: return listOf("$name read no samples")
    val misses = mutableListOf<String>()

    val sdr = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
    val drawn = List(RGB) { sdr[it] / sdr[RGB] * MAX_CODE }
    val expected =
      if (color == null) {
        List(RGB) { samples[it].toCode() }
      } else {
        AuthoredColors.channelsOf(pictureSrgbCodes(color, samples).single()).map { it.toFloat() }
      }
    if (drawn.indices.any { abs(drawn[it] - expected[it]) > SDR_TOLERANCE }) {
      misses += "$name drew $drawn on sdr, where its samples give $expected"
    }

    HdrTransfer.entries.forEach { transfer ->
      val hdr = pixels.enteringFrame(transfer, ColorSpace.Bt2020).centreIn(context, transfer)
      val carried = tenBitCodesFromSignal(FloatArray(RGB) { hdr[it] / hdr[RGB] }).toList()
      val light = tenBitCodesFromSignal(transfer.signalFromNits(pictureLight(color, samples).copyOf(RGB))).toList()
      if (carried.indices.any { abs(carried[it] - light[it]) > HDR_TOLERANCE }) {
        misses += "$name carried $carried on $transfer, where its samples give $light"
      }
    }
    return misses
  }

  /**
   * Why this read holds no picture, naming the picture [name].
   */
  private fun PictureRead<*>.unreadMiss(name: String): String =
    when (this) {
      is PictureRead.Read -> "$name was read"
      is PictureRead.Refused -> "$name was refused: $reason"
      is PictureRead.Unsupported -> "$name is unsupported: $message"
      PictureRead.Unreadable -> "$name did not decode"
    }

  private companion object {
    const val JPEG_XL = "ff0a08701090c98d0800010068004b188b15c209635cf03e8408185734f306700e0800d83f4e2500"
    const val HEIC = "public.heic"
    const val JPEG = "public.jpeg"
    val WRITTEN = listOf(HEIC, JPEG)

    // Colors a profile other than Display P3 draws somewhere else.
    val PROBES = intArrayOf(TEXEL, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), AuthoredColors.MID_GREY)

    const val RGB = 3
    const val MAX_CODE = 255f
    const val MAX_SAMPLE = 65535.0
    const val TEN_BIT_MAX = 1023.0
  }
}
