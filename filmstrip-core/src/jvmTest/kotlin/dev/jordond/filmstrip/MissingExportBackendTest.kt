package dev.jordond.filmstrip

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

// The desktop's encoder is a different artifact from every other target's, so the failure that
// names one is per-target too. Naming filmstrip-transform here sends a caller back to the artifact
// they already added, and to the call they already made.
class MissingExportBackendTest {
  @Test
  fun `names the artifact that actually encodes on this target`() =
    runTest {
      val result = Filmstrip().capabilities()

      val error =
        result
          .shouldBeInstanceOf<CapabilitiesResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.BackendMissing>()
      error.artifact shouldBe "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"
      withClue("the message did not name the call: ${error.message}") {
        ("ffmpegBackend()" in error.message).shouldBeTrue()
      }
    }
}
