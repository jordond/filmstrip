package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.signalFromNits
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.awt.color.ColorSpace as AwtColorSpace

// Pictures and resolves the overlay picture tests share. Every container here is built in the test.

internal const val OPAQUE = 0xFF shl 24

internal val LINEAR: ByteArray = ICC_Profile.getInstance(AwtColorSpace.CS_LINEAR_RGB).data
internal val LINEAR_GREY: ByteArray = ICC_Profile.getInstance(AwtColorSpace.CS_GRAY).data

// The colour descriptions the linear profiles are, read through core the way a decoder's would be.
internal val LINEAR_COLOUR: PictureColour = checkNotNull(PictureColour.fromDecodedProfile(LINEAR, 3))
internal val LINEAR_GREY_COLOUR: PictureColour = checkNotNull(PictureColour.fromDecodedProfile(LINEAR_GREY, 1))

// Every texel opaque, a mid tone among them, so the light each lands at is read apart from its alpha.
internal val TEXELS =
  intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF40A040.toInt(), 0xFF1E90FF.toInt(), 0xFF000000.toInt())

// How far the linear profile moves a mid tone at the least, well clear of any rounding.
internal const val MID_TONE_MOVE = 32

// The side of each square a JPEG test picture is painted in, two of a subsampled JPEG's blocks.
internal const val BLOCK = 16

internal val FRAME = Size(640, 360)

/**
 * An overlay of [bytes] in the top start corner.
 */
internal fun overlayOf(bytes: ByteArray): ImageOverlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)

/**
 * The picture the graph opens for [spec] on an SDR grade.
 */
internal fun auxImage(
  spec: ImageOverlay,
  decoder: OverlayPictureDecoder?,
): ImageSource {
  val resolution = resolverOf(decoder).resolve(spec, capabilities(), attributes(null))
  val resolved = assertIs<EffectResolution.Resolved>(resolution)
  return resolved.effect.fragment.auxInputs
    .first()
    .image
}

/**
 * The sixteen-bit raster [spec] is mapped to on [transfer]'s grade.
 */
internal fun mappedRaster(
  spec: ImageOverlay,
  transfer: HdrTransfer,
  decoder: OverlayPictureDecoder? = null,
): Raster {
  val resolved =
    assertIs<EffectResolution.Resolved>(resolverOf(decoder).resolve(spec, capabilities(), attributes(transfer)))
  val mapped =
    assertIs<ImageSource.Bytes>(
      resolved.effect.fragment.auxInputs
        .first()
        .image,
    ).bytes
  return ImageIO.read(ByteArrayInputStream(mapped)).raster
}

internal fun mappedRaster(
  png: ByteArray,
  transfer: HdrTransfer,
): Raster = mappedRaster(overlayOf(png), transfer)

internal fun resolverOf(decoder: OverlayPictureDecoder?): BuiltInEffectResolver =
  decoder?.let(::BuiltInEffectResolver) ?: BuiltInEffectResolver()

/**
 * The sixteen-bit RGBA the grade's raster holds for an opaque [texel] described by [colour].
 */
internal fun signalOf(
  colour: PictureColour?,
  texel: Int,
  transfer: HdrTransfer,
): IntArray = wideSignal(pictureLight(colour, intArrayOf(texel)), transfer)

/**
 * The sixteen-bit RGBA the grade's raster holds for the opaque light in the first three of [light].
 */
internal fun wideSignal(
  light: FloatArray,
  transfer: HdrTransfer,
): IntArray {
  val signal = transfer.signalFromNits(light.copyOf(3))
  return IntArray(4) { channel ->
    if (channel == 3) SIXTEEN_BIT_MAX else (signal[channel].coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt()
  }
}

internal fun rgbOf(texels: IntArray): BufferedImage =
  BufferedImage(texels.size, 1, BufferedImage.TYPE_INT_ARGB).apply {
    setRGB(0, 0, texels.size, 1, texels, 0, texels.size)
  }

internal fun argbOf(png: ByteArray): IntArray {
  val image = ImageIO.read(ByteArrayInputStream(png))
  return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
}

internal fun channelGap(
  one: Int,
  other: Int,
): Int = listOf(16, 8, 0).maxOf { shift -> abs((one shr shift and 0xFF) - (other shr shift and 0xFF)) }

internal fun pngOf(image: BufferedImage): ByteArray =
  ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

internal fun jpegOf(image: BufferedImage): ByteArray =
  ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpg", it), "the JDK wrote no JPEG") }.toByteArray()

// A row of squares [BLOCK] pixels a side, one to a colour, so the middle of each holds its colour through a JPEG.
internal fun blocksOf(colours: IntArray): BufferedImage =
  BufferedImage(colours.size * BLOCK, BLOCK, BufferedImage.TYPE_INT_RGB).apply {
    for (x in 0 until width) {
      for (y in 0 until height) setRGB(x, y, colours[x / BLOCK])
    }
  }

internal fun blockCentres(count: Int): List<Int> = (0 until count).map { it * BLOCK + BLOCK / 2 }

/**
 * [png] with an iCCP chunk carrying [profile] straight after its header.
 */
internal fun tagged(
  png: ByteArray,
  profile: ByteArray,
): ByteArray {
  val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(profile) } }
  val body = "icc".encodeToByteArray() + byteArrayOf(0, 0) + compressed.toByteArray()
  val name = "iCCP".encodeToByteArray()
  val crc = CRC32().apply { update(name) }.apply { update(body) }
  val chunk =
    ByteArrayOutputStream()
      .also { out ->
        DataOutputStream(out).apply {
          writeInt(body.size)
          write(name)
          write(body)
          writeInt(crc.value.toInt())
        }
      }.toByteArray()
  return png.copyOfRange(0, PNG_HEADER_END) + chunk + png.copyOfRange(PNG_HEADER_END, png.size)
}

