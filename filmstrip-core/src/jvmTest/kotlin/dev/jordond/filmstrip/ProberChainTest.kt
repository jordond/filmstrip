package dev.jordond.filmstrip

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

// Registered probers answer ahead of core's own, and the dispatch is common code. It is tested on
// the JVM because that is the target whose platform prober is a deterministic decline. On Android
// the same test would need a Context and a MediaMetadataRetriever, neither of which a host test
// has.
class ProberChainTest {
  private val source = MediaSource.of("/does/not/exist.mp4")

  @Test
  fun `a registered prober answers ahead of the platform`() =
    runTest {
      val filmstrip =
        Filmstrip {
          addMediaProberFactory { MediaProber { ProbeResult.Success(INFO) } }
        }

      assertIs<ProbeResult.Success>(filmstrip.probe(source)).info.duration shouldBe 1_234.milliseconds
    }

  @Test
  fun `the last registration wins`() =
    runTest {
      val filmstrip =
        Filmstrip {
          addMediaProberFactory { MediaProber { ProbeResult.Success(INFO) } }
          addMediaProberFactory { MediaProber { ProbeResult.Success(LATER) } }
        }

      assertIs<ProbeResult.Success>(filmstrip.probe(source)).info.duration shouldBe 9_999.milliseconds
    }

  @Test
  fun `a prober that declines does not stop one that answers`() =
    runTest {
      val filmstrip =
        Filmstrip {
          addMediaProberFactory { MediaProber { ProbeResult.Success(INFO) } }
          addMediaProberFactory { MediaProber { failure("second") } }
        }

      assertIs<ProbeResult.Success>(filmstrip.probe(source)).info.duration shouldBe 1_234.milliseconds
    }

  // The prober registered last knows the most, so its reason is the one the caller sees even though
  // the platform prober was consulted after it.
  @Test
  fun `the first failure is the one reported`() =
    runTest {
      val filmstrip =
        Filmstrip {
          addMediaProberFactory { MediaProber { failure("first") } }
          addMediaProberFactory { MediaProber { failure("second") } }
        }

      val failure = assertIs<ProbeResult.Failure>(filmstrip.probe(source))
      failure.error.message shouldBe "second"
    }

  @Test
  fun `a factory that declines is skipped`() =
    runTest {
      val filmstrip =
        Filmstrip {
          addMediaProberFactory { MediaProber { ProbeResult.Success(INFO) } }
          addMediaProberFactory { null }
        }

      assertIs<ProbeResult.Success>(filmstrip.probe(source)).info.duration shouldBe 1_234.milliseconds
    }

  private fun failure(message: String): ProbeResult.Failure =
    ProbeResult.Failure(ExportError.SourceUnreadable(source = "test", message = message))

  private companion object {
    val INFO = MediaInfo(duration = 1_234.milliseconds, video = null, audio = null, isExportable = true)
    val LATER = MediaInfo(duration = 9_999.milliseconds, video = null, audio = null, isExportable = true)
  }
}
