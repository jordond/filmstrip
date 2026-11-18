package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferInt
import java.awt.image.DataBufferUShort
import java.awt.image.Raster
import java.awt.image.RenderedImage
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * The PNG an SDR grade's graph opens for [picture], an animated picture's atlas.
 *
 * The samples go through [pictureSrgbCodes] under the picture's color, so a tagged atlas reaches the graph in the sRGB
 * codes its description gives it. An eight-bit atlas with no color is the codes already and is written as it is. A deep
 * atlas that lands where it is stored keeps its sixteen-bit samples, so it reaches the graph at the depth a deep PNG
 * does. The PNG carries no color description, so its [SdrOverlayImage.Drawn.orientation] is the normal one.
 */
internal fun sdrAtlasImage(picture: StoredPicture): SdrOverlayImage.Drawn {
  val samples = picture.samples
  val png =
    if (samples is StoredSamples.Rgba16 && samples.landsAsStored(picture.color)) {
      rgba16Png(samples.rgba, picture.size)
    } else if (samples is StoredSamples.Argb && picture.color == null) {
      argbPng(samples.argb, picture.size)
    } else {
      argbPng(samples.srgbCodes(picture.color), picture.size)
    }
  return SdrOverlayImage.Drawn(ImageSource.ofBytes(png), picture.size)
}

// Whether the codes of [color] are the codes the samples store, so that the atlas keeps its sixteen bits. Both sets of
// codes are let go when this returns.
private fun StoredSamples.Rgba16.landsAsStored(color: PictureColor?): Boolean =
  srgbCodes(color).contentEquals(srgbCodes(null))

// An eight-bit RGBA PNG of [argb] with no color description in it, written from the array itself.
internal fun argbPng(
  argb: IntArray,
  size: Size,
): ByteArray {
  val raster =
    Raster.createPackedRaster(
      DataBufferInt(argb, argb.size),
      size.width,
      size.height,
      size.width,
      ARGB_MASKS,
      null,
    )
  val image = BufferedImage(ColorModel.getRGBdefault(), raster, false, null)
  return pngBytes(image, "The JDK has no PNG writer for an eight-bit picture.")
}

// A sixteen-bit RGBA PNG of [rgba], four samples a texel, with no color description in it.
internal fun rgba16Png(
  rgba: ShortArray,
  size: Size,
): ByteArray {
  val model =
    ComponentColorModel(
      ColorSpace.getInstance(ColorSpace.CS_sRGB),
      true,
      false,
      Transparency.TRANSLUCENT,
      DataBuffer.TYPE_USHORT,
    )
  val buffer = DataBufferUShort(rgba, rgba.size)
  val raster =
    Raster.createInterleavedRaster(
      buffer,
      size.width,
      size.height,
      size.width * RGBA_BANDS.size,
      RGBA_BANDS.size,
      RGBA_BANDS,
      null,
    )
  val image = BufferedImage(model, raster, false, null)
  return pngBytes(image, "The JDK has no PNG writer for a sixteen-bit picture.")
}

/**
 * The bytes of [image] as a PNG, or an [IllegalStateException] saying [missing] when the JDK has no writer for it.
 *
 * The file is collected in fixed chunks of 1 MiB and then copied once into an array of its exact size. Both are held at
 * the end of the copy, so the file is in memory twice at most.
 */
internal fun pngBytes(
  image: RenderedImage,
  missing: String,
): ByteArray {
  val chunks = ChunkedBytes()
  check(ImageIO.write(image, "png", chunks)) { missing }
  return chunks.toByteArray()
}

private class ChunkedBytes : OutputStream() {
  private val full = ArrayList<ByteArray>()
  private var chunk = ByteArray(CHUNK_BYTES)
  private var used = 0

  override fun write(byte: Int) {
    if (used == chunk.size) next()
    chunk[used++] = byte.toByte()
  }

  override fun write(
    bytes: ByteArray,
    offset: Int,
    length: Int,
  ) {
    var from = offset
    var left = length
    while (left > 0) {
      if (used == chunk.size) next()
      val take = minOf(left, chunk.size - used)
      bytes.copyInto(chunk, used, from, from + take)
      used += take
      from += take
      left -= take
    }
  }

  fun toByteArray(): ByteArray {
    val out = ByteArray(full.size * CHUNK_BYTES + used)
    full.forEachIndexed { index, part -> part.copyInto(out, index * CHUNK_BYTES) }
    chunk.copyInto(out, full.size * CHUNK_BYTES, 0, used)
    return out
  }

  private fun next() {
    full += chunk
    chunk = ByteArray(CHUNK_BYTES)
    used = 0
  }

  private companion object {
    const val CHUNK_BYTES = 1 shl 20
  }
}

/**
 * Whether [bytes] are a still WebP whose picture is lossy, a `VP8 ` chunk and not a `VP8L` one.
 *
 * An animated WebP, one with no picture chunk and one that is cut short are not.
 */
internal fun isLossyWebp(bytes: ByteArray): Boolean {
  if (bytes.size < WEBP_FIRST_CHUNK || !bytes.hasTag(0, "RIFF") || !bytes.hasTag(WEBP_FORM, "WEBP")) return false
  var at = WEBP_FIRST_CHUNK
  while (at + CHUNK_HEADER < bytes.size) {
    val size = (0 until CHUNK_SIZE).sumOf { (bytes[at + CHUNK_SIZE_AT + it].toLong() and BYTE) shl (BYTE_BITS * it) }
    when {
      bytes.hasTag(at, "VP8X") && bytes[at + CHUNK_HEADER].toInt() and ANIMATION_FLAG != 0 -> return false
      bytes.hasTag(at, "VP8L") -> return false
      bytes.hasTag(at, "VP8 ") -> return true
    }
    val next = at + CHUNK_HEADER + size + (size and 1L)
    if (next > bytes.size) return false
    at = next.toInt()
  }
  return false
}

private fun ByteArray.hasTag(
  at: Int,
  tag: String,
): Boolean = tag.indices.all { this[at + it] == tag[it].code.toByte() }

private val ARGB_MASKS = intArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000.toInt())
private val RGBA_BANDS = intArrayOf(0, 1, 2, 3)
private const val WEBP_FORM = 8
private const val WEBP_FIRST_CHUNK = 12
private const val CHUNK_HEADER = 8
private const val CHUNK_SIZE_AT = 4
private const val CHUNK_SIZE = 4
private const val ANIMATION_FLAG = 0x02
private const val BYTE = 0xFFL
private const val BYTE_BITS = 8
