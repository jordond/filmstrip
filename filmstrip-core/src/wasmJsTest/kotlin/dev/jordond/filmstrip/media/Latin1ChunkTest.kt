package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Latin1ChunkTest {
  // Longer than two chunks and not a multiple of one, so a boundary inside the array is crossed as well as the end.
  private val bytes = ByteArray(8192 * 2 + 37) { (it * 7).toByte() }

  @Test
  fun `bytes written out in chunks read back the same`() {
    val text = StringBuilder()
    bytes.forEachLatin1Chunk { offset, chunk ->
      offset shouldBe text.length
      text.append(chunk)
    }

    val back = latin1BytesOf(bytes.size) { offset, length -> text.substring(offset, offset + length) }

    back shouldBe bytes
  }
}
