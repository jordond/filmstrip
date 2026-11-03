package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import kotlin.math.roundToInt

/**
 * An overlay picture's straight, unpremultiplied samples, before any color conversion.
 *
 * A picture of eight bits a channel or fewer is held as [Argb], and a deeper one as [Rgba16], so a sixteen-bit or HDR
 * picture keeps every bit it stores.
 */
@InternalFilmstripApi
public sealed interface StoredSamples {
  /**
   * Samples of eight bits a channel.
   *
   * @property argb One packed ARGB color per texel.
   */
  @InternalFilmstripApi
  public class Argb(
    public val argb: IntArray,
  ) : StoredSamples

  /**
   * Samples deeper than eight bits a channel, each scaled to sixteen bits.
   *
   * @property rgba Four samples per texel, red, green, blue and alpha, each read as an unsigned sixteen-bit value.
   */
  @InternalFilmstripApi
  public class Rgba16(
    public val rgba: ShortArray,
  ) : StoredSamples
}

/**
 * The texels as the picture stores them, or null for a color model that is neither RGB, grey nor a palette.
 *
 * A palette's entries are stored codes, so a palette picture is read through them. Every other RGB or grey picture is
 * read off its raster, so neither a profile the JDK honours nor the linear light the JDK tags every grey PNG with moves
 * a sample. A picture whose bands are eight bits or fewer comes back as [StoredSamples.Argb], and a deeper one as
 * [StoredSamples.Rgba16], each band scaled from its own depth.
 */
internal fun BufferedImage.storedSamples(): StoredSamples? {
  val model = colorModel
  if (model is IndexColorModel) return StoredSamples.Argb(getRGB(0, 0, width, height, null, 0, width))
  val grey =
    when (model.colorSpace.type) {
      ColorSpace.TYPE_RGB -> false
      ColorSpace.TYPE_GRAY -> true
      else -> return null
    }

  val bands = model.numComponents
  val floating = raster.dataBuffer.dataType.let { it == DataBuffer.TYPE_FLOAT || it == DataBuffer.TYPE_DOUBLE }
  val deep = floating || (0 until bands).any { model.getComponentSize(it) > EIGHT_BITS }
  val alphaBand = if (model.hasAlpha()) model.numColorComponents else NO_BAND
  val full = FloatArray(bands) { if (floating) 1f else ((1L shl model.getComponentSize(it)) - 1).toFloat() }
  val premultiplied = model.isAlphaPremultiplied
  val argb = if (deep) null else IntArray(width * height)
  val rgba = if (deep) ShortArray(width * height * RGBA) else null
  val row = FloatArray(width * bands)
  val levels = FloatArray(RGBA)
  for (y in 0 until height) {
    raster.getPixels(0, y, width, 1, row)
    for (x in 0 until width) {
      val at = x * bands
      val alpha = if (alphaBand == NO_BAND) 1f else (row[at + alphaBand] / full[alphaBand]).coerceIn(0f, 1f)
      val unmultiply = if (premultiplied && alpha > 0f) alpha else 1f
      for (channel in 0 until 3) {
        val band = if (grey) 0 else channel
        levels[channel] = (row[at + band] / full[band] / unmultiply).coerceIn(0f, 1f)
      }
      levels[3] = alpha
      val texel = y * width + x
      if (argb != null) {
        fun code(channel: Int): Int = (levels[channel] * BYTE_MAX).roundToInt()
        argb[texel] = (code(3) shl ALPHA_SHIFT) or (code(0) shl RED_SHIFT) or (code(1) shl GREEN_SHIFT) or code(2)
      } else {
        for (channel in 0 until RGBA) {
          checkNotNull(rgba)[texel * RGBA + channel] = (levels[channel] * WIDE_MAX).roundToInt().toShort()
        }
      }
    }
  }
  return argb?.let { StoredSamples.Argb(it) } ?: StoredSamples.Rgba16(checkNotNull(rgba))
}

internal val StoredSamples.texelCount: Int
  get() =
    when (this) {
      is StoredSamples.Argb -> argb.size
      is StoredSamples.Rgba16 -> rgba.size / RGBA
    }

/**
 * The color of the texel at [index] with its alpha left out, as one key that tells apart every color the samples
 * can hold.
 */
internal fun StoredSamples.colorKey(index: Int): Long =
  when (this) {
    is StoredSamples.Argb -> {
      (argb[index] and RGB_MASK).toLong()
    }
    is StoredSamples.Rgba16 -> {
      val at = index * RGBA
      (wide(at) shl WIDE_BITS * 2) or (wide(at + 1) shl WIDE_BITS) or wide(at + 2)
    }
  }

/**
 * The alpha of the texel at [index] as a sixteen-bit sample.
 */
internal fun StoredSamples.wideAlpha(index: Int): Int =
  when (this) {
    is StoredSamples.Argb -> (argb[index] ushr ALPHA_SHIFT) * WIDE_MAX / BYTE_MAX
    is StoredSamples.Rgba16 -> wide(index * RGBA + 3).toInt()
  }

/**
 * The texels at [indices], in that order and each made opaque.
 */
internal fun StoredSamples.opaqueAt(indices: IntArray): StoredSamples =
  when (this) {
    is StoredSamples.Argb -> {
      StoredSamples.Argb(IntArray(indices.size) { argb[indices[it]] or OPAQUE })
    }
    is StoredSamples.Rgba16 -> {
      val picked = ShortArray(indices.size * RGBA)
      indices.forEachIndexed { slot, index ->
        rgba.copyInto(picked, slot * RGBA, index * RGBA, index * RGBA + 3)
        picked[slot * RGBA + 3] = WIDE_MAX.toShort()
      }
      StoredSamples.Rgba16(picked)
    }
  }

/**
 * The linear BT.2020 display light [pictureLight] gives these samples under [color], four floats per texel.
 */
internal fun StoredSamples.light(color: PictureColor?): FloatArray =
  when (this) {
    is StoredSamples.Argb -> pictureLight(color, argb)
    is StoredSamples.Rgba16 -> pictureLight(color, rgba)
  }

/**
 * The sRGB codes [pictureSrgbCodes] gives these samples under [color], one packed ARGB color per texel.
 */
internal fun StoredSamples.srgbCodes(color: PictureColor?): IntArray =
  when (this) {
    is StoredSamples.Argb -> pictureSrgbCodes(color, argb)
    is StoredSamples.Rgba16 -> pictureSrgbCodes(color, rgba)
  }

private fun StoredSamples.Rgba16.wide(at: Int): Long = rgba[at].toLong() and WIDE_MAX.toLong()

private const val NO_BAND = -1
private const val RGBA = 4
private const val EIGHT_BITS = 8
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val WIDE_BITS = 16
private const val ALPHA_SHIFT = 24
private const val BYTE_MAX = 0xFF
private const val WIDE_MAX = 0xFFFF
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE = 0xFF shl ALPHA_SHIFT
