package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture

/**
 * A PNG's size as its `IHDR` gives it, and what decoding its samples holds.
 *
 * @property width The picture's width in pixels.
 * @property height The picture's height in pixels.
 * @property decodeBytes What [pngSamples] holds for the picture's inflated rows and decoded samples together, or null
 * when that passes [MAX_PNG_EXTENT_BYTES].
 */
@InternalFilmstripApi
public class PngExtent internal constructor(
  public val width: Int,
  public val height: Int,
  public val decodeBytes: Long?,
)

/**
 * Reads the size of the PNG in [bytes] from its `IHDR` alone, or returns null when it is not a PNG, its `IHDR` is
 * missing, short or fails its CRC, or PNG does not allow its fields. Nothing past the `IHDR` is read. Never throws.
 */
@InternalFilmstripApi
public fun pngExtent(bytes: ByteArray): PngExtent? {
  val png = ByteView(bytes, bytes.size)
  if (!png.matches(0, PNG_SIGNATURE)) return null
  return try {
    val chunk = png.pngChunk(PNG_SIGNATURE.size)
    if (chunk.type != PNG_IHDR || chunk.size != PNG_IHDR_SIZE || !png.crcHolds(chunk)) return null
    if (!png.allowsPngHeader(chunk.start)) return null
    val header = png.readPngHeader(chunk.start, MAX_PNG_EXTENT_BYTES)
    PngExtent(png.u32(chunk.start).toInt(), png.u32(chunk.start + 4).toInt(), header?.decodeBytes)
  } catch (_: MalformedPicture) {
    null
  }
}

/**
 * The most decode bytes [pngExtent] sizes, 1 GiB. Under it a row of the largest picture still fits in an [Int].
 */
internal const val MAX_PNG_EXTENT_BYTES: Long = 1L shl 30
