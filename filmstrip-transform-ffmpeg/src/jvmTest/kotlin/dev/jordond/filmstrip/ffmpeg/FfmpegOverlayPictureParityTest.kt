package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import java.awt.Transparency
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.plugins.tiff.BaselineTIFFTagSet
import javax.imageio.plugins.tiff.TIFFDirectory
import javax.imageio.plugins.tiff.TIFFField
import javax.imageio.plugins.tiff.TIFFTag
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.awt.color.ColorSpace as AwtColorSpace

/**
 * Pictures the JDK reads, carried onto an HDR grade where their colour lands.
 *
 * The JDK decodes a picture before ffmpeg is asked, so a PNG the two read differently would land on a grade somewhere
 * the SDR export never draws it. Each PNG here is built on the JVM, run through the grade, and held against the texels
 * this ffmpeg decodes out of the same bytes, carried onto the grade by the same mapping. A picture whose colour profile
 * the JDK honours is held against its colour managed sRGB value instead. A texel may sit one code either side of the
 * expected one, which is the rounding a sixteen-bit sample or a profile conversion takes on its way to eight bits.
 */
class FfmpegOverlayPictureParityTest {
  private val fixture = FfmpegExportFixture()

  // The JDK's PNG reader does not read an iCCP chunk, so a tagged PNG reaches the grade as its stored samples, the ones
  // ffmpeg draws on an SDR grade.
  @Test
  fun `a PNG tagged with a linear ICC profile carries the samples ffmpeg draws`() =
    assertParity(taggedLinear(eightBitPng()), "linear ICC")

  /**
   * A TIFF tagged with the JDK's linear RGB profile lands where its colour managed sRGB colour does.
   *
   * The JDK's TIFF reader honours the profile, and every other backend draws a tagged picture colour managed, so the
   * expected colours come from the profile's own conversion of the stored samples. The ffmpeg decode route hands back
   * PAM samples with no colour management, so a tagged WebP or AVIF, which only ffmpeg reads, lands on its stored
   * samples instead. That is a named limit of that route.
   */
  @Test
  fun `a TIFF tagged with a linear ICC profile lands on its colour managed colour`() {
    val linear = AwtColorSpace.getInstance(AwtColorSpace.CS_LINEAR_RGB)
    val managed =
      IntArray(TEXELS.size) { x ->
        val texel = TEXELS[x]
        val stored = floatArrayOf(channel(texel, RED_SHIFT), channel(texel, GREEN_SHIFT), channel(texel, 0))
        linear.toRGB(stored).fold(texel ushr ALPHA_SHIFT) { argb, value ->
          (argb shl 8) or (value * BYTE_MAX).roundToInt()
        }
      }
    val tiff = taggedLinearTiff()

    assertLandsOn(tiff, managed, "linear ICC TIFF")
  }

  @Test
  fun `a sixteen bit PNG carries the samples ffmpeg draws`() = assertParity(sixteenBitPng(), "sixteen bit")

  @Test
  fun `a grey PNG carries the samples ffmpeg draws`() = assertParity(greyPng(), "grey")

  private fun assertParity(
    png: ByteArray,
    what: String,
  ) {
    if (!fixture.available()) return
    val read = decoder.decode(ImageSource.ofBytes(png))
    val drawn =
      assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing from the $what PNG")
    assertLandsOn(png, drawn.getRGB(0, 0, drawn.width, 1, null, 0, drawn.width), what)
  }

  // Each texel of [picture]'s first row, carried onto every grade, lands where one of the colours within a code of the
  // matching [texels] entry does, with its alpha.
  private fun assertLandsOn(
    picture: ByteArray,
    texels: IntArray,
    what: String,
  ) {
    val width = texels.size
    for (transfer in HdrTransfer.entries) {
      val graded = mappedRaster(picture, transfer)
      val landings = mappedRaster(neighboursPng(texels), transfer)
      assertEquals(width, graded.width, "$what is ${graded.width} wide on the grade and $width expected")
      texels.indices.forEach { x ->
        val read = graded.getPixel(x, 0, IntArray(CHANNELS))
        val near = (0 until NEIGHBOURS).map { landings.getPixel(x, it, IntArray(CHANNELS)).copyOf(3).toList() }
        val alpha = (texels[x] ushr ALPHA_SHIFT) * WIDE_MAX / BYTE_MAX
        assertTrue(
          read.copyOf(3).toList() in near && abs(read[3] - alpha) <= WIDE_MAX / BYTE_MAX,
          "$transfer $what texel $x read ${read.toList()}, which no colour within a code of " +
            "${texels[x].toUInt().toString(16)} lands at",
        )
      }
    }
  }

  // Every colour within one code of each texel, a column per texel and a row per step, opaque, since the
  // mapping reads the colour apart from the alpha. An HLG signal hangs on the colour's luminance, so a step in one
  // channel moves the others and only the landings themselves say where a neighbour goes.
  private fun neighboursPng(texels: IntArray): ByteArray {
    val image = BufferedImage(texels.size, NEIGHBOURS, BufferedImage.TYPE_INT_ARGB)
    texels.forEachIndexed { x, texel ->
      (0 until NEIGHBOURS).forEach { row ->
        val steps = intArrayOf(row / 9 - 1, row / 3 % 3 - 1, row % 3 - 1)
        image.setRGB(x, row, stepped(texel, steps))
      }
    }
    return pngOf(image)
  }

