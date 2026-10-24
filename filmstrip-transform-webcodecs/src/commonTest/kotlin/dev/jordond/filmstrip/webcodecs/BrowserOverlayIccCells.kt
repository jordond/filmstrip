@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.sdrSignalFromNits
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.webcodecs.internal.ReadbackCanvas
import dev.jordond.filmstrip.webcodecs.internal.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.compositorLightOf
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An overlay picture's bytes, and the codes or samples it stores when they are known ahead of decoding.
 *
 * @property name What the picture is called in a failure message.
 * @property bytes The picture's file.
 * @property stored The picture's stored texel, or null when only the browser's own decode can tell.
 */
internal class OverlayPicture(
  val name: String,
  val bytes: ByteArray,
  val stored: StoredTexel?,
) {
  override fun toString(): String = name
}

/**
 * Every picture the overlay tests draw, each painted one texel all over.
 *
 * The eight-bit ones are an untagged PNG, two PNGs at half alpha, a PNG per ICC profile and per other colour
 * description, and the AVIFs. The sixteen-bit ones are the HDR PNGs and their samples written untagged.
 */
internal object OverlayPictures {
  val PNG = Size(8, 8)
  val PIXELS = PNG.width * PNG.height

  // Half alpha on the untagged texel and on Display P3's, which reach the compositor unpremultiplied.
  private val HALF_ALPHA_UNTAGGED = halfAlpha(ColourPictures.TEXEL)
  private val HALF_ALPHA_P3 = halfAlpha(IccPictures.DISPLAY_P3.texel)

  val PICTURES: List<OverlayPicture> =
    listOf(eightBit("untagged", OverlayPixels.pngOf(PNG, ColourPictures.TEXEL), ColourPictures.TEXEL)) +
      eightBit("half-alpha untagged", OverlayPixels.pngOf(PNG, HALF_ALPHA_UNTAGGED), HALF_ALPHA_UNTAGGED) +
      eightBit(
        "half-alpha iCCP ${IccPictures.DISPLAY_P3.name}",
        OverlayPixels.pngOf(PNG, HALF_ALPHA_P3, IccPictures.DISPLAY_P3.profile),
        HALF_ALPHA_P3,
      ) +
      IccPictures.ALL.map { eightBit("iCCP ${it.name}", it.pngOf(PNG), it.texel) } +
      ColourPictures.PNGS.map { eightBit(it.name, it.pngOf(PNG), it.texel) } +
      ColourPictures.AVIFS.map { OverlayPicture("AVIF ${it.name}", it.bytes(), stored = null) }

  // The HDR pictures' own samples, written with no colour chunk at all.
  private val UNTAGGED_SAMPLES = ColourPictures.CICP_PQ.texel

  val SIXTEEN_BIT_PICTURES: List<OverlayPicture> =
    ColourPictures.HDR_PNGS.map { sixteenBit("sixteen-bit ${it.name}", it.pngOf(PNG), it.texel) } +
      sixteenBit("sixteen-bit untagged", OverlayPixels.sixteenBitPngOf(PNG, UNTAGGED_SAMPLES), UNTAGGED_SAMPLES)

  private fun halfAlpha(argb: Int): Int = (argb and 0x00FFFFFF) or (HALF_ALPHA shl ALPHA_SHIFT)

  private fun eightBit(
    name: String,
    bytes: ByteArray,
    argb: Int,
  ) = OverlayPicture(name, bytes, StoredTexel.EightBit(argb))

  private fun sixteenBit(
    name: String,
    bytes: ByteArray,
    samples: List<Int>,
  ) = OverlayPicture(name, bytes, StoredTexel.SixteenBit(samples))
}

/**
 * The one texel a test picture is painted with, as the picture stores it before any colour conversion.
 *
 * Either form answers through the shared functions, so an expected code is never copied in by hand.
 */
internal sealed interface StoredTexel {
  fun srgbCodes(colour: PictureColour?): Int

  fun light(colour: PictureColour?): FloatArray

  class EightBit(
    private val argb: Int,
  ) : StoredTexel {
    override fun srgbCodes(colour: PictureColour?): Int = pictureSrgbCodes(colour, intArrayOf(argb)).single()

    override fun light(colour: PictureColour?): FloatArray = pictureLight(colour, intArrayOf(argb))
  }

