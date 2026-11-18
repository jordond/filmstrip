package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Disposal
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import kotlin.random.Random

/**
 * Writes the operation lists of [AnimatedPictures] as GIF files, whole in Kotlin.
 *
 * A frame is one image descriptor with an LZW stream from a real encoder, so the codes grow their width and the table
 * fills and clears. Only a frame with a transparent texel carries a transparent colour flag.
 */
@InternalFilmstripApi
public object AnimatedGifs {
  /**
   * The bytes of a GIF of [canvas] holding [ops].
   *
   * @param loops The count a NETSCAPE block stores, or null to write no block.
   * @param interlaced Whether every frame stores its rows in the four interlaced passes.
   * @param localTables Whether every frame carries its own colour table, with no global one.
   * @throws IllegalArgumentException when a frame blends `Source`, a tile has alpha other than zero or opaque, a
   * delay is not whole centiseconds, or a tile holds more than 255 colours.
   */
  public fun bytes(
    canvas: Size,
    ops: List<Op>,
    loops: Int? = null,
    interlaced: Boolean = false,
    localTables: Boolean = false,
  ): ByteArray {
    ops.forEach {
      require(it.blend == Blend.Over) { "A GIF has no blend, so $it cannot be written." }
      require(it.tile.isBinaryAlpha) { "A GIF texel is opaque or transparent, so $it cannot be written." }
      require(it.delayMillis % MILLIS_PER_CENTISECOND == 0) {
        "A GIF delay is whole centiseconds, not ${it.delayMillis}."
      }
    }
    val frames = ops.map { op -> FrameImage(op) }
    val global = if (localTables) null else Palette.of(frames.flatMap { it.colors }.distinct(), frames.any { it.hole })
    val out = mutableListOf<ByteArray>()
    out += ascii("GIF89a") + le16(canvas.width) + le16(canvas.height) + screenFlags(global) + byteArrayOf(0, 0)
    global?.let { out += it.table() }
    loops?.let { out += netscape(it) }
    for ((index, op) in ops.withIndex()) {
      val image = frames[index]
      val palette = global ?: Palette.of(image.colors, image.hole)
      out += graphicControl(op, image.hole, palette)
      out += byteArrayOf(0x2C) + le16(op.x) + le16(op.y) + le16(op.tile.width) + le16(op.tile.height) +
        descriptorFlags(localTables, interlaced, palette)
      if (localTables) out += palette.table()
      val indices = image.indices(palette)
      val stored = if (interlaced) interlace(indices, op.tile.width, op.tile.height) else indices
      out += byteArrayOf(palette.minimumCodeSize.toByte()) + subBlocks(lzw(palette.minimumCodeSize, stored))
    }
    out += byteArrayOf(0x3B)
    return concat(out)
  }

  /**
   * Whether each frame of [ops] carries a transparent colour flag, which is whether its tile has a transparent texel.
   */
  public fun transparencyFlags(ops: List<Op>): List<Boolean> = ops.map { it.tile.hasHole }

  /**
   * A GIF of seeded noise over sixteen colors, one global table, every frame covering the canvas.
   *
   * The same seed gives the same file.
   *
   * @property bytes The bytes of the file.
   */
  @InternalFilmstripApi
  public class NoisyGif internal constructor(
    public val bytes: ByteArray,
    private val colors: List<Int>,
    private val indices: List<ByteArray>,
  ) {
    /**
     * The packed ARGB texels frame [frame] holds, row by row.
     */
    public fun texels(frame: Int): IntArray = IntArray(indices[frame].size) { colors[indices[frame][it].toInt()] }
  }

