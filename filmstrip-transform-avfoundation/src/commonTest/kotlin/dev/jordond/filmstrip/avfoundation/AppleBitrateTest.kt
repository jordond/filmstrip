@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.writer.videoWriterSettings
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.estimatedDataRate
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What the AVFoundation writer asks VideoToolbox for, and what the file it writes measures against the plan.
 */
class AppleBitrateTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `the writer asks for the plan's bitrate over the assistant's`() {
    val planned = resolveVideoBitrate(VideoQuality.Medium, VideoCodec.Hevc, Size(1280, 720), 60)
    val output = OutputFormat(Size(1280, 720), VideoCodec.Hevc, AudioCodec.None, planned, 60, null)

    val settings = videoWriterSettings(output, encodesHdr = false, transfer = null, sourceFormat = null)
    val compression = (settings[AVVideoCompressionPropertiesKey] as? Map<*, *>).shouldNotBeNull()

    (compression[AVVideoAverageBitRateKey] as? Number)?.toLong() shouldBe planned.bitsPerSecond
  }

  // The long fixture is scaled up to 1080p by an effect, since a spec height never upscales.
  @Test
  fun `a 1080p H264 export lands near the bitrate the plan settled on`() =
    runTest(timeout = TIMEOUT) {
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture("apple_export_long.mp4"))) {
            trim(0.seconds, 6.seconds)
            effects { scale(1080) }
          }
        }
      val spec = ExportSpec(videoCodec = VideoCodec.H264)
      val output = NSTemporaryDirectory() + "filmstrip-apple-bitrate.mp4"

      val measured =
        withContext(Dispatchers.Default) {
          val plan =
            when (val verdict = filmstrip.plan(composition, spec)) {
              is Verdict.Capable -> verdict.plan
              is Verdict.Degraded -> verdict.plan
              is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
            }
          plan.output.size shouldBe Size(1920, 1080)
          val planned = plan.output.bitrate.shouldNotBeNull()
          planned shouldBe
            resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, plan.output.size, plan.output.frameRate)

          val finished = filmstrip.export(plan, MediaSink.of(output)).toList().last()
          if (finished is ExportStatus.Failure) error(finished.error.message)
          finished.shouldBeInstanceOf<ExportStatus.Success>()
          planned.bitsPerSecond to videoDataRate(output)
        }

      val (planned, written) = measured
      withClue("measured $written bps against a planned $planned") {
        written shouldBe (planned.toDouble() plusOrMinus planned * TOLERANCE)
      }
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  // AVFoundation works the rate out of the track's own sample sizes over its duration.
  private fun videoDataRate(path: String): Double =
    (
      AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .single() as AVAssetTrack
    ).estimatedDataRate.toDouble()

  private companion object {
    val TIMEOUT = 2.seconds * 60

    // How far a measured stream may sit from the planned bitrate, as a fraction of it.
    const val TOLERANCE = 0.15
  }
}
