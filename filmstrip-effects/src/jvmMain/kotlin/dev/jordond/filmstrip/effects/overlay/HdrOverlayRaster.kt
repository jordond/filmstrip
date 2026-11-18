package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.Raster
import kotlin.math.roundToInt

/**
 * An overlay picture carried onto an HDR grade's signal, as the bytes of a sixteen-bit RGBA PNG.
 *
 * Each texel's straight color goes through [pictureLight] and then [HdrTransfer.signalFromNits], so an opaque texel
 * holds the signal the grade writes that color's light at. Alpha is carried across untouched and the color stays
 * straight, so the graph can decode the color to light and read the alpha as the blend's mask. Sixteen bits hold
 * every ten-bit code the grade is written at.
 *
 * @property png The PNG's bytes.
 * @property size The picture's own size in pixels.
 */
internal class HdrOverlayRaster(
  val png: ByteArray,
  val size: Size,
)

/**
 * Maps [picture] onto [transfer]'s signal.
 *
 * The picture comes from [storedPicture], so its texels reach the mapping as the samples it stores, at the depth it
 * stores them, with the color description that defines them, and [pictureLight] lands them in the grade's display
 * light. Every distinct color of a band of texels is mapped once, so a flat picture costs one conversion a band.
 */
internal fun hdrOverlayRaster(
  picture: StoredPicture,
  transfer: HdrTransfer,
): HdrOverlayRaster {
  val size = picture.size
  return HdrOverlayRaster(rgba16Png(signalSamples(picture, transfer), size), size)
}

// The samples of the signal PNG, four unsigned sixteen-bit values a texel, written straight into the array the PNG is
// encoded from. The texels are mapped in bands, and each band maps its own distinct colors once, so what is held beside
// the samples is one band's table however many distinct colors the picture has.
private fun signalSamples(
  picture: StoredPicture,
  transfer: HdrTransfer,
): ShortArray {
  val stored = picture.samples
  val count = stored.texelCount
  val samples = ShortArray(count * CHANNELS)
  var start = 0
  while (start < count) {
    val end = minOf(count, start + BAND_TEXELS)
    val slots = HashMap<Long, Int>()
    val firsts = mutableListOf<Int>()
    val slotOf =
      IntArray(end - start) { offset ->
        slots.getOrPut(stored.colorKey(start + offset)) { firsts.size.also { firsts += start + offset } }
      }
    val signals = signalCodes(stored.opaqueAt(firsts.toIntArray()).light(picture.color), firsts.size, transfer)
    slotOf.forEachIndexed { offset, slot ->
      val at = (start + offset) * CHANNELS
      for (channel in 0 until 3) samples[at + channel] = signals[slot * 3 + channel].toShort()
      samples[at + 3] = stored.wideAlpha(start + offset).toShort()
    }
    start = end
  }
  return samples
}

// The signal each of [count] colors' light is written at, as sixteen-bit codes, three per color.
private fun signalCodes(
  texels: FloatArray,
  count: Int,
  transfer: HdrTransfer,
): IntArray {
  val codes = IntArray(count * 3)
  for (index in 0 until count) {
    val offset = index * TEXEL_FLOATS
    val signal = transfer.signalFromNits(texels.copyOfRange(offset, offset + 3))
    for (channel in 0 until 3) {
      codes[index * 3 + channel] = (signal[channel].coerceIn(0f, 1f) * WIDE_CODE_MAX).roundToInt()
    }
  }
  return codes
}

/**
 * The bytes of a [width] by [height] sixteen-bit RGBA PNG of [samples], four unsigned values a texel row by row.
 *
 * The alpha is straight, as the samples hold it.
 */
internal fun wideRgbaPng(
  samples: IntArray,
  width: Int,
  height: Int,
): ByteArray {
  val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, CHANNELS, null)
  raster.setPixels(0, 0, width, height, samples)
  val model =
    ComponentColorModel(
      ColorSpace.getInstance(ColorSpace.CS_sRGB),
      true,
      false,
      Transparency.TRANSLUCENT,
      DataBuffer.TYPE_USHORT,
    )
  val image = BufferedImage(model, raster, false, null)

  return pngBytes(image, "The JDK has no PNG writer for a sixteen-bit picture.")
}

private const val WIDE_CODE_MAX = 0xFFFF
private const val CHANNELS = 4
private const val BAND_TEXELS = 1 shl 16
private const val TEXEL_FLOATS = 4
