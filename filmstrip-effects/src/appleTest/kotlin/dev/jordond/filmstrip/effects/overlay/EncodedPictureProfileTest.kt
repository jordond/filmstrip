package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.decodeHex
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicIdentityPictures
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreImage.CIContext
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pictures in formats other than PNG, read for the colour description the Apple overlay raster draws them with.
 *
 * A JPEG XL holds its colour inside the codestream, so its description is the profile ImageIO decodes it with. JPEG
 * and HEIC written by ImageIO hold theirs in the container, and each is held to Core Graphics' Display P3, compared by
 * the codes it draws. Every [ColourPictures] AVIF and HEIC still, each HEIC alone and as a grid, decodes to within
 * rounding of a decode [AppleStillSamples] gives, and enters an SDR frame and both HDR frames at what core makes of the
 * samples the raster read of it, read through the description core reads out of its bytes. The JPEG XL is held the
 * same way. A HEIC with an `nclx` handed to ImageIO as written, without the raster's rewrite, decodes with its `nclx`
 * matrix in its codestream's range, to within rounding of a decode [AppleStillSamples.asWritten] gives. Decoded over
 * and over in both orders, each HEIC and AVIF alone and as a grid, and a HEICS and a JPEG ImageIO writes, reads the
 * same samples every time. A ten-bit HEIC reads back at its ten-bit codes. A still that is unreadable, refused or whose
 * decode is unmeasured on this platform is one more miss, so one run reports every still.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class EncodedPictureProfileTest {
  // A lossless four by two JPEG XL codestream painted 0xC08040, its colour encoding naming Display P3 with no ICC
  // profile of its own.
  private val jpegXl = ImageSource.ofBytes(JPEG_XL.decodeHex())

  @Test
  fun `a jpeg xl carries the profile its codestream names`() {
    assertNull(pictureColour(JPEG_XL.decodeHex()), "the JPEG XL carries a container description")
    val pixels = jpegXl.read().orFail("the JPEG XL")

    assertNull(pixels.codes.colorSpace, "the JPEG XL was decoded into a colour space, not as its codes")
    assertDrawsAsDisplayP3(pixels.colour, "the JPEG XL")
  }

  @Test
  fun `a jpeg xl decodes to its stored codes`() {
    if (!readerOrSkip("the JPEG XL decode case")) return
    val pixels = jpegXl.read().orFail("the JPEG XL")
    val samples = assertNotNull(pixels.centreSamples(), "the JPEG XL read no samples").map { it.toCode() }
    val stored = AuthoredColours.channelsOf(TEXEL)

    assertTrue(
      stored.indices.all { abs(samples[it] - stored[it]) <= TOLERANCE },
      "the JPEG XL decoded to $samples, where it stores $stored",
    )
  }

  // Read back in the frame's codes, the texel lands where the profile puts the samples the raster read, so Core Image
  // handed the codes over as they are.
  @Test
  fun `a jpeg xl enters every frame at the shared function of its samples`() {
    val context = contextOrSkip("the JPEG XL frame case") ?: return
    val pixels = jpegXl.read().orFail("the JPEG XL")
    val misses = drawMisses("the JPEG XL", pixels, AppleDisplayP3.colour(), context)

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `pictures imageio writes in display p3 carry their profile`() {
    WRITTEN.forEach { type ->
      val bytes = AppleDisplayP3.picture(type, SIZE, TEXEL)
      if (bytes == null) {
        println("skipping $type, ImageIO cannot write it here")
        return@forEach
      }
      val container = if (pictureColour(bytes) == null) "no container description" else "a container description"
      println("$type written by ImageIO carries $container")

      val pixels = ImageSource.ofBytes(bytes).read().orFail("the $type picture")
      assertDrawsAsDisplayP3(pixels.colour, type)
    }
  }

  @Test
  fun `a heic written in display p3 keeps its profile in the container`() {
    val bytes = AppleDisplayP3.picture(HEIC, SIZE, TEXEL)
    if (bytes == null) {
      println("skipping $HEIC, ImageIO cannot write it here")
      return
    }

    assertDrawsAsDisplayP3(pictureColour(bytes), "the $HEIC container")
  }

  // Colour managed on the way, a described still would land codes away from every decode Apple's decoder makes of it.
  @Test
  fun `an avif or heic still decodes to its samples with no colour management`() {
    if (!readerOrSkip("the still decode case")) return
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

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // One picture gives one answer whatever was decoded before it. A HEIC, a HEICS and an AVIF are read from ImageIO's
  // own bitmap, and a JPEG through Core Image's decode. Where a read lands is held by the model cases, so a miss here
  // is only ever a picture read more than one way.
  @Test
  fun `the raster reads each heif and jpeg the same way every time`() {
    if (!readerOrSkip("the determinism case")) return
    val reads =
      HEIC_STILLS.flatMap { heic ->
        listOf(
          OrderedRead("$heic") {
            ImageSource
              .ofBytes(heic.bytes())
              .read()
              .orFail("$heic")
              .centreSamples()
          },
          OrderedRead("$heic grid") {
            ImageSource
              .ofBytes(heic.gridBytes())
              .read()
              .orFail("$heic grid")
              .centreSamples()
          },
          OrderedRead("$heic as written") { writtenSamples(heic.bytes()) },
        )
      } +
        AVIF_STILLS.flatMap { avif ->
          listOf(
            OrderedRead("AVIF $avif") {
              ImageSource
                .ofBytes(avif.bytes())
                .read()
                .orFail("AVIF $avif")
                .centreSamples()
            },
            OrderedRead("AVIF $avif grid") {
              ImageSource
                .ofBytes(avif.gridBytes())
                .read()
                .orFail("AVIF $avif grid")
                .centreSamples()
            },
          )
        } +
        DETERMINISM_WRITTEN.mapNotNull { type ->
          val bytes =
            AppleDisplayP3.picture(type, SIZE, TEXEL) ?: return@mapNotNull null.also {
              println("skipping $type in the determinism case, ImageIO cannot write it here")
            }
          OrderedRead("$type written by ImageIO") {
            ImageSource
              .ofBytes(bytes)
              .read()
              .orFail("$type written by ImageIO")
              .centreSamples()
          }
        }
    val answers = reads.associateWith { mutableSetOf<List<Int>>() }
    val misses = mutableListOf<String>()
    repeat(ORDER_ROUNDS) { round ->
      (if (round % 2 == 0) reads else reads.asReversed()).forEach { next ->
        val read = next.samples()
        if (read ==
          null
        ) {
          misses += "$next read no samples"
        } else {
          answers.getValue(next) += read.map { it.toInt() and SAMPLE_MASK }
        }
      }
    }
    answers.filterValues { it.size > 1 }.forEach { (picture, seen) ->
      misses += "$picture read ${seen.size} ways, $seen"
    }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Handed over as written, Apple's HEIC decoder takes the matrix from nclx and the range from the codestream.
  @Test
  fun `apple's heic decoder reads a heic handed over as written with its nclx matrix and codestream range`() {
    if (!readerOrSkip("the as written decode case")) return
    val misses =
      HEIC_STILLS.filter { it.nclx != null }.mapNotNull { heic ->
        val picture = written(heic.bytes())
        val pixels = (picture as? PictureRead.Read)?.value ?: return@mapNotNull picture.unreadMiss("$heic as written")
        val read = pixels.centreSamples() ?: return@mapNotNull "$heic read no samples as written"
        AppleStillSamples.missOrUnmeasured {
          val decoded = read.map { it.toCode() }
          val models = AppleStillSamples.asWritten(heic, read).map { model -> model.map { it.toCode() } }
          val near = models.any { model -> decoded.indices.all { abs(decoded[it] - model[it]) <= TOLERANCE } }
          if (near) null else "$heic decoded to $decoded as written, where its matrix and codestream range give $models"
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Handed over as written, Apple's HEIC decoder reads the range its codestream signals, so these land away from what
  // their nclx names. A platform fix that reads nclx fails this, and the raster's rewrite is then no longer needed.
  @Test
  fun `apple's heic decoder reads the codestream range of a heic handed over as written`() {
    val pinned = HEIC_STILLS.filter { it.nclx != null && it.nclx?.fullRange != it.vui.fullRange }
    assertTrue(
      pinned.containsAll(
        listOf(
          ColourPictures.HEIC_NCLX_BT709_FULL,
          ColourPictures.HEIC_NCLX_BT601_FULL,
          ColourPictures.HEIC_NCLX_LIMITED_VUI_FULL,
        ),
      ),
      "the HEICs whose codestream range disagrees with nclx are $pinned",
    )
    if (!readerOrSkip("the codestream range case")) return
    val misses =
      pinned.mapNotNull { heic ->
        val bytes = heic.bytes()
        assertFalse(heifWithExplicitCodestreamColour(bytes).contentEquals(bytes), "$heic was not rewritten")
        val read = assertNotNull(writtenSamples(bytes), "$heic read no samples")
        AppleStillSamples.missOrUnmeasured {
          val decoded = read.map { it.toCode() }
          val nclx = AppleStillSamples.of(heic, read).map { model -> model.map { it.toCode() } }
          val away = nclx.all { model -> decoded.indices.any { abs(decoded[it] - model[it]) > 2 * TOLERANCE } }
          if (away) {
            null
          } else {
            "$heic decoded to $decoded as written, its nclx reading $nclx, so Apple now reads the range nclx names"
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Each still is drawn from the samples the raster read of it, so the decoder path it took does not move the answer.
  @Test
  fun `an avif or heic still enters every frame at the shared function of its samples`() {
    val context = contextOrSkip("the still frame case") ?: return
    val misses =
      STILLS.flatMap { still ->
        val picture = ImageSource.ofBytes(still.bytes).read()
        val pixels = (picture as? PictureRead.Read)?.value ?: return@flatMap listOf(picture.unreadMiss(still.name))
        drawMisses(still.name, pixels, pictureColour(still.bytes), context)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // ImageIO hands a ten-bit HEIC back at ten bits, so the raster keeps the codes an eight-bit read would round away.
  @Test
  fun `a ten bit heic reads back at its ten bit codes`() {
    if (!readerOrSkip("the ten-bit HEIC case")) return
    val heic = HeicIdentityPictures.tenBitBytes(CicpTag(1, 13, 0, true))
    val read =
      assertNotNull(
        ImageSource
          .ofBytes(heic)
          .read()
          .orFail("the ten-bit HEIC")
          .centreSamples(),
        "the ten-bit HEIC read no samples",
      )
    // Under the identity matrix at full range, red, green and blue are the red difference, luma and blue difference.
    val (y, cb, cr) = HeicIdentityPictures.TEN_BIT_YCBCR
    val expected = listOf(cr, y, cb).map { (it * MAX_SAMPLE / TEN_BIT_MAX).roundToInt() }

    assertContentEquals(expected, read.take(RGB).map { it.toInt() and SAMPLE_MASK }, "the ten-bit HEIC lost depth")
  }

  // A still whose description core reads has to move its colour, or a backend ignoring it would pass the cases above.
  @Test
  fun `a described still moves its decoded colour`() {
    val misses =
      STILLS.filter { pictureColour(it.bytes) != null }.mapNotNull { still ->
        AppleStillSamples.missOrUnmeasured {
          val samples = still.decodes(null).first()
          val drawn = AuthoredColours.channelsOf(pictureSrgbCodes(pictureColour(still.bytes), samples).single())
          val untagged = AuthoredColours.channelsOf(pictureSrgbCodes(null, samples).single())
          val moved = untagged.indices.any { abs(untagged[it] - drawn[it]) > 2 }
          if (moved) null else "${still.name} draws $drawn from its decoded $untagged"
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private fun assertDrawsAsDisplayP3(
    colour: PictureColour?,
    name: String,
  ) {
    assertNotNull(colour, "$name read as untagged")
    assertContentEquals(
      pictureSrgbCodes(AppleDisplayP3.colour(), PROBES),
      pictureSrgbCodes(colour, PROBES),
      "$name does not draw as Display P3",
    )
  }

  /**
   * Why [pixels] misses the codes the shared functions give the samples it read at its centre through [colour], on an
   * SDR frame and on both HDR frames, empty when it lands on all three.
   */
  private fun drawMisses(
    name: String,
    pixels: OverlayPixels,
    colour: PictureColour?,
    context: CIContext,
  ): List<String> {
    val samples = pixels.centreSamples() ?: return listOf("$name read no samples")
    val misses = mutableListOf<String>()

    val sdr = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).centreIn(context, ColorSpace.Bt709)
    val drawn = List(RGB) { sdr[it] / sdr[RGB] * MAX_CODE }
    val expected =
      if (colour == null) {
        List(RGB) { samples[it].toCode() }
      } else {
        AuthoredColours.channelsOf(pictureSrgbCodes(colour, samples).single()).map { it.toFloat() }
      }
    if (drawn.indices.any { abs(drawn[it] - expected[it]) > SDR_TOLERANCE }) {
      misses += "$name drew $drawn on sdr, where its samples give $expected"
    }

    HdrTransfer.entries.forEach { transfer ->
      val hdr = pixels.enteringFrame(transfer, ColorSpace.Bt2020).centreIn(context, transfer)
      val carried = tenBitCodesFromSignal(FloatArray(RGB) { hdr[it] / hdr[RGB] }).toList()
      val light = tenBitCodesFromSignal(transfer.signalFromNits(pictureLight(colour, samples).copyOf(RGB))).toList()
      if (carried.indices.any { abs(carried[it] - light[it]) > HDR_TOLERANCE }) {
        misses += "$name carried $carried on $transfer, where its samples give $light"
      }
    }
    return misses
  }

  /**
   * The straight sixteen-bit red, green, blue and alpha this picture read back at its centre, the samples every frame
   * draws a described picture from, or null when none were read.
   */
  private fun OverlayPixels.centreSamples(): ShortArray? {
    val samples = stored ?: return null
    val centre = (size.height / 2 * size.width + size.width / 2) * CHANNELS
    return samples.copyOfRange(centre, centre + CHANNELS)
  }

  /**
   * The pixels [bytes] decode to handed over as written rather than through the raster's rewrite, decoded the way the
   * raster decodes them, or why they could not be read.
   */
  @OptIn(BetaInteropApi::class)
  private fun written(bytes: ByteArray): PictureRead<OverlayPixels> {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    return data.overlayCodes().map { OverlayPixels(it) }
  }

  /**
   * The straight sixteen-bit samples [written] read back at the centre of [bytes] through the raster's own code
   * reader, or null when they could not be read.
   */
  private fun writtenSamples(bytes: ByteArray): ShortArray? =
    (written(bytes) as? PictureRead.Read)?.value?.centreSamples()

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

  /**
   * Whether the overlay raster reads samples back in this process, after printing that [case] is skipped when it
   * cannot. The raster reads through its own code reader, so this needs no context of the test's own.
   */
  private fun readerOrSkip(case: String): Boolean {
    val probe =
      ImageSource.ofBytes(
        dev.jordond.filmstrip.test.OverlayPixels
          .pngOf(SIZE, TEXEL, null),
      )
    val readable = probe.read().orFail("the probe PNG").stored != null
    if (!readable) println("SKIPPED $case: the overlay raster reads no samples back here, nothing asserted")
    return readable
  }

  /**
   * One AVIF or HEIC still, its bytes and every texel Apple's decoder may hand back for it, given the texel this
   * process's decoder read.
   */
  private class Still(
    val name: String,
    val bytes: ByteArray,
    val decodes: (ShortArray?) -> List<ShortArray>,
  )

  /**
   * One read in the determinism case, named by [name], that hands back the samples the raster read at the centre or
   * null when it read none.
   */
  private class OrderedRead(
    val name: String,
    val samples: () -> ShortArray?,
  ) {
    override fun toString(): String = name
  }

  private companion object {
    const val JPEG_XL = "ff0a08701090c98d0800010068004b188b15c209635cf03e8408185734f306700e0800d83f4e2500"
    const val TEXEL = 0xFFC08040.toInt()
    const val HEIC = "public.heic"
    val SIZE = Size(16, 8)
    val WRITTEN = listOf(HEIC, "public.jpeg", "public.jpeg-xl")

    // Colours a profile other than Display P3 draws somewhere else.
    val PROBES = intArrayOf(TEXEL, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), AuthoredColours.MID_GREY)

    val HEIC_STILLS =
      ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
        ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

    val AVIF_STILLS = ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA

    // A HEVC image sequence and a JPEG ImageIO writes, the determinism case's picture of each.
    val DETERMINISM_WRITTEN = listOf("public.heics", "public.jpeg")

    // Each HEIC alone and as a two by two grid of its codestream. A grid leaves out the profile, which plays no part in
    // the decode.
    val STILLS =
      AVIF_STILLS.map { avif ->
        Still("AVIF $avif", avif.bytes()) { AppleStillSamples.of(avif, it) }
      } +
        HEIC_STILLS.map { heic -> Still("$heic", heic.bytes()) { AppleStillSamples.of(heic, it) } } +
        HEIC_STILLS.map { heic -> Still("$heic grid", heic.gridBytes()) { AppleStillSamples.of(heic, it) } }

    // A decode lands off its model, or a JPEG XL off its stored codes, by rounding alone.
    const val TOLERANCE = AppleStillSamples.TOLERANCE
    const val ORDER_ROUNDS = 12
    const val CHANNELS = 4
    const val RGB = 3
    const val MAX_CODE = 255f
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257f
    const val MAX_SAMPLE = 65535.0
    const val TEN_BIT_MAX = 1023.0

    fun Short.toCode(): Float = (toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP
  }
}
