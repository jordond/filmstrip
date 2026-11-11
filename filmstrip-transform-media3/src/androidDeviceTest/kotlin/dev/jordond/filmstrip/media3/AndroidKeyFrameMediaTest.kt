package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.transformer.EncoderSelector
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where a media3 export writes its key frames against the spacing its plan settled on.
 *
 * `OMX.google.h264.encoder` writes an I frame at the spacing and an IDR only every 1000 frames, so on a device whose
 * only H.264 encoder it is, an API 30 emulator for one, a file this short holds a key frame on its first frame alone.
 */
class AndroidKeyFrameMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aTranscodeWritesAKeyFrameEverySecondByDefault() =
    runTest(timeout = TIMEOUT) {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264))
    }

  @Test
  fun aHalfSecondIntervalWritesAKeyFrameEveryHalfSecond() =
    runTest(timeout = TIMEOUT) {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264, keyFrameInterval = 500.milliseconds))
    }

  // media3 takes the spacing in seconds, so a count that is no round number of seconds at the output rate shows
  // whether the encoder turns it back into the same frames.
  @Test
  fun anIntervalBetweenTwoFramesWritesTheSharedCount() =
    runTest(timeout = TIMEOUT) {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264, frameRate = 25, keyFrameInterval = 500.milliseconds))
    }

  @Test
  fun aCopyKeepsTheSourcesOwnKeyFrames() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixtureFile(LONG_CLIP)
      val whole = Composition(listOf(Track(listOf(Clip(probe.fixture(LONG_CLIP))))))

      val plan = probe.plan(whole, ExportSpec(keyFrameInterval = 100.milliseconds))

      plan.path shouldBe ExportPath.Copy
      plan.output.keyFrameSpacing.shouldBeNull()
      keyFramesOf(probe.export(plan)) shouldBe keyFramesOf(source)
    }

  private suspend fun assertSpacing(spec: ExportSpec) {
    val plan = probe.plan(threeSeconds(), spec)
    val spacing = keyFrameIntervalOf(spec, plan.output.frameRate)
    plan.output.keyFrameSpacing shouldBe spacing

    val (keyFrames, frames) = keyFramesOf(probe.export(plan))

    frames shouldBeGreaterThan spacing * 2
    val expected = if (encodesWithOmxAvcOnly()) listOf(0) else (0 until frames step spacing).toList()
    withClue("key frames at $keyFrames of $frames frames, planned every $spacing") {
      keyFrames shouldBe expected
    }
  }

  private fun encodesWithOmxAvcOnly(): Boolean {
    val encoders = EncoderSelector.DEFAULT.selectEncoderInfos(MimeTypes.VIDEO_H264)
    return encoders.isNotEmpty() && encoders.all { it.name == OMX_AVC }
  }

  private fun threeSeconds(): Composition =
    Composition(listOf(Track(listOf(Clip(probe.fixture(LONG_CLIP), trim = TimeRange.of(Duration.ZERO, 3.seconds))))))

  // The index of every sync sample on the video track in the order the file stores them, and how many samples the
  // track holds.
  private fun keyFramesOf(file: File): Pair<List<Int>, Int> {
    val extractor = MediaExtractorCompat()
    try {
      extractor.setDataSource(Uri.fromFile(file), 0L)
      val track =
        (0 until extractor.trackCount).first {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
      extractor.selectTrack(track)
      val keyFrames = mutableListOf<Int>()
      var frames = 0
      while (extractor.sampleTime >= 0) {
        if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) keyFrames += frames
        frames++
        extractor.advance()
      }
      return keyFrames to frames
    } finally {
      extractor.release()
    }
  }

  private companion object {
    const val LONG_CLIP = "android_export_long.mp4"
    const val OMX_AVC = "OMX.google.h264.encoder"
  }
}
