package dev.jordond.filmstrip.test

import kotlin.random.Random

/**
 * Seeded RGBA8 PNGs of noise over a gradient, written with a deflate encoder of their own so the image data is real
 * Huffman coded data rather than stored blocks.
 *
 * Every picture is [SIZE] pixels square and runs through all five row filters in turn. The same seed always gives the
 * same bytes on every target, and [frames] holds [FRAMES] pictures with a seed each.
 */
internal object NoisyPng {
  /**
   * The width and height of every picture, in pixels.
   */
  const val SIZE: Int = 480

  /**
   * How many pictures [frames] holds.
   */
  const val FRAMES: Int = 60

  /**
   * The pictures of seeds 0 until [FRAMES], built on first use.
   */
  val frames: List<ByteArray> by lazy { List(FRAMES) { png(it) } }

  /**
   * The PNG of [seed] as the bytes of the file.
   *
   * With [mixedBlocks] set the image data alternates dynamic, fixed and stored deflate blocks, otherwise it is dynamic
   * blocks only.
   */
  fun png(
    seed: Int = 0,
    mixedBlocks: Boolean = false,
  ): ByteArray {
    val header = be32(SIZE) + be32(SIZE) + byteArrayOf(8, 6, 0, 0, 0)
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
      pngChunk("IHDR", header) +
      pngChunk("IDAT", zlib(seed, mixedBlocks)) +
      pngChunk("IEND", ByteArray(0))
  }

  /**
   * The zlib stream of [seed]'s image data, the bytes every `IDAT` chunk of [png] carries.
   */
  fun zlib(
    seed: Int,
    mixedBlocks: Boolean = false,
  ): ByteArray {
    val rows = filteredRows(seed)
    return byteArrayOf(0x78, 0xDA.toByte()) + Deflate(rows, mixedBlocks).encode() + be32(adler32(rows))
  }

  private fun filteredRows(seed: Int): ByteArray {
    val random = Random(seed)
    val stride = SIZE * PIXEL
    val rows = ByteArray((stride + 1) * SIZE)
    var above = ByteArray(stride)
    for (y in 0 until SIZE) {
      val line = ByteArray(stride)
      for (x in 0 until SIZE) {
        val base = (x + y) * 255 / (2 * SIZE)
        for (channel in 0 until 3) line[x * PIXEL + channel] = (base + channel * 40 + random.nextInt(NOISE)).toByte()
        line[x * PIXEL + 3] = (255 - random.nextInt(4)).toByte()
      }
      val filter = y % 5
      val at = y * (stride + 1)
      rows[at] = filter.toByte()
      for (i in 0 until stride) {
        val a = if (i >= PIXEL) line[i - PIXEL].toInt() and 0xFF else 0
        val b = above[i].toInt() and 0xFF
        val c = if (i >= PIXEL) above[i - PIXEL].toInt() and 0xFF else 0
        val predicted =
          when (filter) {
            1 -> a
            2 -> b
            3 -> (a + b) ushr 1
            4 -> paeth(a, b, c)
            else -> 0
          }
        rows[at + 1 + i] = (line[i] - predicted).toByte()
      }
      above = line
    }
    return rows
  }

  private fun paeth(
    a: Int,
    b: Int,
    c: Int,
  ): Int {
    val estimate = a + b - c
    val fromA = if (estimate > a) estimate - a else a - estimate
    val fromB = if (estimate > b) estimate - b else b - estimate
    val fromC = if (estimate > c) estimate - c else c - estimate
    return if (fromA <= fromB && fromA <= fromC) {
      a
    } else if (fromB <= fromC) {
      b
    } else {
      c
    }
  }

