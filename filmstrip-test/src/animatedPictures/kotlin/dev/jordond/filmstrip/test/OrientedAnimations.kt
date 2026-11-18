package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedPictures.Op

/**
 * The frames of [AnimatedPictures.SHARED] as an APNG or an animated WebP that carries an Exif orientation, so every
 * frame is stored one way and shown another.
 *
 * The canvas is wider than it is tall, so a quarter turn swaps its sides. A test turns the canvases
 * [AnimatedPictures.canvases] paints with the orientation function core shares and compares what a backend draws to it.
 * A GIF has no place for the tag, so only the two containers that have one are written.
 */
@InternalFilmstripApi
public object OrientedAnimations {
  /**
   * The stored size of every frame, [AnimatedPictures.CANVAS].
   */
  public val CANVAS: Size = AnimatedPictures.CANVAS

  /**
   * The operations every file holds, [AnimatedPictures.SHARED].
   */
  public val OPS: List<Op> = AnimatedPictures.SHARED

  /**
   * An APNG of [OPS] whose `eXIf` chunk tags [exif].
   *
   * @param exif The value of tag 274, written as given.
   */
  public fun apng(exif: Int): ByteArray {
    val file = AnimatedApngs.bytes(CANVAS, OPS)
    var at = PNG_SIGNATURE_SIZE
    while (file.decodeToString(at + CHUNK_TYPE_AT, at + CHUNK_TYPE_AT + CHUNK_TYPE_SIZE) !=
      "IDAT"
    ) {
      at += CHUNK_OVERHEAD + be32At(file, at)
    }
    return file.copyOfRange(0, at) + pngChunk("eXIf", exifTiff(exif)) + file.copyOfRange(at, file.size)
  }

  /**
   * An animated WebP of [OPS] whose `EXIF` chunk tags [exif].
   *
   * @param exif The value of tag 274, written as given.
   */
  public fun webp(exif: Int): ByteArray {
    val file = AnimatedWebps.bytes(CANVAS, OPS)
    file[VP8X_FLAGS_AT] = (file[VP8X_FLAGS_AT].toInt() or EXIF_FLAG).toByte()
    val tiff = exifTiff(exif)
    val chunk = ascii("EXIF") + le32(tiff.size) + tiff + ByteArray(tiff.size and 1)
    val whole = file + chunk
    le32(whole.size - RIFF_HEADER_SIZE).copyInto(whole, RIFF_SIZE_AT)
    return whole
  }

  // A big-endian TIFF block whose only entry is the orientation.
  private fun exifTiff(exif: Int): ByteArray =
    ascii("MM") +
      byteArrayOf(0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, (exif ushr 8).toByte(), exif.toByte(), 0, 0) +
      ByteArray(NEXT_DIRECTORY_SIZE)

  private fun be32At(
    bytes: ByteArray,
    at: Int,
  ): Int = (0 until 4).fold(0) { value, index -> (value shl 8) or (bytes[at + index].toInt() and BYTE_MASK) }

  private const val PNG_SIGNATURE_SIZE = 8
  private const val CHUNK_TYPE_AT = 4
  private const val CHUNK_TYPE_SIZE = 4
  private const val CHUNK_OVERHEAD = 12
  private const val NEXT_DIRECTORY_SIZE = 4
  private const val RIFF_SIZE_AT = 4
  private const val RIFF_HEADER_SIZE = 8
  private const val VP8X_FLAGS_AT = 20
  private const val EXIF_FLAG = 0x08
}
