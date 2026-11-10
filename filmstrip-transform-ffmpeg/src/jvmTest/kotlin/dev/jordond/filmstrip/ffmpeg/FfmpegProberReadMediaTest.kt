package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.proRes4444Clip
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds

/**
 * What [FfmpegProber] reports once a real ffprobe has read the file.
 */
class FfmpegProberReadMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a readable clip comes back with the tracks ffprobe found`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val probed = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig())).probe(MediaSource.of(fixture.landscape.path))

      val info = probed.shouldBeInstanceOf<ProbeResult.Success>().info
      info.video.shouldNotBeNull().displaySize shouldBe Size(640, 360)
      info.audio.shouldNotBeNull().sampleRate shouldBe 48_000
    }

  // ffprobe prints the length to six decimals, so a microsecond is as close as its figure can get.
  @Test
  fun `a primed audio file reports the length its movie header gives`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val primed = File.createTempFile("filmstrip-primed", ".mp4").apply { writeBytes(PrimedAudio.mp4()) }
      try {
        val probed = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig())).probe(MediaSource.of(primed.path))

        val duration = probed.shouldBeInstanceOf<ProbeResult.Success>().info.duration
        (duration - PrimedAudio.presentedLength).absoluteValue shouldBeLessThanOrEqualTo 1.microseconds
      } finally {
        primed.delete()
      }
    }

  @Test
  fun `a prores 4444 clip with alpha reads as alpha and an opaque clip does not`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val prober = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig()))
      val alpha = proRes4444Clip(64, 64, red = 200, green = 40, blue = 40, alpha = 128)

      val clip = prober.probe(MediaSource.of(alpha.path)).shouldBeInstanceOf<ProbeResult.Success>().info
      val opaque = prober.probe(MediaSource.of(fixture.landscape.path)).shouldBeInstanceOf<ProbeResult.Success>().info

      clip.video.shouldNotBeNull().hasAlpha shouldBe true
      opaque.video.shouldNotBeNull().hasAlpha shouldBe false
    }

  @Test
  fun `a file ffprobe finds no track in is unreadable and names the file`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val junk = File.createTempFile("filmstrip-probe", ".mp4").apply { writeText("not a video") }
      try {
        val probed = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig())).probe(MediaSource.of(junk.path))

        val error =
          probed
            .shouldBeInstanceOf<ProbeResult.Failure>()
            .error
            .shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
        error.source shouldBe junk.path
        error.message shouldContain "ffprobe"
      } finally {
        junk.delete()
      }
    }

  @Test
  fun `a file read twice through one runtime spawns ffprobe once`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val runtime = FfmpegRuntime.unshared(FfmpegConfig())
      val prober = FfmpegProber(runtime)
      val source = MediaSource.of(fixture.landscape.path)

      prober.probe(source)
      prober.probe(source)

      runtime.probeSpawns shouldBe 1
    }

  // The runtime outlives every engine in the process, so a refusal it held on to would stay a refusal after the file
  // turned up.
  @Test
  fun `a file that could not be read is read again once it is there`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val later = File.createTempFile("filmstrip-probe", ".mp4").apply { writeText("not a video yet") }
      try {
        val runtime = FfmpegRuntime.unshared(FfmpegConfig())
        val prober = FfmpegProber(runtime)
        val source = MediaSource.of(later.path)

        prober.probe(source).shouldBeInstanceOf<ProbeResult.Failure>()
        fixture.landscape.copyTo(later, overwrite = true)

        prober.probe(source).shouldBeInstanceOf<ProbeResult.Success>()
        runtime.probeSpawns shouldBe 2
      } finally {
        later.delete()
      }
    }
}
