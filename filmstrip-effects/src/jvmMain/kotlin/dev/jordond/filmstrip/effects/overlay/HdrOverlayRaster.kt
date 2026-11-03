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
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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
 * light. Every distinct color is mapped once, so a flat picture costs a single conversion.
 */
internal fun hdrOverlayRaster(
  picture: StoredPicture,
  transfer: HdrTransfer,
): HdrOverlayRaster {
  val stored = picture.samples
  val slots = HashMap<Long, Int>()
  val firsts = mutableListOf<Int>()
  val slotOf =
    IntArray(stored.texelCount) { index ->
      slots.getOrPut(stored.colorKey(index)) { firsts.size.also { firsts += index } }
    }
  val signals = signalCodes(stored.opaqueAt(firsts.toIntArray()).light(picture.color), firsts.size, transfer)

  val samples = IntArray(slotOf.size * CHANNELS)
  slotOf.forEachIndexed { index, slot ->
    val offset = index * CHANNELS
    signals.copyInto(samples, offset, slot * 3, slot * 3 + 3)
    samples[offset + 3] = stored.wideAlpha(index)
  }

  val size = picture.size
  return HdrOverlayRaster(wideRgbaPng(samples, size.width, size.height), size)
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

private fun wideRgbaPng(
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

  return ByteArrayOutputStream()
    .also { check(ImageIO.write(image, "png", it)) { "The JDK has no PNG writer for a sixteen-bit picture." } }
    .toByteArray()
}

private const val WIDE_CODE_MAX = 0xFFFF
private const val CHANNELS = 4
private const val TEXEL_FLOATS = 4
