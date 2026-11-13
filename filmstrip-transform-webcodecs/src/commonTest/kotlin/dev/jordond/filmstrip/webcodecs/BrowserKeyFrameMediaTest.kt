@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.copyPacketsFrom
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacketSink
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where a browser export writes its key frames against the spacing its plan settled on.
 */
class BrowserKeyFrameMediaTest {
  @Test
  fun aTranscodeWritesAKeyFrameEverySecondByDefault() =
    runTest {
      assertSpacing(ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None))
    }

  @Test
  fun aHalfSecondIntervalWritesAKeyFrameEveryHalfSecond() =
    runTest {
      assertSpacing(
        ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, keyFrameInterval = 500.milliseconds),
      )
    }

  @Test
  fun anIntervalBetweenTwoFramesRoundsToTheSharedCount() =
    runTest {
      assertSpacing(
        ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, keyFrameInterval = 450.milliseconds),
      )
    }

  @Test
  fun aCopyKeepsTheSourcesOwnKeyFrames() =
    runTest {
      val source =
        MediaSource.Bytes(
          makeClip(frames = FRAMES, keyFrameSeconds = SOURCE_KEY_FRAME_SECONDS) { _, _ -> Rgb.Grey },
        )
      val composition = Composition(listOf(Track(listOf(Clip(source)))))
      val spec = ExportSpec(keyFrameInterval = 100.milliseconds)
      val filmstrip = webCodecsFilmstrip()

      val plan = planOf(filmstrip, composition, spec)
      plan.path shouldBe ExportPath.Copy
      plan.output.keyFrameSpacing.shouldBeNull()

      keyFramesOf(outputOf(exportOf(composition, MediaSink.Uri(""), spec))) shouldBe keyFramesOf(source)
    }

  private suspend fun assertSpacing(spec: ExportSpec) {
    val composition = Composition(listOf(Track(listOf(Clip(MediaSource.Bytes(makeClip(frames = FRAMES)))))))
    val plan = planOf(webCodecsFilmstrip(), composition, spec)
    val spacing = keyFrameIntervalOf(spec, plan.output.frameRate)
    plan.path shouldBe ExportPath.Transcode
    plan.output.keyFrameSpacing shouldBe spacing

    val (keyFrames, frames) = keyFramesOf(outputOf(exportOf(composition, MediaSink.Uri(""), spec)))

    frames shouldBe FRAMES
    withClue("key frames at $keyFrames of $frames frames, planned every $spacing") {
      keyFrames shouldBe (0 until frames step spacing).toList()
    }
  }

  // The index of every key packet on the video track in the order the frames are shown, and how many packets the
  // track holds.
  private suspend fun keyFramesOf(source: MediaSource): Pair<List<Int>, Int> {
    val reader = SourceReader.of(source) ?: error("the decoder could not open $source")
    try {
      val track = reader.videoTrack() ?: error("the source has no video track")
      val packets = EncodedPacketSink(track).copyPacketsFrom(null, Double.MAX_VALUE, Int.MAX_VALUE).sortedBy { it.pts }
      return packets.indices.filter { packets[it].key } to packets.size
    } finally {
      reader.close()
    }
  }

  private companion object {
    // Three seconds at the fixture's 30 fps.
    const val FRAMES = 90
    const val SOURCE_KEY_FRAME_SECONDS = 0.7
  }
}