/**
 * [jpeg] with [segments] inserted after its JFIF header, which a JFIF reader wants first.
 */
internal fun withSegments(
  jpeg: ByteArray,
  vararg segments: ByteArray,
): ByteArray {
  val jfif = jpeg[3] == 0xE0.toByte()
  val at = if (jfif) 4 + ((jpeg[4].toInt() and 0xFF) shl 8 or (jpeg[5].toInt() and 0xFF)) else 2
  return jpeg.copyOfRange(0, at) + segments.reduce(ByteArray::plus) + jpeg.copyOfRange(at, jpeg.size)
}

/**
 * A JPEG marker segment of [marker] around [payload], its length counting itself.
 */
internal fun segment(
  marker: Int,
  payload: ByteArray,
): ByteArray {
  val length = payload.size + 2
  return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
}

/**
 * The `APP2` segment holding part [part] of [count] of an ICC profile, carrying [chunk].
 */
internal fun iccSegment(
  chunk: ByteArray,
  part: Int = 1,
  count: Int = 1,
): ByteArray = segment(APP2, "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0, part.toByte(), count.toByte()) + chunk)

/**
 * [jpeg] with one APP2 segment carrying [profile].
 */
internal fun taggedJpeg(
  jpeg: ByteArray,
  profile: ByteArray,
): ByteArray = withSegments(jpeg, iccSegment(profile))

/**
 * A RIFF WebP holding a VP8X header that flags a profile and an ICCP chunk carrying [profile], and no picture.
 */
internal fun taggedWebpHeader(profile: ByteArray): ByteArray {
  fun littleEndian(
    value: Int,
    bytes: Int,
  ): ByteArray = ByteArray(bytes) { (value shr (8 * it)).toByte() }

  fun chunk(
    type: String,
    body: ByteArray,
  ): ByteArray = type.encodeToByteArray() + littleEndian(body.size, 4) + body + ByteArray(body.size % 2)

  val side = littleEndian(WEBP_SIDE - 1, 3)
  val vp8x = chunk("VP8X", byteArrayOf(WEBP_ICC_FLAG, 0, 0, 0) + side + side)
  val body = "WEBP".encodeToByteArray() + vp8x + chunk("ICCP", profile)
  return "RIFF".encodeToByteArray() + littleEndian(body.size, 4) + body
}

/**
 * A decoder's answer for [image]'s texels, described by [colour].
 */
internal fun decodedOf(
  image: BufferedImage,
  colour: PictureColour?,
): DecodedPicture =
  DecodedPicture(
    Size(image.width, image.height),
    StoredSamples.Argb(image.getRGB(0, 0, image.width, image.height, null, 0, image.width)),
    colour,
  )

internal fun attributes(transfer: HdrTransfer?): Attributes =
  Attributes(
    inputSize = FRAME,
    outputSize = FRAME,
    layoutSize = FRAME,
    colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
    hdrTransfer = transfer,
    frameRate = RATE,
    span = TimeRange.of(Duration.ZERO, 2.seconds),
  )

internal fun capabilities(): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.FilterGraph,
    supportsFragmentShader = false,
    supportsComputeShader = false,
    supportsHdr = true,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
    maxTextureSize = 16_384,
    features = emptySet(),
  )

/**
 * A decoder answering every decode with [decoded] and every measure with [size], or refusing with [reason] when either
 * is null, and recording what it measured and decoded.
 */
internal class FakeDecoder(
  private val size: Size?,
  private val reason: String = "",
  val measured: MutableList<ImageSource> = mutableListOf(),
  private val decoded: DecodedPicture? = null,
  val decodes: MutableList<ImageSource> = mutableListOf(),
) : OverlayPictureDecoder {
  override fun decode(image: ImageSource): OverlayPictureRead<DecodedPicture> {
    decodes += image
    return decoded?.let { OverlayPictureRead.Read(it) } ?: OverlayPictureRead.Refused(reason)
  }

  override fun measure(image: ImageSource): OverlayPictureRead<Size> {
    measured += image
    return size?.let { OverlayPictureRead.Read(it) } ?: OverlayPictureRead.Refused(reason)
  }
}

internal const val APP1 = 0xE1
internal const val APP2 = 0xE2

private const val RATE = 30f

// The PNG signature and the IHDR chunk, its length, type and CRC around a thirteen byte body.
private const val PNG_HEADER_END = 8 + 12 + 13

private const val WEBP_SIDE = 64
private const val WEBP_ICC_FLAG: Byte = 0x20
