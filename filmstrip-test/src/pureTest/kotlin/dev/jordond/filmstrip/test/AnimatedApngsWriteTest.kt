package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AnimatedApngsWriteTest {
  private class Case(
    val name: String,
    val canvas: Size,
    val ops: List<Op>,
    val sixteenBit: Boolean = false,
    val defaultIsFrame: Boolean = false,
    val fractions: List<AnimatedPictures.Fraction>? = null,
    val indexed: Boolean = false,
  ) {
    fun bytes(): ByteArray = AnimatedApngs.bytes(canvas, ops, 0, sixteenBit, defaultIsFrame, fractions, indexed)

    fun parts(cicp: ByteArray? = null): List<ByteArray> {
      val parts = mutableListOf<ByteArray>()
      AnimatedApngs.write(canvas, ops, 0, sixteenBit, defaultIsFrame, fractions, indexed, cicp) { parts += it }
      return parts
    }
  }

  private val noisy = Size(33, 17)
  private val noisyOps =
    List(8) { Op(0, 0, AnimatedPictures.Tiles.noisy(noisy.width, noisy.height, it), blend = Blend.Source) }

  private val cases =
    listOf(
      Case("eight bit", AnimatedPictures.CANVAS, AnimatedPictures.SHARED),
      Case("sixteen bit", AnimatedPictures.CANVAS, AnimatedPictures.SHARED, sixteenBit = true),
      Case("indexed", AnimatedPictures.CANVAS, AnimatedPictures.SHARED, indexed = true),
      Case(
        "fractions",
        AnimatedPictures.CANVAS,
        AnimatedPictures.SHARED.take(AnimatedPictures.FRACTIONS.size),
        fractions = AnimatedPictures.FRACTIONS,
      ),
      Case("noisy sixteen bit", noisy, noisyOps, sixteenBit = true, defaultIsFrame = true),
    )

  @Test
  fun theWrittenPartsAreTheFileBytesMakes() {
    for (case in cases) {
      withClue(case.name) { concat(case.parts()).toList() shouldBe case.bytes().toList() }
    }
  }

  @Test
  fun aWrittenColorDescriptionIsTheFileWithCicpMakes() {
    val cicp = byteArrayOf(1, 13, 0, 1)
    for (case in cases) {
      withClue(case.name) {
        concat(case.parts(cicp)).toList() shouldBe AnimatedApngs.withCicp(case.bytes(), cicp).toList()
      }
    }
  }

  @Test
  fun noPartIsLargerThanTheLargestChunkOfTheFile() {
    for (case in cases) {
      val largest = chunkSizes(case.bytes()).max()
      withClue(case.name) { case.parts().maxOf { it.size } shouldBe largest }
    }
  }

  // The size of every chunk of a PNG, its length, type, data and CRC, walked off the length fields after the signature.
  private fun chunkSizes(file: ByteArray): List<Int> {
    val sizes = mutableListOf<Int>()
    var at = SIGNATURE_BYTES
    while (at < file.size) {
      val length =
        (file[at].toInt() and BYTE_MASK shl 24) or (file[at + 1].toInt() and BYTE_MASK shl 16) or
          (file[at + 2].toInt() and BYTE_MASK shl 8) or (file[at + 3].toInt() and BYTE_MASK)
      sizes += length + CHUNK_FRAMING_BYTES
      at += length + CHUNK_FRAMING_BYTES
    }
    return sizes
  }

  private companion object {
    const val SIGNATURE_BYTES = 8
    const val CHUNK_FRAMING_BYTES = 12
  }
}
