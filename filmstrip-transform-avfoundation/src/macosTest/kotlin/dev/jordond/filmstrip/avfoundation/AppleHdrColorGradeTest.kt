package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * What a color matrix does to a frame that keeps its grade, measured in light.
 *
 * Core Image holds an HDR frame as display referred linear light, and the lowering moves that into
 * the SDR signal a display at reference white would have been fed, runs the matrix there, and moves
 * it back. The figure under test comes from [transformNits], the reading every backend is held to,
 * rather than from a measurement taken once. Each transfer's identity export is made once and shared
 * by every case in the class. AppleHdrColorGradeCanaryTest runs the contrast case on iOS.
 *
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
class AppleHdrColorGradeTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a contrast on a PQ grade pivots where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      filmstrip.assertSofterContrastPivots(identityLight(HdrTransfer.Pq))
    }

  // The mix arm. Without a bias the choice of white cancels, so what this checks is that the mix
  // ran on the encoded signal rather than on light.
  @Test
  fun `a saturation on a PQ grade mixes what the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val lit = identityLight(HdrTransfer.Pq)
      val graded = filmstrip.exportedGradeLight(hdrFixture(HdrTransfer.Pq)) { saturation(MUTED) }
      val matrix = matrixOf(Saturation(MUTED))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      assertGraded(matrix, expected, graded, SATURATION_DRIFT, lit, HdrTransfer.Pq)
    }

  // The HLG arm, with both halves of the matrix in one run. Core Image is display referred whatever
  // the transfer, and the probe decodes HLG the same way the effect saw it, so the reading holds to
  // the encode's drift here too. The matrix run on scene light is the mistake HLG adds.
  @Test
  fun `an HLG grade lands where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val lit = identityLight(HdrTransfer.Hlg)
      val graded =
        filmstrip.exportedGradeLight(hdrFixture(HdrTransfer.Hlg)) { contrast(SOFTER_CONTRAST).saturation(MUTED) }
      val matrix = matrixOf(Contrast(SOFTER_CONTRAST), Saturation(MUTED))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Hlg)

      assertGraded(matrix, expected, graded, HLG_GRADE_DRIFT, lit, HdrTransfer.Hlg)
    }

  // Above white there is headroom rather than a ceiling. Where the signal the matrix asks for is
  // past what PQ can carry it clips, and that is the format's limit rather than the effect's, so it
  // is asserted as one instead of pretending the two exports match there.
  @Test
  fun `a channel pushed past the peak lands on the format's ceiling`() =
    runTest(timeout = TIMEOUT) {
      val lit = identityLight(HdrTransfer.Pq)
      val graded = filmstrip.exportedGradeLight(hdrFixture(HdrTransfer.Pq)) { contrast(HARSH) }
      val expected = matrixOf(Contrast(HARSH)).transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      val clipped = lit.litChannels().filter { expected[it] >= PQ_PEAK_NITS * HEADROOM }
      withClue("no channel of $lit reached the ceiling under a contrast of $HARSH") {
        clipped.isNotEmpty().shouldBeTrue()
      }
      clipped.forEach { channel ->
        withClue(
          "channel $channel asked for ${expected[channel]} nits and read ${graded[channel]}, short of the " +
            "format's own ceiling",
        ) {
          (graded[channel] > lit[channel] && graded[channel] >= PQ_PEAK_NITS * HEADROOM).shouldBeTrue()
        }
      }
    }

  // The centre of the graded fixture carrying transfer under an identity contrast. Every case reads its matrix
  // against this, so a transfer's reference is exported once for the class.
  private suspend fun identityLight(transfer: HdrTransfer): List<Float> =
    identities.getOrPut(transfer) { filmstrip.exportedGradeLight(hdrFixture(transfer)) { contrast(1f) } }

  private companion object {
    val TIMEOUT = 5.minutes

    val identities = mutableMapOf<HdrTransfer, List<Float>>()

    const val HARSH = 2f
    const val MUTED = 0.5f

    // How far a PQ saturation's read strays from the shared reading, as a fraction of it. Held on macOS, where it
    // measured 0.64%, against 1.96% on the iOS simulator. Run on light it misses by 40.4% on macOS and 40.2% on the
    // iOS simulator.
    const val SATURATION_DRIFT = 0.04f

    // The same for an HLG contrast and saturation together. Held on macOS, where it measured 0.22%, against 0.90% on
    // the iOS simulator. Run on scene light it misses by 13% and on light by 42%.
    const val HLG_GRADE_DRIFT = 0.02f

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f
  }
}
