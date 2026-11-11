@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.interop.isFullSync
import dev.jordond.filmstrip.avfoundation.internal.writer.videoWriterSettings
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.makeSampleCursorWithPresentationTimeStamp
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the AVFoundation writer asks VideoToolbox for, and where the file it writes puts its key frames against the
 * spacing the plan settled on.
 *
 * VideoToolbox takes the spacing as a ceiling. It starts a key frame only where one of its reorder groups starts and
 * adds key frames of its own, at places that move from one run to the next, so two key frames sit as far apart as the
 * plan asks or closer and never further.
 */
class AppleKeyFrameHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `the writer asks for the plan's key frame spacing`() {
    val spacing = keyFrameIntervalOf(ExportSpec(keyFrameInterval = 500.milliseconds), 25)
    val output = OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.None, Bitrate.mbps(2), 25, null, spacing)

    val settings = videoWriterSettings(output, encodesHdr = false, transfer = null, sourceFormat = null)
    val compression = (settings[AVVideoCompressionPropertiesKey] as? Map<*, *>).shouldNotBeNull()

    (compression[AVVideoMaxKeyFrameIntervalKey] as? Number)?.toInt() shouldBe spacing
  }

  @Test
  fun `a transcode goes no longer than a second between key frames by default`() =
    runTest(timeout = TIMEOUT) {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264))
    }

  @Test
  fun `a half second interval goes no longer than half a second between key frames`() =
    runTest(timeout = TIMEOUT) {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264, keyFrameInterval = 500.milliseconds))
    }

  @Test
  fun `a copy keeps the source's own key frames`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(LONG_CLIP)
      val composition = compositionOf { clip(MediaSource.of(source)) }
      val output = NSTemporaryDirectory() + "filmstrip-apple-key-frames-copy.mp4"

      val written =
        withContext(Dispatchers.Default) {
          val plan = planOf(composition, ExportSpec(keyFrameInterval = 100.milliseconds))
          plan.path shouldBe ExportPath.Copy
          plan.output.keyFrameSpacing.shouldBeNull()
          export(plan, output)
          keyFramesOf(output)
        }

      written shouldBe keyFramesOf(source)
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  private suspend fun assertSpacing(spec: ExportSpec) {
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture(LONG_CLIP))) { trim(0.seconds, 3.seconds) }
      }
    val output = NSTemporaryDirectory() + "filmstrip-apple-key-frames.mp4"

    val (spacing, written) =
      withContext(Dispatchers.Default) {
        val plan = planOf(composition, spec)
        val spacing = keyFrameIntervalOf(spec, plan.output.frameRate)
        plan.output.keyFrameSpacing shouldBe spacing
        export(plan, output)
        spacing to keyFramesOf(output)
      }

    val (keyFrames, frames) = written
    frames shouldBeGreaterThan spacing * 2
    withClue("key frames at $keyFrames of $frames frames, planned every $spacing") {
      keyFrames.first() shouldBe 0
      val gaps = (keyFrames + frames).zipWithNext { from, to -> to - from }
      gaps.max() shouldBeLessThanOrEqual spacing
      // Longer than half the spacing, so a ceiling half as long would have cut it.
      gaps.max() shouldBeGreaterThan spacing / 2
    }
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  private suspend fun planOf(
    composition: Composition,
    spec: ExportSpec,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }

  private suspend fun export(
    plan: ExportPlan,
    output: String,
  ) {
    val finished = filmstrip.export(plan, MediaSink.of(output)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()
  }

  // The index of every sync sample on the video track in the order the frames are shown, and how many samples the
  // track holds.
  private fun keyFramesOf(path: String): Pair<List<Int>, Int> {
    val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .single() as AVAssetTrack
    val cursor = track.makeSampleCursorWithPresentationTimeStamp(kCMTimeZero.readValue()).shouldNotBeNull()
    val keyFrames = mutableListOf<Int>()
    var frames = 0
    do {
      if (cursor.isFullSync()) keyFrames += frames
      frames++
    } while (cursor.stepInPresentationOrderByCount(1) == 1L)
    return keyFrames to frames
  }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    const val LONG_CLIP = "apple_export_long.mp4"
  }
}
