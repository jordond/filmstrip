package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a backend says about the sound it writes to the seam contract, on top of its [ClipParityBackend].
 */
interface SeamSoundBackend : ClipParityBackend {
  /**
   * The first channel of the audio [composition] exports to, with the encoder's own lead and tail left out.
   */
  suspend fun soundOf(composition: Composition): HeardSound
}

/**
 * The sound of two clips of one source whose trims meet, `[a, b)` and `[b, c)`, in the file a backend writes.
 *
 * The fixture's audio is one continuous tone, so the seam is where the tone steps in phase or falls quiet. A backend
 * holds the seam to [SHARED_SEAM_BOUND].
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class SeamSoundContractTest(
  backend: SeamSoundBackend,
) : SeamSoundBackend by backend {
  @Test
  fun cellSoundAtASeamOnAnAccessUnit() = assertExportSeam(ON_A_UNIT)

  @Test
  fun cellSoundAtASeamAMillisecondIntoAnAccessUnit() = assertExportSeam(JUST_PAST_A_UNIT)

  @Test
  fun cellSoundAtASeamNearTheMiddleOfAnAccessUnit() = assertExportSeam(MIDDLE_OF_A_UNIT)

  private fun assertExportSeam(b: Duration) =
    contractTest { _ ->
      val edit = seamEdit(b)
      val measured = measureSeam(soundOf(edit), seamAt = b)
      withClue("the file's seam at $b held a quiet run of ${measured.quietRun} against ${SHARED_SEAM_BOUND.quietRun}") {
        (measured.quietRun <= SHARED_SEAM_BOUND.quietRun).shouldBeTrue()
      }
      withClue("the file's tone stepped by ${measured.phaseStep} at $b against ${SHARED_SEAM_BOUND.phaseStep}") {
        (measured.phaseStep.absoluteValue <= SHARED_SEAM_BOUND.phaseStep).shouldBeTrue()
      }
    }

  private fun seamEdit(b: Duration): Composition =
    Composition(
      listOf(
        Track(listOf(Clip(fixtureClip, TimeRange.of(Duration.ZERO, b)), Clip(fixtureClip, TimeRange.of(b, b + LATER)))),
      ),
    )

  private companion object {
    // The second clip's length, long enough for a window after the seam.
    val LATER = 500.milliseconds

    // 24 access units of 1024 frames at 48 kHz, a millisecond past that, and ten milliseconds into the unit after the
    // 23rd.
    val ON_A_UNIT = 512.milliseconds
    val JUST_PAST_A_UNIT = 513.milliseconds
    val MIDDLE_OF_A_UNIT = 501.milliseconds
  }
}
