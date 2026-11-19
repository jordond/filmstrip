package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration

class HdrPreviewFrameTest {
  @Test
  fun `a signal frame holds the shared codes of each pixel`() {
    val pixels = listOf(MID, BRIGHT, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    val signal = FloatArray(pixels.size * 3).also { out -> pixels.forEachIndexed { i, p -> p.copyInto(out, i * 3) } }

    val frame = HdrPreviewFrame.fromSignal(signal, Size(2, 2), Duration.ZERO, HdrTransfer.Pq)

    frame.codesAt(0, 0).toList() shouldBe tenBitCodesFromSignal(MID).toList()
    frame.codesAt(1, 0).toList() shouldBe tenBitCodesFromSignal(BRIGHT).toList()
    frame.codesAt(1, 1).toList() shouldBe tenBitCodesFromSignal(floatArrayOf(1f, 1f, 1f)).toList()
  }

  @Test
  fun `codes are read row major`() {
    val codes = ShortArray(3 * 2 * 3) { it.toShort() }

    val frame = HdrPreviewFrame(codes, Size(3, 2), Duration.ZERO, HdrTransfer.Hlg)

    frame.codesAt(1, 1).toList() shouldBe listOf(12, 13, 14)
  }

  private companion object {
    val MID = floatArrayOf(0.31f, 0.52f, 0.18f)
    val BRIGHT = floatArrayOf(0.75f, 0.6f, 0.44f)
  }
}
