package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Disposal
import dev.jordond.filmstrip.test.AnimatedPictures.Fraction
import dev.jordond.filmstrip.test.AnimatedPictures.Op

/**
 * Writes the operation lists of [AnimatedPictures] as APNG files, whole in Kotlin, with stored deflate blocks.
 */
@InternalFilmstripApi
public object AnimatedApngs {
  /**
   * The bytes of an APNG of [canvas] holding [ops].
   *
   * @param plays The count `acTL` stores, where zero is forever.
   * @param sixteenBit Whether the file stores 16-bit samples, each 8-bit texel written as the sample 257 times it.
   * @param defaultIsFrame Whether the default image is the first frame. When it is not, the default image is a flat
   * magenta that no frame holds, written ahead of every frame.
   * @param fractions The delays to store in place of each frame's own, as a numerator over a denominator.
   * @param indexed Whether the file stores 8-bit palette indices behind a `PLTE` and a `tRNS` chunk, one entry for
   * every distinct texel of [ops], instead of RGBA.
   * @throws IllegalArgumentException when [defaultIsFrame] and the first frame is not the whole canvas, or [fractions]
   * is not one a frame.
   */
  public fun bytes(
    canvas: Size,
    ops: List<Op>,
    plays: Int = 0,
    sixteenBit: Boolean = false,
    defaultIsFrame: Boolean = false,
    fractions: List<Fraction>? = null,
    indexed: Boolean = false,
  ): ByteArray {
    require(!indexed || !sixteenBit) { "A palette file stores 8-bit indices." }
    require(!defaultIsFrame || ops[0].covers(canvas)) { "A default image that is a frame is the whole canvas." }
    require(fractions == null || fractions.size == ops.size) { "Every frame needs its own fraction." }
    var sequence = 0
    val out = mutableListOf<ByteArray>()
    out += PNG_SIGNATURE
    val depth: Byte = if (sixteenBit) 16 else 8
    val palette = if (indexed) ops.flatMap { op -> texels(op) }.plus(MAGENTA).distinct() else emptyList()
    val colorType: Byte = if (indexed) 3 else 6
    out += pngChunk("IHDR", be32(canvas.width) + be32(canvas.height) + byteArrayOf(depth, colorType, 0, 0, 0))
    if (indexed) {
      out +=
        pngChunk(
          "PLTE",
          palette.flatMap { listOf((it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte()) }.toByteArray(),
        )
      out += pngChunk("tRNS", palette.map { (it ushr 24).toByte() }.toByteArray())
    }
    out += pngChunk("acTL", be32(ops.size) + be32(plays))
    if (!defaultIsFrame) {
      val magenta = rows(canvas.width, canvas.height, sixteenBit, palette) { _, _ -> MAGENTA }
      out += pngChunk("IDAT", zlibStored(magenta))
    }
    for ((index, op) in ops.withIndex()) {
      val delay =
        fractions?.get(index)?.let { be16(it.numerator) + be16(it.denominator) }
          ?: (be16(op.delayMillis) + be16(AnimatedPictures.MILLIS_PER_SECOND))
      val control =
        be32(sequence++) + be32(op.tile.width) + be32(op.tile.height) + be32(op.x) + be32(op.y) + delay +
          byteArrayOf(disposeCode(op.disposal), if (op.blend == Blend.Source) 0 else 1)
      out += pngChunk("fcTL", control)
      val data = zlibStored(rows(op.tile.width, op.tile.height, sixteenBit, palette) { x, y -> op.tile.texel(x, y) })
      out += if (index == 0 && defaultIsFrame) pngChunk("IDAT", data) else pngChunk("fdAT", be32(sequence++) + data)
    }
    out += pngChunk("IEND", ByteArray(0))
    return concat(out)
  }

  /**
   * [file] with a `cICP` chunk of [cicp] written after its header, so its color is described.
   *
   * @param cicp The four bytes of the chunk, primaries, transfer, matrix and range.
   */
  public fun withCicp(
    file: ByteArray,
    cicp: ByteArray,
  ): ByteArray {
    val header = PNG_SIGNATURE.size + HEADER_CHUNK
    return file.copyOfRange(0, header) + pngChunk("cICP", cicp) + file.copyOfRange(header, file.size)
  }

  /**
   * An APNG of [frames] frames of [NoisyPng], frame `n` made from seed `n`, each covering the canvas and shown
   * for 40 ms.
   *
   * The first frame is the default image and the file plays forever.
   */
  public fun noisy(frames: Int = NoisyPng.FRAMES): ByteArray {
    var sequence = 0
    val out = mutableListOf<ByteArray>()
    out += PNG_SIGNATURE
    out += pngChunk("IHDR", be32(NoisyPng.SIZE) + be32(NoisyPng.SIZE) + byteArrayOf(8, 6, 0, 0, 0))
    out += pngChunk("acTL", be32(frames) + be32(0))
    repeat(frames) { seed ->
      val delay = be16(AnimatedPictures.FRAME_MILLIS) + be16(AnimatedPictures.MILLIS_PER_SECOND)
      out +=
        pngChunk(
          "fcTL",
          be32(sequence++) + be32(NoisyPng.SIZE) + be32(NoisyPng.SIZE) + be32(0) + be32(0) + delay +
            byteArrayOf(0, 0),
        )
      val data = NoisyPng.zlib(seed)
      out += if (seed == 0) pngChunk("IDAT", data) else pngChunk("fdAT", be32(sequence++) + data)
    }
    out += pngChunk("IEND", ByteArray(0))
    return concat(out)
  }

  private fun texels(op: Op): List<Int> =
    (0 until op.tile.height).flatMap { y -> (0 until op.tile.width).map { x -> op.tile.texel(x, y) } }

  private fun Op.covers(canvas: Size): Boolean =
    x == 0 && y == 0 && tile.width == canvas.width && tile.height == canvas.height

  private fun disposeCode(disposal: Disposal): Byte =
    when (disposal) {
      Disposal.None -> 0
      Disposal.Background -> 1
      Disposal.Previous -> 2
    }

  private fun rows(
    width: Int,
    height: Int,
    sixteenBit: Boolean,
    palette: List<Int>,
    texel: (x: Int, y: Int) -> Int,
  ): ByteArray {
    val out = mutableListOf<Byte>()
    for (y in 0 until height) {
      out += 0
      for (x in 0 until width) {
        val argb = texel(x, y)
        if (palette.isNotEmpty()) {
          out += palette.indexOf(argb).toByte()
          continue
        }
        for (shift in intArrayOf(16, 8, 0, 24)) {
          val sample = ((argb ushr shift) and BYTE_MASK).toByte()
          out += sample
          if (sixteenBit) out += sample
        }
      }
    }
    return out.toByteArray()
  }

  private fun be16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private const val HEADER_CHUNK = 25
  private const val MAGENTA = 0xFFFF00FF.toInt()
}