  class SixteenBit(
    val samples: List<Int>,
  ) : StoredTexel {
    private val rgba = ShortArray(samples.size) { samples[it].toShort() }

    override fun srgbCodes(colour: PictureColour?): Int = pictureSrgbCodes(colour, rgba).single()

    override fun light(colour: PictureColour?): FloatArray = pictureLight(colour, rgba)
  }
}

/**
 * One picture drawn at one opacity over a fill, with the texel it stores and the colour its bytes describe.
 *
 * Each reading the tests compare against is worked out here from the shared functions.
 */
internal class OverlayCell(
  val picture: OverlayPicture,
  val spec: ImageOverlay,
  val opacity: Float,
  val colour: PictureColour?,
  val stored: StoredTexel,
) {
  // The texel's own alpha and the overlay's opacity together.
  private val alpha: Float = (stored.srgbCodes(colour) ushr ALPHA_SHIFT) / MAX_CODE.toFloat() * opacity

  /**
   * Whether the picture is blended with the fill under it rather than covering it.
   *
   * A texel short of opaque blends, and so does an opacity under one.
   */
  val translucent: Boolean get() = alpha < 1f

  /**
   * The sRGB codes the picture lands on over [fill], blended on the frame's own codes by its alpha and the opacity.
   */
  fun sdrCodes(fill: Int): IntArray {
    val over = AuthoredColours.channelsOf(stored.srgbCodes(colour))
    val under = AuthoredColours.channelsOf(fill)
    return IntArray(3) { OverlayPixels.sourceOver(under[it], over[it], alpha) }
  }

  /**
   * The light the picture lands on over [fill], blended in linear display light by its alpha and the opacity.
   */
  fun nits(fill: Int): FloatArray {
    val texel = stored.light(colour)
    return OverlayPixels.hdrSourceOver(authoredHdrNits(fill), texel.copyOf(3), texel[3] * opacity)
  }

  /**
   * The eight-bit codes a kept grade's preview shows for the picture over [fill], before they are rounded.
   *
   * Every buffer on the way holds half floats: the overlay's premultiplied texel, the fill the frame is cleared to and
   * the blend of the two. Each is rounded to its nearest half float here as it is stored, a tie going up when [tiesUp]
   * is set and to even otherwise, then the blend is clamped at reference white and put through the SDR display curve.
   * [asUntagged] reads the stored texel as though the picture carried no colour, which is where a compositor that
   * dropped [colour] would put it.
   */
  fun presentedLevels(
    fill: Int,
    asUntagged: Boolean = false,
    tiesUp: Boolean = false,
  ): DoubleArray {
    val texel = stored.light(if (asUntagged) null else colour)
    val light = compositorLightOf(texel.copyOf(3))
    val under = compositorLightOf(authoredHdrNits(fill))
    val texelAlpha = halfFloat(texel[3], tiesUp)
    val alpha = texelAlpha * opacity
    return DoubleArray(3) {
      val straight = halfFloat(light[it] * texel[3], tiesUp) / texelAlpha
      val held = halfFloat(straight * alpha + halfFloat(under[it], tiesUp) * (1f - alpha), tiesUp)
      sdrSignalFromNits(held.toDouble() * HDR_REFERENCE_WHITE_NITS).coerceAtMost(1.0) * MAX_CODE
    }
  }

  /**
   * [presentedLevels] rounded to the codes the preview's eight-bit canvas holds.
   *
   * Takes the same arguments and reads the same buffers.
   */
  fun presented(
    fill: Int,
    asUntagged: Boolean = false,
    tiesUp: Boolean = false,
  ): IntArray = presentedLevels(fill, asUntagged, tiesUp).map { it.roundToInt() }.toIntArray()

  override fun toString(): String = "$picture at $opacity"
}

/**
 * [value] rounded to its nearest IEEE 754 half float, the way a float lands in an RGBA16F texel.
 *
 * A half float keeps eleven significant bits, so its step is the value's power of two over 1024, and never smaller
 * than 2^-24 where the format runs out of exponent. Every value here is far inside the largest half float. A value
 * exactly halfway between two half floats goes to the larger one when [tiesUp] is set and to the even one otherwise.
 */