  // Greedy LZ77 over a 32 KB window, then blocks of BLOCK_TOKENS tokens. A token below 256 is a literal and anything
  // above it is a match, length shl 16 or distance.
  private class Deflate(
    private val data: ByteArray,
    private val mixed: Boolean,
  ) {
    private val bits = BitSink()

    fun encode(): ByteArray {
      val tokens = tokenize()
      var from = 0
      var byteFrom = 0
      var index = 0
      while (from < tokens.size) {
        val to = minOf(tokens.size, from + BLOCK_TOKENS)
        var byteTo = byteFrom
        for (at in from until to) byteTo += if (tokens[at] < LITERALS) 1 else tokens[at] ushr 16
        val last = to == tokens.size
        when {
          mixed && index % 7 == 3 -> huffmanBlock(tokens, from, to, last, fixed = true)
          mixed && index % 7 == 5 -> storedBlocks(byteFrom, byteTo, last)
          else -> huffmanBlock(tokens, from, to, last, fixed = false)
        }
        from = to
        byteFrom = byteTo
        index++
      }
      return bits.finish()
    }

    private fun tokenize(): IntArray {
      val head = IntArray(HASH_SIZE) { -1 }
      val previous = IntArray(data.size)
      var tokens = IntArray(data.size / 2 + 16)
      var count = 0
      var at = 0

      fun hashAt(position: Int): Int =
        (
          ((data[position].toInt() and 0xFF) shl 10) xor
            ((data[position + 1].toInt() and 0xFF) shl 5) xor
            (data[position + 2].toInt() and 0xFF)
        ) and (HASH_SIZE - 1)

      fun insert(position: Int) {
        if (position + 2 >= data.size) return
        val hash = hashAt(position)
        previous[position] = head[hash]
        head[hash] = position
      }

      while (at < data.size) {
        var bestLength = 0
        var bestDistance = 0
        if (at + 2 < data.size) {
          var candidate = head[hashAt(at)]
          var steps = 0
          val limit = minOf(MAX_MATCH, data.size - at)
          while (candidate >= 0 && at - candidate <= WINDOW && steps < CHAIN) {
            var length = 0
            while (length < limit && data[candidate + length] == data[at + length]) length++
            if (length > bestLength) {
              bestLength = length
              bestDistance = at - candidate
              if (length == limit) break
            }
            candidate = previous[candidate]
            steps++
          }
        }
        if (count == tokens.size) tokens = tokens.copyOf(count * 2)
        if (bestLength >= MIN_MATCH) {
          tokens[count++] = (bestLength shl 16) or bestDistance
          for (position in at until at + bestLength) insert(position)
          at += bestLength
        } else {
          tokens[count++] = data[at].toInt() and 0xFF
          insert(at)
          at++
        }
      }
      return tokens.copyOf(count)
    }

    private fun storedBlocks(
      from: Int,
      to: Int,
      last: Boolean,
    ) {
      var at = from
      do {
        val end = minOf(to, at + MAX_STORED)
        val final = last && end == to
        bits.write(if (final) 1 else 0, 1)
        bits.write(0, 2)
        bits.alignToByte()
        bits.write(end - at, 16)
        bits.write((end - at).inv() and 0xFFFF, 16)
        for (position in at until end) bits.write(data[position].toInt() and 0xFF, 8)
        at = end
      } while (at < to)
    }

    private fun huffmanBlock(
      tokens: IntArray,
      from: Int,
      to: Int,
      last: Boolean,
      fixed: Boolean,
    ) {
      val literalLengths: IntArray
      val distanceLengths: IntArray
      if (fixed) {
        literalLengths =
          IntArray(288) {
            if (it < 144 || it >= 280) {
              8
            } else if (it < 256) {
              9
            } else {
              7
            }
          }
        distanceLengths = IntArray(30) { 5 }
      } else {
        val literalFrequency = IntArray(286)
        val distanceFrequency = IntArray(30)
        literalFrequency[END_OF_BLOCK] = 1
        for (at in from until to) {
          val token = tokens[at]
          if (token < LITERALS) {
            literalFrequency[token]++
          } else {
            literalFrequency[LENGTH_SYMBOL[token ushr 16]]++
            distanceFrequency[distanceSymbol(token and 0xFFFF)]++
          }
        }
        literalLengths = codeLengths(literalFrequency, 15)
        distanceLengths = codeLengths(distanceFrequency, 15).also { if (it.all { length -> length == 0 }) it[0] = 1 }
      }
      bits.write(if (last) 1 else 0, 1)
      bits.write(if (fixed) 1 else 2, 2)
      if (!fixed) writeTables(literalLengths, distanceLengths)
      val literalCodes = canonical(literalLengths)
      val distanceCodes = canonical(distanceLengths)
      for (at in from until to) {
        val token = tokens[at]
        if (token < LITERALS) {
          bits.write(literalCodes[token], literalLengths[token])
        } else {
          val length = token ushr 16
          val distance = token and 0xFFFF
          val lengthSymbol = LENGTH_SYMBOL[length]
          bits.write(literalCodes[lengthSymbol], literalLengths[lengthSymbol])
          bits.write(length - LENGTH_BASE[lengthSymbol - LITERALS - 1], LENGTH_EXTRA[lengthSymbol - LITERALS - 1])
          val distanceSymbol = distanceSymbol(distance)
          bits.write(distanceCodes[distanceSymbol], distanceLengths[distanceSymbol])
          bits.write(distance - DISTANCE_BASE[distanceSymbol], DISTANCE_EXTRA[distanceSymbol])
        }
      }
      bits.write(literalCodes[END_OF_BLOCK], literalLengths[END_OF_BLOCK])
    }

    // Writes every code length on its own, with no run length symbols, through a code length code of its own.
    private fun writeTables(
      literalLengths: IntArray,
      distanceLengths: IntArray,
    ) {
      val all = literalLengths + distanceLengths
      val frequency = IntArray(19)
      for (length in all) frequency[length]++
      val lengthLengths = codeLengths(frequency, 7)
      val lengthCodes = canonical(lengthLengths)
      var count = 19
      while (count > 4 && lengthLengths[CODE_LENGTH_ORDER[count - 1]] == 0) count--
      bits.write(literalLengths.size - 257, 5)
      bits.write(distanceLengths.size - 1, 5)
      bits.write(count - 4, 4)
      for (index in 0 until count) bits.write(lengthLengths[CODE_LENGTH_ORDER[index]], 3)
      for (length in all) bits.write(lengthCodes[length], lengthLengths[length])
    }
  }

