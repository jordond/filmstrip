package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

// None of these spawn anything. The toolchain is pointed at a path that does not exist, so the
// answers cannot depend on what the machine has installed.
class FfmpegProberTest {
  @Test
  fun `a source with no file behind it is refused before ffprobe is asked`() =
    runTest {
      val runtime = FfmpegRuntime.unshared(FfmpegConfig(executablePath = NOWHERE))
      val prober = FfmpegProber(runtime)

      val failure =
        prober
          .probe(
            MediaSource.ofUri("https://example.com/clip.mp4"),
          ).shouldBeInstanceOf<ProbeResult.Failure>()
      val error = failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      error.message shouldBe READS_FILES
      runtime.probeSpawns shouldBe 0
    }

  @Test
  fun `a refused source names itself the way a caller wrote it`() =
    runTest {
      val prober = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig(executablePath = NOWHERE)))

      val failure =
        prober
          .probe(
            MediaSource.ofUri("https://example.com/clip.mp4"),
          ).shouldBeInstanceOf<ProbeResult.Failure>()

      failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().source shouldBe "https://example.com/clip.mp4"
    }

  @Test
  fun `a file with no toolchain to read it reports the missing tool rather than the file`() =
    runTest {
      val runtime = FfmpegRuntime.unshared(FfmpegConfig(executablePath = NOWHERE))
      val prober = FfmpegProber(runtime)

      val failure = prober.probe(MediaSource.of("/nonexistent/clip.mp4")).shouldBeInstanceOf<ProbeResult.Failure>()

      failure.error.shouldBeInstanceOf<FilmstripError.ToolchainMissing>().tool shouldBe "ffmpeg"
      runtime.probeSpawns shouldBe 0
    }

  @Test
  fun `a buffer goes to the toolchain as a file rather than being refused`() =
    runTest {
      val runtime = FfmpegRuntime.unshared(FfmpegConfig(executablePath = NOWHERE))

      val failure =
        FfmpegProber(
          runtime,
        ).probe(MediaSource.ofBytes(ByteArray(16))).shouldBeInstanceOf<ProbeResult.Failure>()

      failure.error.shouldBeInstanceOf<FilmstripError.ToolchainMissing>().tool shouldBe "ffmpeg"
    }

  @Test
  fun `a buffer that cannot be written is unreadable and nothing is spawned`() =
    runTest {
      val blocked = File.createTempFile("filmstrip-blocked-root", null)
      try {
        val runtime =
          FfmpegRuntime.unshared(
            FfmpegConfig(executablePath = NOWHERE),
            bytesRoot = BytesRoot(blocked.path),
          )

        val failure =
          FfmpegProber(runtime).probe(MediaSource.ofBytes(ByteArray(16))).shouldBeInstanceOf<ProbeResult.Failure>()

        failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldContain "temporary file"
        runtime.probeSpawns shouldBe 0
      } finally {
        blocked.delete()
      }
    }

  private companion object {
    const val NOWHERE = "/nonexistent/prober/ffmpeg"
  }
}
