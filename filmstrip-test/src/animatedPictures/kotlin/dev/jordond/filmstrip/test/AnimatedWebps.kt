package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Disposal
import dev.jordond.filmstrip.test.AnimatedPictures.Op

/**
 * Writes the operation lists of [AnimatedPictures] as animated WebP files, the `ANMF` container assembled in Kotlin
 * around the frame chunks `cwebp` made.
 */
@InternalFilmstripApi
public object AnimatedWebps {
  /**
   * The bytes of an animated WebP of [canvas] holding [ops].
   *
   * @param loops The count `ANIM` stores, where zero is forever.
   * @throws IllegalArgumentException when a tile has no WebP, a frame is laid on an odd texel, or a frame disposes to
   * `Previous`, which a WebP has no way to say.
   */
  public fun bytes(
    canvas: Size,
    ops: List<Op>,
    loops: Int = 0,
  ): ByteArray {
    val frames =
      ops.map { op ->
        require(op.x % 2 == 0 && op.y % 2 == 0) { "A WebP frame sits on an even texel, so $op cannot be written." }
        require(op.disposal != Disposal.Previous) { "A WebP has no previous disposal, so $op cannot be written." }
        val chunks = requireNotNull(op.tile.webp) { "${op.tile} has no WebP." }.hexBytes()
        riffChunk("ANMF", anmf(op) + chunks)
      }
    val flags = 0x02 or (if (ops.any { it.tile.hasHole || it.tile.webp?.startsWith(ALPHA_TAG) == true }) 0x10 else 0)
    val body =
      ascii("WEBP") +
        riffChunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + le24(canvas.width - 1) + le24(canvas.height - 1)) +
        riffChunk("ANIM", byteArrayOf(0, 0, 0, 0) + le16(loops)) +
        frames.fold(ByteArray(0)) { all, frame -> all + frame }
    return ascii("RIFF") + le32(body.size) + body
  }

  /**
   * The bytes of the still WebP a reader must make of the frame chunks [chunks] of a [width] by [height] picture.
   *
   * A frame with an `ALPH` chunk ahead of its `VP8 ` chunk needs a `VP8X` header carrying the alpha flag and the size.
   * Any other frame is its chunks behind a RIFF header.
   */
  public fun still(
    chunks: ByteArray,
    width: Int,
    height: Int,
  ): ByteArray {
    val alpha = chunks.decodeToString(0, 4) == "ALPH"
    val header =
      if (alpha) riffChunk("VP8X", byteArrayOf(0x10, 0, 0, 0) + le24(width - 1) + le24(height - 1)) else ByteArray(0)
    val body = ascii("WEBP") + header + chunks
    return ascii("RIFF") + le32(body.size) + body
  }

  /**
   * The frame chunks of [op]'s tile.
   */
  public fun chunksOf(op: Op): ByteArray = requireNotNull(op.tile.webp) { "${op.tile} has no WebP." }.hexBytes()

  private fun anmf(op: Op): ByteArray {
    val flags = (if (op.blend == Blend.Source) 0x02 else 0) or (if (op.disposal == Disposal.Background) 0x01 else 0)
    return le24(op.x / 2) + le24(op.y / 2) + le24(op.tile.width - 1) + le24(op.tile.height - 1) +
      le24(op.delayMillis) + byteArrayOf(flags.toByte())
  }

  private fun riffChunk(
    type: String,
    data: ByteArray,
  ): ByteArray = ascii(type) + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

  private const val ALPHA_TAG = "414c5048"
}