  private class BitSink {
    private var bytes = ByteArray(1 shl 16)
    private var size = 0
    private var buffer = 0
    private var count = 0

    fun write(
      value: Int,
      width: Int,
    ) {
      buffer = buffer or (value shl count)
      count += width
      while (count >= 8) {
        if (size == bytes.size) bytes = bytes.copyOf(size * 2)
        bytes[size++] = buffer.toByte()
        buffer = buffer ushr 8
        count -= 8
      }
    }

    fun alignToByte() {
      if (count > 0) write(0, 8 - count)
    }

    fun finish(): ByteArray {
      alignToByte()
      return bytes.copyOf(size)
    }
  }

  private fun distanceSymbol(distance: Int): Int {
    var symbol = DISTANCE_BASE.size - 1
    while (DISTANCE_BASE[symbol] > distance) symbol--
    return symbol
  }

  // Code lengths from frequencies by repeated merging of the two lightest nodes. A code that runs past limit is
  // flattened by halving the frequencies and starting over.
  private fun codeLengths(
    frequency: IntArray,
    limit: Int,
  ): IntArray {
    var weights = frequency.copyOf()
    while (true) {
      val lengths = depths(weights)
      if (lengths.all { it <= limit }) return lengths
      weights = IntArray(weights.size) { if (weights[it] == 0) 0 else (weights[it] + 1) / 2 }
    }
  }

  private fun depths(frequency: IntArray): IntArray {
    val lengths = IntArray(frequency.size)
    val leaves = frequency.indices.filter { frequency[it] > 0 }.sortedBy { frequency[it] }
    if (leaves.size < 2) {
      leaves.forEach { lengths[it] = 1 }
      return lengths
    }
    val total = leaves.size * 2 - 1
    val weight = LongArray(total)
    val parent = IntArray(total) { -1 }
    for ((node, symbol) in leaves.withIndex()) weight[node] = frequency[symbol].toLong()
    var leaf = 0
    var inner = leaves.size
    var next = leaves.size

    fun lightest(): Int =
      if (leaf < leaves.size && (inner >= next || weight[leaf] <= weight[inner])) leaf++ else inner++

    while (next < total) {
      val first = lightest()
      val second = lightest()
      weight[next] = weight[first] + weight[second]
      parent[first] = next
      parent[second] = next
      next++
    }
    for ((node, symbol) in leaves.withIndex()) {
      var depth = 0
      var at = node
      while (parent[at] >= 0) {
        at = parent[at]
        depth++
      }
      lengths[symbol] = depth
    }
    return lengths
  }

  // The codes of a canonical Huffman code, bit reversed since deflate writes them most significant bit first into a
  // least significant bit first stream.
  private fun canonical(lengths: IntArray): IntArray {
    val counts = IntArray(16)
    for (length in lengths) if (length > 0) counts[length]++
    val firsts = IntArray(16)
    var code = 0
    for (length in 1..15) {
      code = (code + counts[length - 1]) shl 1
      firsts[length] = code
    }
    return IntArray(lengths.size) { symbol ->
      val length = lengths[symbol]
      if (length == 0) {
        0
      } else {
        var value = firsts[length]++
        var reversed = 0
        repeat(length) {
          reversed = (reversed shl 1) or (value and 1)
          value = value ushr 1
        }
        reversed
      }
    }
  }

  private const val PIXEL = 4
  private const val NOISE = 24
  private const val LITERALS = 256
  private const val END_OF_BLOCK = 256
  private const val BLOCK_TOKENS = 16_384
  private const val MIN_MATCH = 3
  private const val MAX_MATCH = 258
  private const val WINDOW = 32_768
  private const val HASH_SIZE = 1 shl 15
  private const val CHAIN = 6
  private const val MAX_STORED = 65_535
  private const val ADLER_MODULUS = 65_521
  private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
  private val LENGTH_EXTRA = IntArray(29) { if (it < 8 || it == 28) 0 else (it - 4) / 4 }
  private val LENGTH_BASE =
    IntArray(29).also { bases ->
      var base = 3
      for (index in bases.indices) {
        bases[index] = base
        base += 1 shl LENGTH_EXTRA[index]
      }
      bases[28] = 258
    }

  // The literal/length symbol of every match length, 257 to 285.
  private val LENGTH_SYMBOL =
    IntArray(MAX_MATCH + 1) { length ->
      if (length < MIN_MATCH) {
        0
      } else {
        var index = LENGTH_BASE.size - 1
        while (LENGTH_BASE[index] > length) index--
        LITERALS + 1 + index
      }
    }
  private val DISTANCE_EXTRA = IntArray(30) { maxOf(0, (it - 2) / 2) }
  private val DISTANCE_BASE =
    IntArray(30).also { bases ->
      var base = 1
      for (index in bases.indices) {
        bases[index] = base
        base += 1 shl DISTANCE_EXTRA[index]
      }
    }
}
