package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.test.NoisyPng
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Pins the exact output of [pngSamples] and [inflateZlib] as digests, so a change to how either is computed shows up
 * as a moved digest and not only as a failed comparison.
 *
 * The noisy pictures carry Huffman coded image data through every row filter. The hand built pictures cover every
 * color type, bit depth, filter and Adam7 layout.
 */
class PngDigestTest {
  @Test
  fun `noisy pictures decode to the pinned samples`() {
    val actual =
      mapOf(
        "seed 0" to samplesDigest(NoisyPng.png(0)),
        "seed 1" to samplesDigest(NoisyPng.png(1)),
        "mixed blocks" to samplesDigest(NoisyPng.png(2, mixedBlocks = true)),
      )

    actual shouldBe NOISY_SAMPLES
  }

  @Test
  fun `noisy streams inflate to the pinned bytes`() {
    val size = (NoisyPng.SIZE * 4 + 1) * NoisyPng.SIZE
    val actual =
      mapOf(
        "seed 0" to bytesDigest(inflate(NoisyPng.zlib(0), size)),
        "mixed blocks" to bytesDigest(inflate(NoisyPng.zlib(3, mixedBlocks = true), size + 5)),
      )

    actual shouldBe NOISY_STREAMS
  }

  @Test
  fun `every color type and depth decodes to the pinned samples`() {
    val random = Random(5)
    val actual = linkedMapOf<String, String>()
    for ((colorType, depth) in PNG_FORMATS) {
      val palette = if (colorType == 3) ByteArray(768) { random.nextInt(256).toByte() } else null
      val digest = Digest()
      for ((width, height) in listOf(3 to 3, 13 to 11, 40 to 2)) {
        val samples = randomSamples(random, width, height, colorType, depth)
        for (interlaced in listOf(false, true)) {
          for (strategy in FILTER_STRATEGIES) {
            val png = encodePng(width, height, colorType, depth, samples, interlaced, strategy, palette)
            digest.add(pngSamples(png)!!)
          }
        }
      }
      actual["$colorType/$depth"] = digest.hex()
    }

    actual shouldBe FORMAT_SAMPLES
  }

  @Test
  fun `stored streams of hand built pictures inflate to the pinned bytes`() {
    val random = Random(9)
    val digest = Digest()
    for ((colorType, depth) in PNG_FORMATS) {
      val samples = randomSamples(random, 21, 17, colorType, depth)
      for (interlaced in listOf(false, true)) {
        val rows = pngImageData(21, 17, colorType, depth, samples, interlaced) { it % 5 }
        digest.add(inflate(storedZlibBlocks(rows), rows.size))
      }
    }

    digest.hex() shouldBe STORED_STREAMS
  }

  private fun inflate(
    stream: ByteArray,
    limit: Int,
  ): ByteArray = inflateZlib(stream, 0, stream.size, limit)!!

  private fun samplesDigest(png: ByteArray): String = Digest().also { it.add(pngSamples(png)!!) }.hex()

  private fun bytesDigest(bytes: ByteArray): String = Digest().also { it.add(bytes) }.hex()

  // Two FNV-1a style hashes with different multipliers, over Int arithmetic so every target computes the same.
  private class Digest {
    private var first = -2128831035
    private var second = 5381

    fun add(bytes: ByteArray) {
      add(bytes.size)
      for (byte in bytes) add(byte.toInt() and 0xFF)
    }

    fun add(samples: PngSamples) {
      add(samples.width)
      add(samples.height)
      add(if (samples.sixteenBit) 1 else 0)
      for (sample in samples.rgba) add(sample.toInt() and 0xFFFF)
    }

    private fun add(value: Int) {
      first = (first xor value) * 16777619
      second = (second * 33) xor value
    }

    fun hex(): String = "${first.toUInt().toString(16)}-${second.toUInt().toString(16)}"
  }

  private companion object {
    val NOISY_SAMPLES: Map<String, String> =
      mapOf(
        "seed 0" to "be340d77-18b289f3",
        "seed 1" to "9025ef5b-52eff9",
        "mixed blocks" to "1679898a-2f9827fe",
      )
    val NOISY_STREAMS: Map<String, String> =
      mapOf(
        "seed 0" to "a0690756-3a3f0b64",
        "mixed blocks" to "5c124623-7dab50df",
      )
    val FORMAT_SAMPLES: Map<String, String> =
      mapOf(
        "0/1" to "95a011a1-10acf805",
        "0/2" to "327e840b-28ed25e5",
        "0/4" to "3ff3bbdf-60a16d25",
        "0/8" to "cd4f9d0d-6df62ac5",
        "0/16" to "47e4c973-ca443a65",
        "2/8" to "21b1993d-278065c5",
        "2/16" to "5223b3bd-766a5745",
        "3/1" to "b1986829-8d158305",
        "3/2" to "9d576047-3f0690a5",
        "3/4" to "8fe34ae5-f77e2145",
        "3/8" to "c0597775-9a4278c5",
        "4/8" to "481a3a83-eaf944e5",
        "4/16" to "67b54f0d-a9508e45",
        "6/8" to "9ff08347-1f968de5",
        "6/16" to "13cd6cf3-d18818a5",
      )
    const val STORED_STREAMS = "f4c6a2c1-b6e0559d"
  }
}
