package dev.jordond.filmstrip.media3.internal.fill

import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class SolidPaintTest {
  @Test
  fun `a solid paints the fill's own components with its alpha on every grade`() {
    for (transfer in listOf(null) + HdrTransfer.entries) {
      SolidPaint(HALF_GREEN, transfer).paintOf(useHdr = true).toList() shouldBe
        (fillComponents(HALF_GREEN, transfer) + 128f / 255f).toList()
    }
  }

  @Test
  fun `an sdr pipeline paints the authored color whatever the plan's grade`() {
    SolidPaint(HALF_GREEN, HdrTransfer.Pq).paintOf(useHdr = false).toList() shouldBe
      (fillComponents(HALF_GREEN, transfer = null) + 128f / 255f).toList()
  }

  @Test
  fun `two paints of one color on one grade are equal`() {
    SolidPaint(HALF_GREEN, HdrTransfer.Pq) shouldBe SolidPaint(HALF_GREEN, HdrTransfer.Pq)
    SolidPaint(HALF_GREEN, HdrTransfer.Pq) shouldNotBe SolidPaint(HALF_GREEN, HdrTransfer.Hlg)
  }

  private companion object {
    const val HALF_GREEN = 0x80308020.toInt()
  }
}
