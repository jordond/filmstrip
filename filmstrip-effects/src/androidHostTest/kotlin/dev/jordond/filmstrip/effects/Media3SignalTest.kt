package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Media3SignalTest {
  @Test
  fun aColourInMedia3sWorkingSpaceReadsAsItsSignal() {
    HdrTransfer.entries.forEach { transfer ->
      val expected = transfer.signalFromNits(MID_RANGE_NITS)
      val read = media3Signal(media3WorkingLight(MID_RANGE_NITS, transfer), transfer)
      read.indices.forEach { read[it] shouldBe (expected[it] plusOrMinus TOLERANCE) }
    }
  }

  @Test
  fun aPqFrameAboveMedia3sPeakKeepsItsLight() {
    val nits = floatArrayOf(4_000f, 1_500f, 20f)
    val read = media3Signal(media3WorkingLight(nits, HdrTransfer.Pq), HdrTransfer.Pq)
    val expected = HdrTransfer.Pq.signalFromNits(nits)
    read.indices.forEach { read[it] shouldBe (expected[it] plusOrMinus TOLERANCE) }
  }

  private companion object {
    val MID_RANGE_NITS = floatArrayOf(180f, 64f, 7f)
    const val TOLERANCE = 1e-4f
  }
}
