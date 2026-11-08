package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.toPlaybackError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A composition the media3 lowering refuses while the preview rebuilds reads as the error an export of the same
 * composition reports, mapped onto a player's arms.
 */
class AndroidLoweringRefusalTest {
  @Test
  fun aLoweringRefusalReadsAsTheExportsInvalidComposition() =
    contractTest { scope ->
      val engine =
        Media3PlayerEngine(
          parent = scope,
          context = contractContext(),
          planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
          config = PlayerConfig(),
          lower = { throw Media3LoweringFailure(REASON) },
        )
      withEngine(engine) { recorder ->
        val failed =
          engine
            .awaitComposition(androidFixtureComposition())
            .shouldBeInstanceOf<SetCompositionResult.Failure>()

        failed.error shouldBe FilmstripError.InvalidComposition(REASON).toPlaybackError()
        recorder.lastState.status shouldBe PlaybackStatus.Error(failed.error)
      }
    }

  private companion object {
    const val REASON = "Nothing in this composition contributes a track to encode."
  }
}
