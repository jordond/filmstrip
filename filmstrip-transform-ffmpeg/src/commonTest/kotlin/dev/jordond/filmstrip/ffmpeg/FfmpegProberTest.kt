package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

// None of these spawn anything. The toolchain is pointed at a path that does not exist, so the
// answers cannot depend on what the machine has installed.
class FfmpegProberTest {
  @Test
  fun `a source with no file behind it is refused before ffprobe is asked`() =
    runTest {
      val runtime = FfmpegRuntime.unshared(FfmpegConfig(executablePath = NOWHERE))
      val prober = FfmpegProber(runtime)

      listOf(
        MediaSource.ofBytes(ByteArray(16)),
        MediaSource.ofUri("https://example.com/clip.mp4"),
      ).forEach { source ->
        val failure = prober.probe(source).shouldBeInstanceOf<ProbeResult.Failure>()
        val error = failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

        error.message shouldBe READS_FILES
      }
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

  private companion object {
    const val NOWHERE = "/nonexistent/prober/ffmpeg"
  }
}
