package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A copy whose source lists its video packets with no presentation times, the way ffprobe reads an AVI holding
 * B-frames.
 */
class FfmpegUntimedCopyMediaTest {
  private val fixture = FfmpegExportFixture()

  // Counted by packets with no times, the copy would drop every video packet and still exit cleanly.
  @Test
  fun `a copy ending before an untimed source's end transcodes and names why`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val avi = untimedSource()
      val output = File.createTempFile("filmstrip-untimed-copy", ".mp4").also { it.delete() }
      val source = MediaSource.of(avi.absolutePath)
      val composition = compositionOf { clip(source) { trim(TimeRange.of(Duration.ZERO, END)) } }

      val negotiated =
        fixture
          .plannedLowering(composition, ExportSpec(), listOf(source))
          .export.composition
          .shouldNotBeNull()
      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      fixture.exported(plan, output)

      withClue("the export wrote ${videoPackets(output)} video packets on a grid of ${negotiated.videoSlots}") {
        videoPackets(output).toLong() shouldBe negotiated.videoSlots
      }
      plan.path shouldBe ExportPath.Transcode
      plan.copyBlockedBy shouldBe listOf(CopyBlocker.SourceUntimed)

      output.delete()
      avi.delete()
    }

  private fun videoPackets(file: File): Int {
    val process =
      ProcessBuilder(
        hostFfprobe,
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-count_packets",
        "-show_entries",
        "stream=nb_read_packets",
        "-of",
        "csv=p=0",
        file.absolutePath,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()
    return output.trim().toIntOrNull() ?: 0
  }

  /**
   * Four seconds of H264 with two B-frames per reference, written into AVI with real ffmpeg, whose packets ffprobe
   * lists with no times. Tagged `avc1` and silent, so the mp4 muxer takes it and nothing but the times blocks the copy.
   */
  private fun untimedSource(): File {
    val target = File.createTempFile("filmstrip-untimed-source", ".avi")
    ProcessBuilder(
      hostFfmpeg,
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "lavfi",
      "-i",
      "testsrc2=size=320x240:rate=30:duration=4",
      "-c:v",
      "libx264",
      "-bf",
      "2",
      "-g",
      "30",
      "-tag:v",
      "avc1",
      "-f",
      "avi",
      target.absolutePath,
    ).start().waitFor()
    return target
  }

  private companion object {
    // Well short of the source's four seconds, so the copy has to stop its video on a count.
    val END = 1_010.milliseconds
  }
}