  /**
   * A [NoisyGif] of [frames] frames of [size].
   */
  public fun noisy(
    size: Size,
    frames: Int,
    seed: Long = 1L,
  ): NoisyGif {
    val random = Random(seed)
    val colors = List(NOISY_COLORS) { 0xFF000000.toInt() or (it * 0x101010 + it * 0x30201) }
    val palette = Palette.of(colors, hole = false)
    val out = mutableListOf<ByteArray>()
    out += ascii("GIF89a") + le16(size.width) + le16(size.height) + screenFlags(palette) + byteArrayOf(0, 0)
    out += palette.table()
    out += netscape(0)
    val all =
      List(frames) { frame ->
        val indices =
          ByteArray(size.width * size.height) { at ->
            val x = at % size.width
            val y = at / size.width
            val banded = ((x shr 3) + (y shr 3) + frame) and (NOISY_COLORS - 1)
            (if (random.nextInt(NOISE_ONE_IN) == 0) random.nextInt(NOISY_COLORS) else banded).toByte()
          }
        out += byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04) + le16(FRAME_CENTISECONDS) + byteArrayOf(0, 0)
        out += byteArrayOf(0x2C, 0, 0, 0, 0) + le16(size.width) + le16(size.height) + byteArrayOf(0)
        out += byteArrayOf(palette.minimumCodeSize.toByte()) + subBlocks(lzw(palette.minimumCodeSize, indices))
        indices
      }
    out += byteArrayOf(0x3B)
    return NoisyGif(concat(out), colors, all)
  }

  private class FrameImage(
    val op: Op,
  ) {
    val colors: List<Int> =
      (0 until op.tile.height)
        .flatMap { y -> (0 until op.tile.width).map { x -> op.tile.texel(x, y) } }
        .filter { it ushr 24 != 0 }
        .distinct()
    val hole: Boolean = op.tile.hasHole

    fun indices(palette: Palette): ByteArray =
      ByteArray(op.tile.width * op.tile.height) { at ->
        val texel = op.tile.texel(at % op.tile.width, at / op.tile.width)
        (if (texel ushr 24 == 0) palette.transparentIndex else palette.colors.indexOf(texel)).toByte()
      }
  }

  private class Palette(
    val colors: List<Int>,
    val transparentIndex: Int,
    val bits: Int,
  ) {
    val minimumCodeSize: Int get() = maxOf(2, bits)

    fun table(): ByteArray {
      val bytes = ByteArray(3 shl bits)
      colors.forEachIndexed { index, argb ->
        bytes[index * 3] = (argb shr 16).toByte()
        bytes[index * 3 + 1] = (argb shr 8).toByte()
        bytes[index * 3 + 2] = argb.toByte()
      }
      return bytes
    }

    companion object {
      fun of(
        opaque: List<Int>,
        hole: Boolean,
      ): Palette {
        val entries = opaque.size + if (hole) 1 else 0
        require(entries <= MAX_COLORS) { "A GIF table holds $MAX_COLORS colors, and this needs $entries." }
        var bits = 1
        while ((1 shl bits) < entries) bits++
        return Palette(opaque, if (hole) opaque.size else -1, bits)
      }
    }
  }

  private fun netscape(loops: Int): ByteArray =
    byteArrayOf(0x21, 0xFF.toByte(), 11) + ascii("NETSCAPE2.0") + byteArrayOf(3, 1) + le16(loops) + byteArrayOf(0)

  private fun screenFlags(global: Palette?): ByteArray =
    byteArrayOf((if (global == null) 0x70 else 0xF0 or (global.bits - 1)).toByte())

  private fun descriptorFlags(
    local: Boolean,
    interlaced: Boolean,
    palette: Palette,
  ): ByteArray = byteArrayOf(((if (local) 0x80 or (palette.bits - 1) else 0) or (if (interlaced) 0x40 else 0)).toByte())

  private fun graphicControl(
    op: Op,
    hole: Boolean,
    palette: Palette,
  ): ByteArray {
    val disposal =
      when (op.disposal) {
        Disposal.None -> 1
        Disposal.Background -> 2
        Disposal.Previous -> 3
      }
    val flags = (disposal shl 2) or (if (hole) 1 else 0)
    return byteArrayOf(0x21, 0xF9.toByte(), 4, flags.toByte()) + le16(op.delayMillis / MILLIS_PER_CENTISECOND) +
      byteArrayOf(maxOf(palette.transparentIndex, 0).toByte(), 0)
  }

  private fun interlace(
    rows: ByteArray,
    width: Int,
    height: Int,
  ): ByteArray {
    val order = listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2).flatMap { (first, step) -> (first until height step step) }
    val out = ByteArray(rows.size)
    order.forEachIndexed { stored, row -> rows.copyInto(out, stored * width, row * width, (row + 1) * width) }
    return out
  }

  private fun subBlocks(data: ByteArray): ByteArray {
    val parts = mutableListOf<ByteArray>()
    var from = 0
    while (from < data.size) {
      val to = minOf(data.size, from + MAX_SUB_BLOCK)
      parts += byteArrayOf((to - from).toByte()) + data.copyOfRange(from, to)
      from = to
    }
    parts += byteArrayOf(0)
    return concat(parts)
  }

  /**
   * [indices] as the LZW codes of a GIF image, with the dictionary, the code width and the clear code of a standard
   * encoder.
   */
  private fun lzw(
    minimumCodeSize: Int,
    indices: ByteArray,
  ): ByteArray {
    val clear = 1 shl minimumCodeSize
    val end = clear + 1
    val bits = BitSink()
    var width = minimumCodeSize + 1
    var next = end + 1
    val dictionary = Dictionary()
    bits.write(clear, width)
    var prefix = indices[0].toInt() and BYTE_MASK
    for (at in 1 until indices.size) {
      val value = indices[at].toInt() and BYTE_MASK
      val key = (prefix shl 8) or value
      val known = dictionary.get(key)
      if (known >= 0) {
        prefix = known
        continue
      }
      bits.write(prefix, width)
      if (next < MAX_CODES) {
        dictionary.put(key, next)
        if (next == 1 shl width && width < MAX_WIDTH) width++
        next++
      } else {
        bits.write(clear, width)
        dictionary.clear()
        width = minimumCodeSize + 1
        next = end + 1
      }
      prefix = value
    }
    bits.write(prefix, width)
    if (next < MAX_CODES) {
      if (next == 1 shl width && width < MAX_WIDTH) width++
    }
    bits.write(end, width)
    return bits.bytes()
  }

  // A table from a prefix code and a texel to the code of the string they make, open addressed and half full at most.
  private class Dictionary {
    private val keys = IntArray(SLOTS)
    private val codes = IntArray(SLOTS)

    fun get(key: Int): Int {
      var slot = slotOf(key)
      while (keys[slot] != 0) {
        if (keys[slot] == key + 1) return codes[slot]
        slot = (slot + 1) and (SLOTS - 1)
      }
      return -1
    }

    fun put(
      key: Int,
      code: Int,
    ) {
      var slot = slotOf(key)
      while (keys[slot] != 0) slot = (slot + 1) and (SLOTS - 1)
      keys[slot] = key + 1
      codes[slot] = code
    }

    fun clear() = keys.fill(0)

    private fun slotOf(key: Int): Int = ((key * HASH_MULTIPLIER) ushr (Int.SIZE_BITS - SLOT_BITS))

    private companion object {
      const val SLOT_BITS = 13
      const val SLOTS = 1 shl SLOT_BITS
      const val HASH_MULTIPLIER = -0x61c88647
    }
  }

  private class BitSink {
    private var out = ByteArray(INITIAL_BYTES)
    private var size = 0
    private var buffer = 0
    private var count = 0

    fun write(
      code: Int,
      width: Int,
    ) {
      buffer = buffer or (code shl count)
      count += width
      while (count >= 8) {
        push(buffer.toByte())
        buffer = buffer ushr 8
        count -= 8
      }
    }

    fun bytes(): ByteArray {
      if (count > 0) push(buffer.toByte())
      return out.copyOf(size)
    }

    private fun push(byte: Byte) {
      if (size == out.size) out = out.copyOf(size * 2)
      out[size++] = byte
    }

    private companion object {
      const val INITIAL_BYTES = 1024
    }
  }

  private const val MILLIS_PER_CENTISECOND = 10
  private const val MAX_COLORS = 256
  private const val MAX_SUB_BLOCK = 255
  private const val MAX_CODES = 4096
  private const val MAX_WIDTH = 12
  private const val NOISY_COLORS = 16
  private const val NOISE_ONE_IN = 8
  private const val FRAME_CENTISECONDS = 4
}
