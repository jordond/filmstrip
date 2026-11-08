package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.BrowserEncoder
import dev.jordond.filmstrip.webcodecs.internal.EncodedFile
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.OfflineAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.muxCodecKey
import dev.jordond.filmstrip.webcodecs.internal.verify
import dev.jordond.filmstrip.webcodecs.internal.verifyAudio
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The encode-and-mux half of the export, fed frames directly and read back through a demuxer that
 * never saw it.
 */
class BrowserEncoderMediaTest {
  @Test
  fun everyFrameAddedReadsBackFromTheFinishedFile() =
    runTest {
      val render = renderOf(videoComposition())
      val encoder = BrowserEncoder.open(render)
      encoder.addFrames()

      val file = encoder.finish()
      val verified = withClue("the finished file did not read back") { file.verify().shouldNotBeNull() }

      withClue("the finished file was empty") { (file.byteLength > 0) shouldBe true }
      verified.decodedFrames shouldBe FRAMES.toLong()
      verified.video?.codec shouldBe muxCodecKey(VideoCodec.H264)
      val expectedUs = FRAMES * render.stepUs
      withClue("the file ran ${verified.durationUs}us") {
        (abs(verified.durationUs - expectedUs) < render.stepUs) shouldBe true
      }
    }

  @Test
  fun aVp9RenderWritesAFileThatReadsBackAsVp9() =
    runTest {
      val render = renderOf(videoComposition(), ExportSpec(videoCodec = VideoCodec.Vp9, audioCodec = AudioCodec.None))
      val encoder = BrowserEncoder.open(render)
      encoder.addFrames()

      val verified = withClue("the finished file did not read back") { encoder.finish().verify().shouldNotBeNull() }

      verified.video?.codec shouldBe muxCodecKey(VideoCodec.Vp9)
      verified.decodedFrames shouldBe FRAMES.toLong()
    }

  // The render names no audio format, so the encoder opened no audio track and a mix handed to it
  // has nowhere to go.
  @Test
  fun aMixHandedToARenderWithNoAudioIsDropped() =
    runTest {
      val render = renderOf(videoComposition())
      render.audioFormat.shouldBeNull()
      val encoder = BrowserEncoder.open(render)
      encoder.addFrames()

      val mix = OfflineAudioContext(2, MIX_FRAMES, SAMPLE_RATE).createBuffer(2, MIX_FRAMES, SAMPLE_RATE)
      encoder.addAudio(mix)
      val verified = encoder.finish().verify().shouldNotBeNull()

      withClue("a render with no audio wrote an audio track") { verified.audio.shouldBeNull() }
    }

  @Test
  fun anAudioOnlyEncoderRefusesAFrame() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val render =
        renderOf(
          videoComposition(audio = AudioSpec.AudioOnly, bytes = makeClipWithAudio(frames = 30)),
          ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac),
        )
      val encoder = BrowserEncoder.open(render)
      val frame = greyFrame(0)

      try {
        val refused = assertFailsWith<IllegalStateException> { encoder.add(frame) }
        refused.message shouldBe "This output writes no video track, so it takes no frames."
      } finally {
        frame.close()
        encoder.cancel()
      }
    }

  @Test
  fun cancellingBeforeAnyFrameIsSafeTwice() =
    runTest {
      val encoder = BrowserEncoder.open(renderOf(videoComposition()))

      encoder.cancel()
      encoder.cancel()
    }

  // Bytes are not proof of a file, so a buffer the demuxer refuses has to fail both read-backs
  // rather than throw out of them.
  @Test
  fun aBufferTheDemuxerRefusesFailsVerification() =
    runTest {
      val junk = EncodedFile(ArrayBuffer(JUNK_BYTES), "video/mp4")

      junk.byteLength shouldBe JUNK_BYTES
      junk.verify().shouldBeNull()
      junk.verifyAudio().shouldBeNull()
    }

  private suspend fun BrowserEncoder.addFrames() {
    repeat(FRAMES) { index ->
      val frame = greyFrame(index)
      try {
        add(frame)
      } finally {
        frame.close()
      }
    }
  }

  private suspend fun videoComposition(
    audio: AudioSpec = AudioSpec.Remove,
    bytes: ByteArray? = null,
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes ?: makeClip(frames = FRAMES)))))),
      audio = audio,
    )

  private companion object {
    const val FRAMES = 6
    const val SAMPLE_RATE = 48_000f
    const val MIX_FRAMES = 4_800
    const val JUNK_BYTES = 64
  }
}
