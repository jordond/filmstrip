package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.authoredHdrTexels
import dev.jordond.filmstrip.media.signalFromNits
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.roundToInt

/**
 * An overlay picture carried onto an HDR grade's signal, as the bytes of a sixteen-bit RGBA PNG.
 *
 * Each texel's straight colour goes through [authoredHdrTexels] and then [HdrTransfer.signalFromNits], so an opaque
 * texel holds the signal the grade writes that colour's light at. Alpha is carried across untouched and the colour
 * stays straight, so the graph can decode the colour to light and read the alpha as the blend's mask. Sixteen bits
 * hold every ten-bit code the grade is written at.
 *
 * @property png The PNG's bytes.
 * @property size The picture's own size in pixels.
 */
internal class HdrOverlayRaster(
  val png: ByteArray,
  val size: Size,
)

/**
 * Decodes [image] and maps it onto [transfer]'s signal.
 *
 * The JDK decodes the picture when it has a reader for it, and [decoder] decodes it otherwise. A picture the JDK reads
 * with an embedded colour profile it honours reaches the mapping colour managed into sRGB. A grey picture reaches it
 * as the samples it stores, which the JDK would otherwise read as linear light. Every distinct colour is mapped once,
 * so a flat picture costs a single conversion.
 *
 * @return The mapped picture, [OverlayPictureRead.Refused] with [decoder]'s reason when neither can decode it, or
 * null when the JDK cannot and there is no [decoder] to ask.
 */
internal fun hdrOverlayRaster(
  image: ImageSource,
  transfer: HdrTransfer,
  decoder: OverlayPictureDecoder?,
): OverlayPictureRead<HdrOverlayRaster>? {
  val decoded =
    image.openHeader()?.let(::readWithJdk)
      ?: when (val read = decoder?.decode(image)) {
        is OverlayPictureRead.Read -> read.value
        is OverlayPictureRead.Refused -> return read
        null -> return null
      }

  val width = decoded.width
  val height = decoded.height
  val argb = storedArgb(decoded)
  val colours = argb.map { it or OPAQUE }.distinct().toIntArray()
  val signals = signalCodes(colours, transfer)
  val slotOf = colours.withIndex().associate { (slot, colour) -> colour to slot }

  val samples = IntArray(argb.size * CHANNELS)
  argb.forEachIndexed { index, texel ->
    val slot = checkNotNull(slotOf[texel or OPAQUE]) * 3
    val offset = index * CHANNELS
    samples[offset] = signals[slot]
    samples[offset + 1] = signals[slot + 1]
    samples[offset + 2] = signals[slot + 2]
    samples[offset + 3] = (texel ushr ALPHA_SHIFT) * WIDE_CODE_MAX / BYTE_MAX
  }

  return OverlayPictureRead.Read(HdrOverlayRaster(wideRgbaPng(samples, width, height), Size(width, height)))
}

// The picture the JDK decodes out of [stream], which is closed before this returns.
private fun readWithJdk(stream: ImageInputStream): BufferedImage? =
  try {
    ImageIO.read(stream)
  } catch (unreadable: IOException) {
    null
  } finally {
    closeQuietly(stream)
  }

// The texels as straight ARGB. The JDK tags every grey PNG as linear light whatever the file says, so getRGB would lift
// its mid tones, and a grey picture is read off its stored samples as sRGB grey instead. Every other picture goes
// through getRGB, which colour manages a profile the reader honoured into sRGB.
private fun storedArgb(picture: BufferedImage): IntArray {
  val width = picture.width
  val height = picture.height
  val model = picture.colorModel
  if (model !is ComponentColorModel || model.colorSpace !== ColorSpace.getInstance(ColorSpace.CS_GRAY)) {
    return picture.getRGB(0, 0, width, height, null, 0, width)
  }

  var pixel: Any? = null
  var components = FloatArray(model.numComponents)
  return IntArray(width * height) { index ->
    pixel = picture.raster.getDataElements(index % width, index / width, pixel)
    components = model.getNormalizedComponents(pixel, components, 0)
    val alpha = if (model.hasAlpha()) components[1] else 1f
    val level = byteOf(if (model.isAlphaPremultiplied && alpha > 0f) components[0] / alpha else components[0])
    (byteOf(alpha) shl ALPHA_SHIFT) or (level shl RED_SHIFT) or (level shl GREEN_SHIFT) or level
  }
}

private fun byteOf(normalized: Float): Int = (normalized.coerceIn(0f, 1f) * BYTE_MAX).roundToInt()

// ImageIO closes the stream after a picture it decoded and leaves it open otherwise, and a second
// close throws.
private fun closeQuietly(stream: ImageInputStream) {
  try {
    stream.close()
  } catch (closed: IOException) {
    // Already closed by the read.
  }
}

// The signal each colour's light is written at, as sixteen-bit codes, three per colour.
private fun signalCodes(
  colours: IntArray,
  transfer: HdrTransfer,
): IntArray {
  val texels = authoredHdrTexels(colours)
  val codes = IntArray(colours.size * 3)
  for (index in colours.indices) {
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

private const val OPAQUE = 0xFF shl 24
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MAX = 0xFF
private const val WIDE_CODE_MAX = 0xFFFF
private const val CHANNELS = 4
private const val TEXEL_FLOATS = 4
