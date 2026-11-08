package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.test.HdrOverlayCells
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The letterbox fill on an export that keeps its grade, read back out of the written file.
 *
 * A square crop of the 16:9 HDR fixtures leaves a bar above and below the picture that only the fill paints. The bar is
 * held to [signalFromNits] of [authoredHdrNits], the signal every backend writes there, so a white fill lands at
 * reference white rather than at the format's peak.
 *
 * A case fails when its fixture is missing, as in [AppleExportHardwareTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrFillHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a white fill on a kept grade is written at graphics white not at the panel peak`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val frame = filmstrip.exportedFrame(transfer, HdrOverlayCells.WHITE)
        assertCarriesSignal(frame, transfer, HdrOverlayCells.WHITE)
        assertCarriesLight(frame, transfer, HdrOverlayCells.WHITE, -NITS_DRIFT..NITS_DRIFT)
      }
    }

  // White is the end of the range, where reading the authored fraction as light and reading it
  // through the sRGB curve agree. A color in the middle is where the two part. The signal check is
  // what tells a right fill from a wrong one on both transfers. Core Image reads an HLG bar bright,
  // which AppleHdrFillCoreImageHardwareTest pins where that range is measured.
  @Test
  fun `a mid-range fill on a kept grade carries its own signal on both transfers`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val frame = filmstrip.exportedFrame(transfer, MID_BLUE)
        assertCarriesSignal(frame, transfer, MID_BLUE)
        if (transfer == HdrTransfer.Pq) assertCarriesLight(frame, transfer, MID_BLUE, -NITS_DRIFT..NITS_DRIFT)
      }
    }

  private fun assertCarriesSignal(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
  ) {
    val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color))).toList()
    val read = frame.codesAt(FILL_CENTRE, FILL_BAR)

    withClue("the $transfer bar carried codes $read and the shared signal asks for $expected") {
      expected.indices.all { abs(read[it] - expected[it]) <= FILL_CODE_TOLERANCE }.shouldBeTrue()
    }
  }

  /**
   * Holds each channel of the bar's light to the shared fill.
   *
   * The fraction a channel reads off by, from [HdrFrameProbe.lightOff], has to sit inside [drift].
   */
  private fun assertCarriesLight(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
    drift: ClosedFloatingPointRange<Float>,
  ) {
    val off = frame.lightOff(color)

    withClue(
      "the $transfer bar read ${frame.nitsAt(FILL_CENTRE, FILL_BAR)} nits and the shared fill asks for " +
        "${authoredHdrNits(color).toList()}, off by $off where $drift is allowed",
    ) {
      off.all { it in drift }.shouldBeTrue()
    }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    // A code of rounding near reference white moves light by about a percent, with room for the
    // encoder on top of that.
    const val NITS_DRIFT = 0.04f
  }
}
