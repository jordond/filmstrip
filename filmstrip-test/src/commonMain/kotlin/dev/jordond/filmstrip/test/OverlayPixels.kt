package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.authoredHdrNits
import kotlin.math.roundToInt

/**
 * The overlay images the pixel pins draw, where a blended overlay pixel should land, and how far an
 * encoded reading of one may sit from it.
 *
 * An overlay test paints its image from [pngOf] so it knows the colour it authored, predicts a partly
 * opaque draw with [sourceOver], or with [hdrSourceOver] on an HDR grade, and compares an exported reading
 * with [ENCODED_TOLERANCE]. The authored colours themselves and the colour-handling tolerance live in
 * [AuthoredColours].
 */
@InternalFilmstripApi
public object OverlayPixels {
  /**
   * How far a blended overlay pixel read back out of an H.264 export may sit from its prediction, in
   * code values per channel.
   *
   * The prediction is the same pixel read without the encode, or a blend of readings off the same
   * file. Either way each side has been through the encoder and decoder at most once, and this bounds
   * the codec noise that round trip leaves on a pixel mixed from the overlay and the frame under it. It
   * says nothing about colour handling, which [AuthoredColours.TOLERANCE] measures.
   */
  public const val ENCODED_TOLERANCE: Float = 6f

  /**
   * Where a source over draw of a channel at [over] with [opacity] leaves a channel at [under], worked
   * on the eight-bit codes themselves and rounded to the nearest code.
   */
  public fun sourceOver(
    under: Int,
    over: Int,
    opacity: Float,
  ): Int = (under + opacity * (over - under)).roundToInt()

  /**
   * Where an [overlay] colour drawn at [alpha] over [background] display light lands on an HDR grade, in
   * cd/m2.
   *
   * The overlay is mapped through [authoredHdrNits] and blended with the background in linear light, so
   * an opaque overlay lands where a fill of the same colour does. [alpha] is the texel's alpha times the
   * overlay's opacity, and the alpha packed in [overlay] is ignored.
   *
   * @param background Linear BT.2020 red, green and blue in cd/m2, as [authoredHdrNits] returns them.
   * @param overlay The packed ARGB colour drawn over [background].
   * @param alpha How much of the overlay covers the background, from 0 to 1.
   * @return Red, green and blue in that order.
   */
  public fun hdrSourceOver(
    background: FloatArray,
    overlay: Int,
    alpha: Float,
  ): FloatArray = hdrSourceOver(background, authoredHdrNits(overlay), alpha)

  /**
   * Where an [overlay] light drawn at [alpha] over [background] display light lands on an HDR grade, in cd/m2.
   *
   * Both lights are linear BT.2020 red, green and blue in cd/m2, and they are blended in linear light, so an opaque
   * overlay lands on its own light. [alpha] is the texel's alpha times the overlay's opacity.
   *
   * @param background Linear BT.2020 red, green and blue in cd/m2.
   * @param overlay Linear BT.2020 red, green and blue in cd/m2, such as a tagged picture's texel in display light.
   * @param alpha How much of the overlay covers the background, from 0 to 1.
   * @return Red, green and blue in that order.
   */
  public fun hdrSourceOver(
    background: FloatArray,
    overlay: FloatArray,
    alpha: Float,
  ): FloatArray = FloatArray(3) { alpha * overlay[it] + (1f - alpha) * background[it] }

  /**
   * A PNG of [size] painted [argb] all over, alpha included, as the bytes of the file.
   *
   * The image is eight-bit RGBA with no filtering, and its deflate stream holds the rows stored rather
   * than compressed, so a test builds its overlay image with no binary in the tree.
   *
   * A [profile] is embedded as an `iCCP` chunk. A grey profile makes the image eight-bit grey with alpha,
   * painted from the red channel of [argb], so its red, green and blue must match. The chunks [colour] describes are
   * written beside it, all ahead of the image data in the order `cICP`, `iCCP`, `sRGB`, `gAMA`, `cHRM`.
   *
   * @param profile The ICC profile bytes to tag the image with, or null for an untagged image.
   * @param colour The other colour chunks to write, or null for none.
   * @throws IllegalArgumentException When [profile] is shorter than an ICC header, or is grey and [argb] is not.
   */
  public fun pngOf(
    size: Size,
    argb: Int,
    profile: ByteArray? = null,
    colour: PngColour? = null,
  ): ByteArray {
    require(profile == null || profile.size >= ICC_HEADER_BYTES) {
      "An ICC profile needs its $ICC_HEADER_BYTES byte header, but this one is only ${profile?.size} bytes."
    }
    val grey = profile != null && profile.decodeToString(COLOUR_SPACE_OFFSET, COLOUR_SPACE_OFFSET + 4) == "GRAY"
    val channels = listOf(argb shr RED_SHIFT, argb shr GREEN_SHIFT, argb, argb ushr ALPHA_SHIFT).map { it and BYTE }
    require(!grey || (channels[0] == channels[1] && channels[1] == channels[2])) {
      "A grey PNG needs matching red, green and blue."
    }
    val texel = (if (grey) listOf(channels[0], channels[3]) else channels).map { it.toByte() }
    return png(size, if (grey) GREY_ALPHA else RGBA, EIGHT_BITS, texel, colourChunks(profile, colour))
  }