internal fun halfFloat(
  value: Float,
  tiesUp: Boolean = false,
): Float {
  if (value == 0f || !value.isFinite()) return value
  val magnitude = abs(value.toDouble())
  val exponent = ((magnitude.toRawBits() ushr DOUBLE_MANTISSA_BITS).toInt() and DOUBLE_EXPONENT_MASK) - DOUBLE_BIAS
  val step = 2.0.pow(exponent.coerceAtLeast(HALF_MIN_EXPONENT) - HALF_MANTISSA_BITS)
  val steps = magnitude / step
  val rounded = (if (tiesUp) floor(steps + 0.5) else round(steps)) * step
  return (if (value < 0f) -rounded else rounded).toFloat()
}

/**
 * The packed ARGB codes at the middle of the overlay the browser resolver makes of [bytes], decoded with no colour
 * conversion.
 *
 * They are read back through a 2D canvas the way the compositor reads a raster, so an AVIF Chrome lands through
 * another matrix than its own is read the way it is drawn.
 */
internal suspend fun decodedCodeOf(bytes: ByteArray): Int {
  val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
  val resolver = BuiltInEffectResolver()
  resolver.preload(EditComposition(listOf(Track(listOf(Clip(MediaSource.ofUri(READ_CLIP), effects = listOf(spec)))))))
  val effect =
    when (val resolution = resolver.resolve(spec, READ_CAPABILITIES, READ_ATTRIBUTES)) {
      is EffectResolution.Resolved -> resolution.effect
      is EffectResolution.Degraded -> resolution.effect
      else -> fail("the overlay did not resolve: $resolution")
    }
  val overlay = assertNotNull(effect.overlay)
  val (width, height) = overlay.size.width to overlay.size.height
  val context = assertNotNull(ReadbackCanvas(width, height).getContext("2d"))
  context.drawImage(overlay.image, 0.0, 0.0)
  val data = Uint8Array(context.getImageData(0, 0, width, height).data.buffer)
  val offset = ((height / 2) * width + width / 2) * 4
  return (data.at(offset + 3) shl 24) or (data.at(offset) shl 16) or (data.at(offset + 1) shl 8) or data.at(offset + 2)
}

/**
 * Straight [texels] as the compositor uploads them on a kept grade.
 *
 * Each is held where [compositorLightOf] puts its light, then multiplied by its alpha.
 */
internal fun premultiplied(texels: FloatArray): FloatArray {
  val out = texels.copyOf()
  for (offset in out.indices step 4) {
    val light = compositorLightOf(out.copyOfRange(offset, offset + 3))
    repeat(3) { out[offset + it] = light[it] * out[offset + 3] }
  }
  return out
}

/**
 * Straight RGBA codes, four bytes a texel, packed into ARGB ints.
 *
 * The inverse of [rgbaOf].
 */
internal fun argbOf(codes: ByteArray): IntArray =
  IntArray(codes.size / 4) { index ->
    val channel = { at: Int -> codes[index * 4 + at].toInt() and 0xFF }
    (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
  }

/**
 * A packed ARGB int as the four RGBA bytes a texture is uploaded with.
 *
 * The inverse of [argbOf].
 */
internal fun rgbaOf(argb: Int): List<Byte> = listOf(argb ushr 16, argb ushr 8, argb, argb ushr 24).map { it.toByte() }

/**
 * Three texels of straight RGBA codes whose channels sit well inside the range.
 *
 * A conversion that only held at black and white would miss them.
 */
internal fun midRangeCodes(): ByteArray =
  listOf(ColourPictures.TEXEL, AuthoredColours.MID_GREY, AuthoredColours.MID_GREEN)
    .flatMap { argb -> AuthoredColours.channelsOf(argb) + (argb ushr 24) }
    .map { it.toByte() }
    .toByteArray()

private val READ_FRAME = Size(1280, 720)

private val READ_CAPABILITIES =
  RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = false,
    colorSpaces = setOf(ColorSpace.Bt709),
    maxTextureSize = 4_096,
    features = emptySet(),
  )

private val READ_ATTRIBUTES =
  Attributes(
    inputSize = READ_FRAME,
    outputSize = READ_FRAME,
    layoutSize = READ_FRAME,
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = TimeRange.of(Duration.ZERO, 10.seconds),
  )

private const val READ_CLIP = "blob:clip"

private const val MAX_CODE = 255.0
private const val ALPHA_SHIFT = 24
private const val HALF_ALPHA = 0x80
private const val DOUBLE_MANTISSA_BITS = 52
private const val DOUBLE_EXPONENT_MASK = 0x7FF
private const val DOUBLE_BIAS = 1023
private const val HALF_MANTISSA_BITS = 10
private const val HALF_MIN_EXPONENT = -14