  private fun stepped(
    texel: Int,
    steps: IntArray,
  ): Int =
    listOf(RED_SHIFT, GREEN_SHIFT, 0).foldIndexed(OPAQUE) { channel, argb, shift ->
      argb or (((texel shr shift and BYTE_MAX) + steps[channel]).coerceIn(0, BYTE_MAX) shl shift)
    }

  private fun mappedRaster(
    png: ByteArray,
    transfer: HdrTransfer,
  ): Raster {
    val spec = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)
    val resolved = BuiltInEffectResolver(decoder).resolve(spec, capabilities, attributes(transfer))
    val mapped =
      (
        (resolved as EffectResolution.Resolved)
          .effect.fragment.auxInputs
          .first()
          .image as ImageSource.Bytes
      ).bytes
    return ImageIO.read(ByteArrayInputStream(mapped)).raster
  }

  private fun channel(
    texel: Int,
    shift: Int,
  ): Float = (texel shr shift and BYTE_MAX) / BYTE_MAX.toFloat()

  private fun attributes(transfer: HdrTransfer): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 2.seconds),
    )

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg", "ffprobe")

    val capabilities =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt2020),
        maxTextureSize = 16_384,
        features = emptySet(),
      )

    val FRAME = Size(1280, 720)
    const val RATE = 30f
    const val NEIGHBOURS = 27
    const val CHANNELS = 4
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BYTE_MAX = 0xFF
    const val WIDE_MAX = 0xFFFF
    const val OPAQUE = 0xFF shl 24
  }
}

// Every texel keeps some alpha, since a clear one leaves its colour free for either decoder to rewrite.
private val TEXELS =
  intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF40A040.toInt(), 0xFF1E90FF.toInt(), 0x80FF8020.toInt())

private fun eightBitPng(): ByteArray {
  val image = BufferedImage(TEXELS.size, 1, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, TEXELS.size, 1, TEXELS, 0, TEXELS.size)
  return pngOf(image)
}

// Samples that land between two eight-bit codes, so each decoder's rounding is what is read.
private fun sixteenBitPng(): ByteArray {
  val samples =
    intArrayOf(
      65_535,
      65_535,
      65_535,
      65_535,
      32_896,
      32_896,
      32_896,
      65_535,
      12_345,
      40_000,
      23_456,
      65_535,
      7_777,
      37_000,
      65_535,
      65_535,
      65_535,
      33_000,
      8_000,
      32_896,
    )
  val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, samples.size / 4, 1, 4, null)
  raster.setPixels(0, 0, samples.size / 4, 1, samples)
  val model =
    ComponentColorModel(
      AwtColorSpace.getInstance(AwtColorSpace.CS_sRGB),
      true,
      false,
      Transparency.TRANSLUCENT,
      DataBuffer.TYPE_USHORT,
    )
  return pngOf(BufferedImage(model, raster, false, null))
}

private fun greyPng(): ByteArray {
  val levels = intArrayOf(0, 64, 128, 192, 230, 255)
  val image = BufferedImage(levels.size, 1, BufferedImage.TYPE_BYTE_GRAY)
  image.raster.setPixels(0, 0, levels.size, 1, levels)
  return pngOf(image)
}

// [png] with an iCCP chunk after its header carrying the JDK's linear RGB profile, which shares sRGB's primaries and
// drops its curve, so a decoder that honoured it would move every mid tone.
private fun taggedLinear(png: ByteArray): ByteArray {
  val profile = ICC_Profile.getInstance(AwtColorSpace.CS_LINEAR_RGB).data
  val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(profile) } }
  val body = "linear".encodeToByteArray() + byteArrayOf(0, 0) + compressed.toByteArray()
  val headerEnd = PNG_SIGNATURE + CHUNK_FRAME + IHDR_LENGTH
  return png.copyOfRange(0, headerEnd) + chunk("iCCP", body) + png.copyOfRange(headerEnd, png.size)
}

// The same texels as a TIFF carrying the JDK's linear RGB profile in its ICC tag.
private fun taggedLinearTiff(): ByteArray {
  val image = BufferedImage(TEXELS.size, 1, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, TEXELS.size, 1, TEXELS, 0, TEXELS.size)
  val writer = ImageIO.getImageWritersByFormatName("tiff").next()
  val param = writer.defaultWriteParam
  val directory =
    TIFFDirectory.createFromMetadata(
      writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param),
    )
  val profile = ICC_Profile.getInstance(AwtColorSpace.CS_LINEAR_RGB).data
  val tag = BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ICC_PROFILE)
  directory.addTIFFField(TIFFField(tag, TIFFTag.TIFF_UNDEFINED, profile.size, profile))
  val out = ByteArrayOutputStream()
  ImageIO.createImageOutputStream(out).use { stream ->
    writer.output = stream
    writer.write(null, IIOImage(image, null, directory.asMetadata), param)
  }
  writer.dispose()
  return out.toByteArray()
}

private fun chunk(
  type: String,
  body: ByteArray,
): ByteArray {
  val name = type.encodeToByteArray()
  val crc = CRC32().apply { update(name) }.apply { update(body) }
  return ByteArrayOutputStream()
    .also { out ->
      DataOutputStream(out).apply {
        writeInt(body.size)
        write(name)
        write(body)
        writeInt(crc.value.toInt())
      }
    }.toByteArray()
}

private fun pngOf(image: BufferedImage): ByteArray =
  ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()

private const val PNG_SIGNATURE = 8

// A chunk's length, type and CRC, four bytes each, around its body.
private const val CHUNK_FRAME = 12
private const val IHDR_LENGTH = 13
