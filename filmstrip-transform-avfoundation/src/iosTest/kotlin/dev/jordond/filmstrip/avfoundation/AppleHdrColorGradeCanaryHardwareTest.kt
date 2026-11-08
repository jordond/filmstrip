package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Core Image's lowering of a color matrix on a frame that keeps its grade, on the iOS simulator and on an iPhone.
 *
 * Runs the mid-range PQ contrast case of AppleHdrColorGradeHardwareTest, which holds every grade on macOS, against the
 * same shared reading and bound. A case fails when its fixture is missing, as in [AppleExportHardwareTest].
 */
class AppleHdrColorGradeCanaryHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a contrast on a PQ grade pivots where the shared reading says on iOS`() =
    runTest(timeout = TIMEOUT) {
      val lit = filmstrip.exportedGradeLight(hdrFixture(HdrTransfer.Pq)) { contrast(1f) }
      filmstrip.assertSofterContrastPivots(lit)
    }

  private companion object {
    val TIMEOUT = 5.minutes
  }
}
