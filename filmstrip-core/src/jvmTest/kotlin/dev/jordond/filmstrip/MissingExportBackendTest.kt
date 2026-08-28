package dev.jordond.filmstrip

import dev.jordond.filmstrip.export.ExportError
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The desktop's encoder is a different artifact from every other target's, so the failure that
// names one is per-target too. Naming filmstrip-transform here sends a caller back to the artifact
// they already added, and to the call they already made.
class MissingExportBackendTest {
  @Test
  fun `names the artifact that actually encodes on this target`() =
    runTest {
      val result = Filmstrip().capabilities()

      val error = assertIs<ExportError.BackendMissing>(assertIs<CapabilitiesResult.Failure>(result).error)
      error.artifact shouldBe "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"
      assertTrue("ffmpegBackend()" in error.message, "the message did not name the call: ${error.message}")
    }
}
