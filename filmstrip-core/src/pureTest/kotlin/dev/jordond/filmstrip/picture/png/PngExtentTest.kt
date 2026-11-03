package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.picture.bytes.ByteView
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reads a PNG's size from its `IHDR` alone, sizing the decode [pngSamples] would hold up to [MAX_PNG_EXTENT_BYTES] and
 * refusing a header PNG does not allow.
 */
class PngExtentTest {
  @Test
  fun `a picture's extent carries the decode bytes pngSamples holds for it`() {
    for (interlaced in listOf(false, true)) {
      val file = pngFile(4096, 6000, 6, 8, storedZlibBlocks(ByteArray(1)), interlaced)
      val extent = pngExtent(file).shouldNotBeNull()
      val header = ByteView(file, file.size).readPngHeader(PNG_SIGNATURE.size + 8, MAX_PNG_EXTENT_BYTES)

      extent.width shouldBe 4096
      extent.height shouldBe 6000
      extent.decodeBytes shouldBe header.shouldNotBeNull().decodeBytes
    }
  }

  @Test
  fun `a picture past the sized bytes keeps its size and gives no decode bytes`() {
    val extent = pngExtent(pngFile(16_384, 16_384, 6, 16, storedZlibBlocks(ByteArray(1)))).shouldNotBeNull()

    extent.width shouldBe 16_384
    extent.height shouldBe 16_384
    extent.decodeBytes.shouldBeNull()
  }

  @Test
  fun `a header png does not allow or a file that is not a png gives no extent`() {
    pngExtent(pngFile(16, 16, 2, 4, storedZlibBlocks(ByteArray(1)))).shouldBeNull()
    pngExtent(pngFile(0, 16, 6, 8, storedZlibBlocks(ByteArray(1)))).shouldBeNull()
    pngExtent(PNG_SIGNATURE).shouldBeNull()
    pngExtent(ByteArray(64)).shouldBeNull()
  }
}
