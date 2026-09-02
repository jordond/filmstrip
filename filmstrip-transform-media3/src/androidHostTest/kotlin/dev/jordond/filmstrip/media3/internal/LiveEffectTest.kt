package dev.jordond.filmstrip.media3.internal

import androidx.media3.effect.AlphaScale
import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.HdrColorMatrixEffect
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Which position in a lowered chain each effect gets, and what may be swapped into it.
 *
 * Nothing here draws. A slot is judged on what it accepts and on what the next draw would read,
 * which is the seam a preview's parameter change travels through.
 */
class LiveEffectTest {
  @Test
  fun `a colour matrix on a kept grade gets a slot that swaps without a rebuild`() {
    val slot = liveSlotFor(hdrMatrix(CONTRAST, HdrTransfer.Pq))

    slot.shouldBeInstanceOf<LiveHdrColorMatrix>()
    slot.effect shouldBeSameInstanceAs slot
  }

  @Test
  fun `the slot takes another matrix on the same transfer and nothing else`() {
    val slot = liveSlotFor(hdrMatrix(CONTRAST, HdrTransfer.Pq))

    slot.accepts(hdrMatrix(DESATURATE, HdrTransfer.Pq)) shouldBe true
    slot.accepts(hdrMatrix(DESATURATE, HdrTransfer.Hlg)) shouldBe false
    slot.accepts(AlphaScale(0.5f)) shouldBe false
    slot.accepts(sdrMatrix()) shouldBe false
  }

  @Test
  fun `installing a matrix is what the next draw reads`() {
    val slot = liveSlotFor(hdrMatrix(CONTRAST, HdrTransfer.Pq)).shouldBeInstanceOf<LiveHdrColorMatrix>()
    val next = hdrMatrix(DESATURATE, HdrTransfer.Pq)

    slot.install(next)

    slot.current shouldBeSameInstanceAs next
  }

  // A live position must survive the chain being built, and media3 drops a no-op effect while it
  // builds one.
  @Test
  fun `the slot is never a no-op whatever the matrix says`() {
    val slot = liveSlotFor(hdrMatrix(ColorMatrix.Identity, HdrTransfer.Pq)).shouldBeInstanceOf<LiveHdrColorMatrix>()

    slot.isNoOp(1280, 720) shouldBe false
  }

  @Test
  fun `an sdr colour matrix keeps media3's own slot`() {
    liveSlotFor(sdrMatrix()).shouldBeInstanceOf<LiveRgbMatrix>()
  }

  private fun hdrMatrix(
    matrix: ColorMatrix,
    transfer: HdrTransfer,
  ) = HdrColorMatrixEffect(matrix, transfer)

  private fun sdrMatrix(): RgbMatrix =
    object : RgbMatrix {
      override fun getMatrix(
        presentationTimeUs: Long,
        useHdr: Boolean,
      ): FloatArray = FloatArray(16)
    }

  private companion object {
    val CONTRAST = ColorMatrix(rr = 2f, rBias = -0.5f, gg = 2f, gBias = -0.5f, bb = 2f, bBias = -0.5f)
    val DESATURATE = ColorMatrix(rr = 0.5f, rg = 0.5f, gr = 0.5f, gg = 0.5f, bb = 1f)
  }
}
