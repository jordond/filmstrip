package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.ffprobe
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * One export through a real ffmpeg on every push, two clips planned, reframed and written to a file read back with
 * ffprobe. It reads no pixels, so it only shows the pipeline still runs end to end.
 */
class FfmpegExportSmokeTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `exports two clips into one reframed file`() =
    runTest(timeout = TIMEOUT) {
      withClue("the export fixtures are generated") { fixture.available().shouldBeTrue() }

      val output = File.createTempFile("filmstrip-export", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath)) {
            trim(TimeRange.of(0.milliseconds, 1_000.milliseconds))
          }
          clip(MediaSource.of(fixture.portrait.absolutePath)) {
            trim(TimeRange.of(0.milliseconds, 1_000.milliseconds))
            effects { rotate(180) }
          }
          effects { crop(AspectRatio.Portrait) }
        }

      val spec = ExportSpec(heightPx = 240)
      val verdict = fixture.filmstrip.plan(composition, spec)
      val plan =
        when (verdict) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }

      plan.output.size.height shouldBe 240
      plan.output.size.width shouldBe 134

      val statuses = fixture.filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList()
      val finished = statuses.last()
      if (finished is ExportStatus.Failure) error(finished.error.message)

      statuses.first().shouldBeInstanceOf<ExportStatus.Started>()
      withClue("expected progress") { statuses.any { it is ExportStatus.Progress }.shouldBeTrue() }

      val success = finished.shouldBeInstanceOf<ExportStatus.Success>()
      success.info.video!!.displaySize shouldBe plan.output.size
      success.info.audio!!.sampleRate shouldBe 48_000
      // Two one second clips, laid end to end.
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 1_800.milliseconds..2_300.milliseconds).shouldBeTrue()
      }

      // Verified independently of the code under test.
      val probe = ffprobe(output.absolutePath)
      probe shouldBe "134x240 30/1 h264 aac"

      output.delete()
    }
}
