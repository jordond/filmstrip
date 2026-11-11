package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where an ffmpeg export writes its key frames against the spacing its plan settled on.
 *
 * The software encoders hold the spacing exactly. The VideoToolbox ones read it as a ceiling and add key frames of
 * their own, so a file one of them wrote is only held to going no longer than the spacing between two.
 */
class FfmpegKeyFrameMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a transcode writes a key frame every second by default`() =
    runTest(timeout = TIMEOUT) {
      for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) assertSpacing(ExportSpec(videoCodec = codec))
    }

  @Test
  fun `a half second interval writes a key frame every half second`() =
    runTest(timeout = TIMEOUT) {
      for (codec in listOf(VideoCodec.H264, VideoCodec.Hevc)) {
        assertSpacing(ExportSpec(videoCodec = codec, keyFrameInterval = 500.milliseconds))
      }
    }

  @Test
  fun `a copy keeps the source's own key frames`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.long.isFile) return@runTest
      val output = File.createTempFile("filmstrip-key-frames-copy", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.long.absolutePath)) }

      val plan = fixture.plannedRun(composition, ExportSpec(keyFrameInterval = 100.milliseconds))
      plan.path shouldBe ExportPath.Copy
      plan.output.keyFrameSpacing.shouldBeNull()
      fixture.exported(plan, output)

      keyFramesOf(output) shouldBe keyFramesOf(fixture.long)
      output.delete()
    }

  private suspend fun assertSpacing(spec: ExportSpec) {
    if (!fixture.long.isFile) return
    val encoder = encoderFor(spec.videoCodec) ?: return
    val output = File.createTempFile("filmstrip-key-frames", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.long.absolutePath)) { trim(TimeRange.of(0.milliseconds, 3_000.milliseconds)) }
      }

    val plan = fixture.plannedRun(composition, spec)
    val spacing = keyFrameIntervalOf(spec, plan.output.frameRate)
    plan.output.keyFrameSpacing shouldBe spacing
    fixture.exported(plan, output)
    val (keyFrames, frames) = keyFramesOf(output)

    frames shouldBeGreaterThan spacing * 2
    withClue("$encoder wrote key frames at $keyFrames of $frames frames, planned every $spacing") {
      if (encoder.endsWith(VIDEOTOOLBOX)) {
        keyFrames.first() shouldBe 0
        (keyFrames + frames).zipWithNext { from, to -> to - from }.max() shouldBeLessThanOrEqual spacing
      } else {
        keyFrames shouldBe (0 until frames step spacing).toList()
      }
    }
    output.delete()
  }

  // The name of the encoder this build writes [codec] with, or null when it has none.
  private suspend fun encoderFor(codec: VideoCodec): String? =
    fixture.filmstrip
      .capabilities()
      .shouldBeInstanceOf<CapabilitiesResult.Success>()
      .capabilities
      .encoderFor(codec)
      ?.encoderName

  // The index of every key frame on the video stream in the order the frames are shown, and how many packets the
  // stream holds.
  private fun keyFramesOf(file: File): Pair<List<Int>, Int> {
    val process =
      ProcessBuilder(
        hostFfprobe,
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "packet=pts_time,flags",
        "-of",
        "csv=p=0",
        file.absolutePath,
      ).start()
    val packets =
      process
        .inputReader()
        .readLines()
        .filter { it.isNotBlank() }
        .map { it.substringBefore(',').trim().toDouble() to it.substringAfter(',').startsWith('K') }
        .sortedBy { it.first }
    process.waitFor()
    return packets.indices.filter { packets[it].second } to packets.size
  }

  private companion object {
    const val VIDEOTOOLBOX = "_videotoolbox"
  }
}
