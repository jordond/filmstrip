package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.signalFromNits
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
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import java.awt.color.ColorSpace as AwtColorSpace

/**
 * Pictures the JDK reads, carried onto an HDR grade where their colour lands, whichever reader decodes them.
 *
 * With a decoder every picture on a grade is decoded by this ffmpeg, and without one by the JDK, so both reads are held
 * against the same answer. Each picture is built on the JVM, run through the grade, and held against where
 * [pictureLight] puts its stored samples, at the depth the picture stores them. A grey picture lands where the same
 * grey stored as RGB does. A picture tagged with an ICC profile is held against where its profile's light puts it.
 */
class FfmpegOverlayPictureParityTest {
  private val fixture = FfmpegExportFixture()

  // The JDK's PNG reader does not read an iCCP chunk, so the profile is read out of the bytes and the samples off the
  // raster. ffmpeg's own decode carries the same profile, which is what a picture only ffmpeg reads relies on.
  @Test
  fun `a PNG tagged with a linear ICC profile lands where its profile's light puts it`() {
    val png = taggedLinear(eightBitPng())
    val light = pictureLight(pictureColour(png), opaque(TEXELS))
    assertLight(png, light, "linear ICC PNG")

    if (!fixture.available()) return
    val read = decoder.decode(ImageSource.ofBytes(png))
    val decoded = assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing from the PNG")
    assertContentEquals(light, pictureLight(decoded.colour, opaque(TEXELS)), "this ffmpeg's decode lost the profile")
  }

  /**
   * A TIFF tagged with the JDK's linear RGB profile lands where [pictureLight] puts its stored samples.
   *
   * The JDK's TIFF reader honours the profile, so a read through its colour model would land the picture on the JDK's
   * own conversion. The samples are read off the raster instead, so the TIFF lands where every other backend puts the
   * same tagged texels.
   */
  @Test
  fun `a TIFF tagged with a linear ICC profile lands where its profile's light puts it`() {
    val tiff = taggedLinearTiff()
    assertLight(tiff, pictureLight(pictureColour(tiff), opaque(TEXELS)), "linear ICC TIFF")
  }

  // The samples sit between eight-bit codes, so a read cut to eight bits on the way lands somewhere else.
  @Test
  fun `a sixteen bit PNG lands at the depth it stores`() {
    val samples = SIXTEEN_BIT_SAMPLES
    val light = pictureLight(null, ShortArray(samples.size) { samples[it].toShort() })
    assertLight(sixteenBitPng(), light, "sixteen bit PNG", samples.filterIndexed { at, _ -> at % CHANNELS == 3 })
  }

  @Test
  fun `a grey PNG lands where the same grey stored as RGB does`() {
    val texels = GREY_LEVELS.map { OPAQUE or (it shl RED_SHIFT) or (it shl GREEN_SHIFT) or it }.toIntArray()
    assertLight(greyPng(), pictureLight(null, texels), "grey PNG", texels.map { SIXTEEN_BIT_MAX })
  }

  // Each texel of [picture]'s first row, carried onto every grade, lands on the signal of [light], with [alphas]. The
  // picture is decoded by this ffmpeg when the catalogue has it, and by the JDK when it does not, so both are held.
  private fun assertLight(
    picture: ByteArray,
    light: FloatArray,
    what: String,
    alphas: List<Int> = TEXELS.map { (it ushr ALPHA_SHIFT) * SIXTEEN_BIT_MAX / BYTE_MAX },
  ) {
    val readers = if (fixture.available()) listOf(null, decoder) else listOf(null)
    for (reader in readers) {
      val by = if (reader == null) "the JDK" else "this ffmpeg"
      for (transfer in HdrTransfer.entries) {
        val graded = mappedRaster(picture, transfer, reader)
        assertEquals(alphas.size, graded.width, "$what read by $by is ${graded.width} wide on the grade")
        alphas.indices.forEach { x ->
          val signal = transfer.signalFromNits(light.copyOfRange(x * CHANNELS, x * CHANNELS + 3))
          val expected = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() } + alphas[x]
          val read = graded.getPixel(x, 0, IntArray(CHANNELS)).toList()
          assertEquals(expected, read, "$transfer $what read by $by texel $x")
        }
      }
    }
  }

  private fun opaque(texels: IntArray): IntArray = texels.map { it or OPAQUE }.toIntArray()

  private fun mappedRaster(
    png: ByteArray,
    transfer: HdrTransfer,
    reader: OverlayPictureDecoder? = decoder,
  ): Raster {
    val spec = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)
    val resolver = reader?.let(::BuiltInEffectResolver) ?: BuiltInEffectResolver()
    val resolved = resolver.resolve(spec, CAPABILITIES, attributes(transfer, 2.seconds))
    val mapped =
      (
        (resolved as EffectResolution.Resolved)
          .effect.fragment.auxInputs
          .first()
          .image as ImageSource.Bytes
      ).bytes
    return ImageIO.read(ByteArrayInputStream(mapped)).raster
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")

    const val CHANNELS = 4
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BYTE_MAX = 0xFF
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

// Samples that land between two eight-bit codes, so a read cut to eight bits lands elsewhere.
private val SIXTEEN_BIT_SAMPLES =
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

private fun sixteenBitPng(): ByteArray {
  val samples = SIXTEEN_BIT_SAMPLES
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

private val GREY_LEVELS = intArrayOf(0, 64, 128, 192, 230, 255)

private fun greyPng(): ByteArray {
  val levels = GREY_LEVELS
  val image = BufferedImage(levels.size, 1, BufferedImage.TYPE_BYTE_GRAY)
  image.raster.setPixels(0, 0, levels.size, 1, levels)
  return pngOf(image)
}

// The JDK's linear RGB profile, which shares sRGB's primaries and drops its curve, so honouring it moves every mid
// tone.
private val LINEAR_PROFILE: ByteArray = ICC_Profile.getInstance(AwtColorSpace.CS_LINEAR_RGB).data

// [png] with an iCCP chunk after its header carrying [LINEAR_PROFILE].
private fun taggedLinear(png: ByteArray): ByteArray {
  val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(LINEAR_PROFILE) } }
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
  val tag = BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ICC_PROFILE)
  directory.addTIFFField(TIFFField(tag, TIFFTag.TIFF_UNDEFINED, LINEAR_PROFILE.size, LINEAR_PROFILE))
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
