package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a media3 export writes against the bitrate its plan settled on.
 *
 * The long fixture is scaled up to 1080p by an effect, since a spec height never upscales, so the encoder runs at a
 * size where its own default and the plan's figure sit far apart.
 */
class AndroidBitrateMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aTranscodeWritesNearTheDefaultTiersBitrate() =
    runTest(timeout = TIMEOUT) {
      val plan = probe.plan(fullHd(), ExportSpec(videoCodec = VideoCodec.H264))
      plan.output.size shouldBe Size(1920, 1080)
      val planned = plan.output.bitrate.shouldNotBeNull()
      planned shouldBe resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, plan.output.size, plan.output.frameRate)

      assertNear(videoBitrateOf(probe.export(plan)), planned.bitsPerSecond)
    }

  // media3's own default for 1080p at 30 fps is about 8.7 Mbps, so a file near the preset's 4 Mbps shows the spec
  // reached the encoder.
  @Test
  fun anUploadExportWritesNearItsOwnBitrate() =
    runTest(timeout = TIMEOUT) {
      val plan = probe.plan(fullHd(), ExportSpec.Upload)
      val planned = plan.output.bitrate.shouldNotBeNull()
      planned shouldBe ExportSpec.Upload.bitrate

      assertNear(videoBitrateOf(probe.export(plan)), planned.bitsPerSecond)
    }

  @Test
  fun aBitratePastTheEncodersCeilingIsHeldThere() =
    runTest(timeout = TIMEOUT) {
      val capabilities =
        probe.filmstrip
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      val ceiling = capabilities.encoderFor(VideoCodec.H264)?.maxBitrate.shouldNotBeNull()
      val spec = ExportSpec(bitrate = Bitrate.mbps(2000), videoCodec = VideoCodec.H264)

      val verdict = probe.filmstrip.plan(fullHd(), spec)

      val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
      degraded.adjustments.map { it.kind } shouldContain AdjustmentKind.BitrateClamped
      degraded.plan.output.bitrate shouldBe ceiling
      probe.export(degraded.plan).length() shouldBeGreaterThan 0L
    }

  // Six seconds of the long fixture, the same span the ffmpeg and AVFoundation suites measure, so the first key frame
  // weighs the same in every reading.
  private fun fullHd(): EditComposition =
    EditComposition(
      listOf(
        Track(
          listOf(
            Clip(
              probe.fixture(LONG_CLIP),
              trim = TimeRange.of(Duration.ZERO, 6.seconds),
              effects = listOf(Scale(heightPx = 1080)),
            ),
          ),
        ),
      ),
    )

  private fun assertNear(
    measured: Double,
    planned: Long,
  ) {
    withClue("measured $measured bps against a planned $planned") {
      measured shouldBe (planned.toDouble() plusOrMinus planned * TOLERANCE)
    }
  }

  // The video track's bytes over its duration, read off the samples the muxer wrote rather than any header.
  private fun videoBitrateOf(file: File): Double {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.path)
      val track =
        (0 until extractor.trackCount).first {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
      val durationUs = extractor.getTrackFormat(track).getLong(MediaFormat.KEY_DURATION)
      extractor.selectTrack(track)
      val buffer = ByteBuffer.allocate(SAMPLE_BUFFER)
      var bytes = 0L
      while (true) {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        bytes += size
        extractor.advance()
      }
      return bytes * BITS_PER_BYTE / (durationUs / MICROS_PER_SECOND)
    } finally {
      extractor.release()
    }
  }

  private companion object {
    // How far a measured stream may sit from the planned bitrate, as a fraction of it.
    const val TOLERANCE = 0.15
    const val LONG_CLIP = "android_export_long.mp4"
    const val SAMPLE_BUFFER = 8 * 1024 * 1024
    const val BITS_PER_BYTE = 8.0
    const val MICROS_PER_SECOND = 1_000_000.0
  }
}
