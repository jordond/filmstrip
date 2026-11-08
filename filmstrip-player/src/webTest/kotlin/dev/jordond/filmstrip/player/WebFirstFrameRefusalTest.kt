package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.NO_FIRST_FRAME
import dev.jordond.filmstrip.player.internal.toPlaybackError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A browser load whose first frame the decoder never hands back.
 */
class WebFirstFrameRefusalTest {
  @Test
  fun `a load that draws no first frame fails the way an export of it would`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope, drawFirstFrame = { _, _ -> false }).engine
      withEngine(engine) {
        engine
          .awaitComposition(webFixtureComposition())
          .shouldBeInstanceOf<SetCompositionResult.Failure>()
          .error shouldBe FilmstripError.InvalidComposition(NO_FIRST_FRAME).toPlaybackError()
      }
    }
}
