package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.toPlaybackError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A composition the AVFoundation lowering refuses while the preview rebuilds reads as the error an export of the same
 * composition reports, mapped onto a player's arms.
 */
class AppleLoweringRefusalTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a lowering refusal reads as the export's invalid composition`() =
    contractTest { scope ->
      val engine =
        AvPlayerEngine(
          scope,
          AvPreviewPlanner(CONTRACT_COMPONENTS),
          PlayerConfig(),
          muted = true,
          lower = { throw AvLoweringFailure(REASON) },
        )
      withEngine(engine) { recorder ->
        val failed =
          engine
            .awaitComposition(appleFixtureComposition())
            .shouldBeInstanceOf<SetCompositionResult.Failure>()

        failed.error shouldBe FilmstripError.InvalidComposition(REASON).toPlaybackError()
        recorder.lastState.status shouldBe PlaybackStatus.Error(failed.error)
      }
    }

  private companion object {
    const val REASON = "The clip's frames could not be laid."
  }
}