  /**
   * A sixteen-bit RGBA PNG of [size] painted [texel] all over, as the bytes of the file.
   *
   * It is written the way [pngOf] writes an eight-bit one, with the chunks [colour] describes ahead of the image data.
   *
   * @param texel The red, green, blue and alpha samples, each from 0 to 65535.
   * @param colour The colour chunks to write, or null for none.
   * @throws IllegalArgumentException When [texel] is not four samples in range.
   */
  public fun sixteenBitPngOf(
    size: Size,
    texel: List<Int>,
    colour: PngColour? = null,
  ): ByteArray {
    require(texel.size == 4 && texel.all { it in 0..SIXTEEN_BIT_MAX }) {
      "A sixteen-bit texel needs four samples from 0 to $SIXTEEN_BIT_MAX, but got $texel."
    }
    val bytes = texel.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }
    return png(size, RGBA, SIXTEEN_BITS, bytes, colourChunks(profile = null, colour))
  }

  private fun png(
    size: Size,
    colourType: Byte,
    bitDepth: Byte,
    texel: List<Byte>,
    colourChunks: ByteArray,
  ): ByteArray {
    val stride = size.width * texel.size + 1
    val rows = ByteArray(stride * size.height)
    for (index in rows.indices) {
      val column = index % stride - 1
      rows[index] = if (column < 0) 0 else texel[column % texel.size]
    }

    val header = int32(size.width) + int32(size.height) + byteArrayOf(bitDepth, colourType, 0, 0, 0)
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
      chunk("IHDR", header) +
      colourChunks +
      chunk("IDAT", zlibStored(rows)) +
      chunk("IEND", ByteArray(0))
  }

  private fun colourChunks(
    profile: ByteArray?,
    colour: PngColour?,
  ): ByteArray {
    val chunks =
      listOfNotNull(
        colour?.cicp?.let {
          chunk(
            "cICP",
            byteArrayOf(it.primaries.toByte(), it.transfer.toByte(), it.matrix.toByte(), if (it.fullRange) 1 else 0),
          )
        },
        profile?.let { chunk("iCCP", PROFILE_NAME.encodeToByteArray() + byteArrayOf(0, 0) + zlibStored(it)) },
        colour?.srgbIntent?.let { chunk("sRGB", byteArrayOf(it.toByte())) },
        colour?.gamma?.let { chunk("gAMA", int32(it)) },
        colour?.chromaticities?.let {
          chunk("cHRM", it.values.fold(ByteArray(0)) { bytes, value -> bytes + int32(value) })
        },
      )
    return chunks.fold(ByteArray(0)) { bytes, chunk -> bytes + chunk }
  }

  internal fun zlibStored(bytes: ByteArray): ByteArray {
    var zlib = byteArrayOf(0x78, 0x01)
    var from = 0
    do {
      val to = minOf(bytes.size, from + MAX_STORED_BLOCK)
      val length = to - from
      val last: Byte = if (to == bytes.size) 1 else 0
      zlib += byteArrayOf(last) + int16LittleEndian(length) + int16LittleEndian(length.inv() and MAX_STORED_BLOCK)
      zlib += bytes.copyOfRange(from, to)
      from = to
    } while (from < bytes.size)
    return zlib + int32(adler32(bytes))
  }

  internal fun chunk(
    type: String,
    data: ByteArray,
  ): ByteArray {
    val typed = type.encodeToByteArray() + data
    return int32(data.size) + typed + int32(crc32(typed))
  }

  private fun int32(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

  private fun int16LittleEndian(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

  private fun crc32(bytes: ByteArray): Int {
    var crc = -1
    for (byte in bytes) {
      crc = crc xor (byte.toInt() and BYTE)
      repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
    }
    return crc.inv()
  }

  private fun adler32(bytes: ByteArray): Int {
    var a = 1
    var b = 0
    for (byte in bytes) {
      a = (a + (byte.toInt() and BYTE)) % ADLER_MODULUS
      b = (b + a) % ADLER_MODULUS
    }
    return (b shl 16) or a
  }

  private const val RGBA: Byte = 6
  private const val GREY_ALPHA: Byte = 4
  private const val EIGHT_BITS: Byte = 8
  private const val SIXTEEN_BITS: Byte = 16
  private const val SIXTEEN_BIT_MAX = 0xFFFF
  private const val COLOUR_SPACE_OFFSET = 16
  private const val ICC_HEADER_BYTES = 128
  private const val PROFILE_NAME = "ICC profile"
  private const val RED_SHIFT = 16
  private const val GREEN_SHIFT = 8
  private const val ALPHA_SHIFT = 24
  private const val BYTE = 0xFF
  private const val ADLER_MODULUS = 65521
  private const val MAX_STORED_BLOCK = 0xFFFF
}
