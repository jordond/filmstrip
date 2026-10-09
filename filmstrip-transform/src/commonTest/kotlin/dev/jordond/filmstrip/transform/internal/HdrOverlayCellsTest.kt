package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.HdrOverlayCells.BLACK
import dev.jordond.filmstrip.test.HdrOverlayCells.Cell
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdrOverlayCellsTest {
  // Every blend cell's misses are read off the shared wrong blends here, and the smallest of them over both
  // transfers is the one the shared figure reports. Green over grey at a quarter on PQ, blended on the sRGB
  // codes, is the closest a wrong blend gets. A cell that cannot see a blend misses by less and moves it.
  @Test
  fun `the smallest wrong blend miss is green over grey at a quarter blended on PQ in sRGB`() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        HdrOverlayCells.BLENDS.flatMap { cell ->
          val rule = cell.codes(transfer)
          listOf(rule.missFrom(cell.signalBlendCodes(transfer)), rule.missFrom(cell.srgbBlendCodes(transfer)))
        }
      }
    val closest = Cell(AuthoredColours.MID_GREEN, AuthoredColours.MID_GREY, QUARTER)

    assertContains(HdrOverlayCells.BLENDS, closest)
    assertEquals(misses.min(), HdrOverlayCells.SMALLEST_WRONG_BLEND_MISS)
    assertEquals(
      closest.codes(HdrTransfer.Pq).missFrom(closest.srgbBlendCodes(HdrTransfer.Pq)),
      HdrOverlayCells.SMALLEST_WRONG_BLEND_MISS,
    )
  }

  @Test
  fun `the widest blend tolerance is the largest whole code whose double is below the smallest miss`() {
    val bound = HdrOverlayCells.MAX_BLEND_TOLERANCE
    val miss = HdrOverlayCells.SMALLEST_WRONG_BLEND_MISS

    assertTrue(2 * bound < miss, "twice $bound codes reaches the smallest miss of $miss")
    assertTrue(2 * (bound + 1) >= miss, "a bound of ${bound + 1} codes still sits clear of a miss of $miss")
  }

  // A wrong blend over black reads the middle of the ramp, where mixing signals or eight-bit codes is not the
  // same as mixing light. Half way, the signal blend carries half the overlay's signal and the sRGB blend half
  // its eight-bit code, which rounds to mid grey.
  @Test
  fun `white over black at half lands on half the signal and on mid grey for the wrong blends`() {
    val cell = Cell(WHITE, BLACK, HALF)
    val white = authoredHdrNits(WHITE)

    HdrTransfer.entries.forEach { transfer ->
      val halfSignal = transfer.signalFromNits(white).map { it * HALF }
      val signalBlend = transfer.signalFromNits(cell.signalBlendNits(transfer))

      halfSignal.indices.forEach { assertEquals(halfSignal[it], signalBlend[it], SIGNAL_EPSILON, "$transfer") }
      assertContentEquals(tenBitCodesFromSignal(halfSignal.toFloatArray()), cell.signalBlendCodes(transfer))
    }
    assertContentEquals(authoredHdrNits(AuthoredColours.MID_GREY), cell.srgbBlendNits())
  }

  // An HLG reading held to the scene light blend says nothing unless a wrong blend lands more than twice the
  // blend bound from it.
  @Test
  fun `the HLG scene light blend misses a wrong blend by more than twice the widest blend tolerance`() {
    HdrOverlayCells.BLENDS.forEach { cell ->
      val sceneLight = tenBitCodesFromSignal(cell.hlgSceneLightSignal())
      val signalMiss = sceneLight.missFrom(cell.signalBlendCodes(HdrTransfer.Hlg))
      val srgbMiss = sceneLight.missFrom(cell.srgbBlendCodes(HdrTransfer.Hlg))

      assertTrue(
        minOf(signalMiss, srgbMiss) > 2 * HdrOverlayCells.MAX_BLEND_TOLERANCE,
        "HLG scene light $cell misses a wrong blend by only ${minOf(signalMiss, srgbMiss)} codes " +
          "(signal $signalMiss, sRGB $srgbMiss)",
      )
    }
  }

  // A reading held within a backend's noise of the scene light blend is off the display light rule by more
  // than that noise only if the two sit further apart than twice it. Noise is at most the blend bound.
  @Test
  fun `white over grey and the black cells sit clear of display light in HLG scene light`() {
    val clear = HdrOverlayCells.BLENDS.filter { it.overlay == WHITE || it.background == BLACK }
    assertTrue(clear.any { it.background == AuthoredColours.MID_GREY })
    assertTrue(clear.any { it.background == BLACK })

    clear.forEach { cell ->
      val gap = cell.hlgSceneLightGap()
      assertTrue(
        gap > 2 * HdrOverlayCells.MAX_BLEND_TOLERANCE,
        "HLG $cell lands only $gap codes from display light in scene light",
      )
    }
  }

  // Green over grey lands within a backend's noise of display light in HLG scene light, so a reading of it
  // cannot tell a scene light blend from the rule.
  @Test
  fun `green over grey sits within the blend bound of display light in HLG scene light`() {
    val green = HdrOverlayCells.BLENDS.filter { it.overlay == AuthoredColours.MID_GREEN }
    assertTrue(green.isNotEmpty())

    green.forEach { cell ->
      val gap = cell.hlgSceneLightGap()
      assertTrue(
        gap <= HdrOverlayCells.MAX_BLEND_TOLERANCE,
        "HLG $cell lands $gap codes from display light in scene light",
      )
    }
  }

  private fun Cell.hlgSceneLightGap(): Int =
    tenBitCodesFromSignal(hlgSceneLightSignal()).missFrom(codes(HdrTransfer.Hlg))

  private fun IntArray.missFrom(other: IntArray): Int = indices.maxOf { abs(this[it] - other[it]) }

  private companion object {
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val SIGNAL_EPSILON = 1e-4f
  }
}
