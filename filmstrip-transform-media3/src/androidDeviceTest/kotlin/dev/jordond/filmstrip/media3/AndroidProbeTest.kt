package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What the prober reports about a track, against clips whose every field is known up front.
 *
 * These are per-track facts, read off the extractor. The fixtures are generated to a spec, so the
 * expected values are that spec, never whatever the device happened to say.
 *
 * Worth running on more than one device. The Fold 7 publishes several per-track keys that stock
 * Android does not, so a reading that only works there passes here and fails in the field.
 */
class AndroidProbeTest {
  private val probe = Media3ExportProbe()

  @Test
  fun readsTheTrackRatherThanTheContainer() =
    runTest(timeout = TIMEOUT) {
      val info = infoOf(CLIP)
      val video = assertNotNull(info.video, "no video track")

      // A file-level read reports the container's video/mp4 for both tracks, so a codec that tells
      // the two apart is the check that these come off the tracks.
      assertEquals(CodecKind.H264, video.codec.kind, "video codec: ${video.codec}")
      assertEquals(CodecKind.Aac, assertNotNull(info.audio).codec.kind, "audio codec")
    }

  @Test
  fun readsTheFrameRateTheClipWasEncodedAt() =
    runTest(timeout = TIMEOUT) {
      val thirty = infoOf(CLIP)
      val twentyFour = infoOf(CLIP_24FPS)

      // The planner falls back to thirty when the source rate is absent, so a single thirty-fps
      // fixture cannot tell a real reading from the fallback.
      assertEquals(30f, thirty.video?.frameRate, "30fps clip")
      assertEquals(24f, twentyFour.video?.frameRate, "24fps clip")
    }

  @Test
  fun readsTheChannelsAndSampleRateOffTheAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val audio = assertNotNull(infoOf(CLIP).audio, "no audio track")

      assertEquals(2, audio.channelCount, "the fixture is stereo")
      assertEquals(48_000, audio.sampleRate, "the fixture is 48kHz")
    }

  @Test
  fun readsTenBitBt2020OffAnHdrClip() =
    runTest(timeout = TIMEOUT) {
      val video = assertNotNull(infoOf(HDR_CLIP).video, "no video track")

      assertEquals(CodecKind.Hevc, video.codec.kind)
      assertEquals(10, video.bitDepth, "the fixture is ten bit")
      assertEquals(ColorSpace.Bt2020, video.colorSpace)
      assertEquals(HdrTransfer.Pq, video.hdrTransfer)
    }

  // Eight bit is a reading here, not a default. It comes from the profile, and the ladder
  // returns null when nothing in the track says.
  @Test
  fun readsEightBitOffAnSdrClip() =
    runTest(timeout = TIMEOUT) {
      val video = assertNotNull(infoOf(CLIP).video, "no video track")

      assertEquals(8, video.bitDepth, "H.264 High is eight bit")
    }

  @Test
  fun readsABufferTheSameWayItReadsTheFileItCameFrom() =
    runTest(timeout = TIMEOUT) {
      val bytes = bytesOf(CLIP)
      val fromFile = infoOf(CLIP)

      val fromBytes = probe.infoOf(MediaSource.ofBytes(bytes, FormatHint.Mp4))

      // The buffer is written to the cache and probed as the file it landed on, so the two readings
      // are of the same container and every field has to agree.
      assertEquals(fromFile.duration, fromBytes.duration, "duration")
      assertEquals(fromFile.video?.codec?.kind, fromBytes.video?.codec?.kind, "video codec")
      assertEquals(fromFile.video?.displaySize, fromBytes.video?.displaySize, "frame")
      assertEquals(fromFile.video?.frameRate, fromBytes.video?.frameRate, "frame rate")
      assertEquals(fromFile.audio?.codec?.kind, fromBytes.audio?.codec?.kind, "audio codec")
      assertEquals(fromFile.audio?.sampleRate, fromBytes.audio?.sampleRate, "sample rate")
    }

  @Test
  fun readsAnUnhintedBufferBySniffingIt() =
    runTest(timeout = TIMEOUT) {
      val bytes = bytesOf(CLIP)

      // Nothing names the container, so the file is written under a name that claims nothing and
      // the retriever has to work it out from the bytes.
      val info = probe.infoOf(MediaSource.ofBytes(bytes))

      assertEquals(CodecKind.H264, assertNotNull(info.video, "no video track").codec.kind)
    }

  private fun bytesOf(name: String): ByteArray = probe.fixtureFile(name).readBytes()

  private suspend fun infoOf(name: String): MediaInfo = probe.infoOf(probe.fixture(name))

  private companion object {
    const val CLIP_24FPS = "android_export_24fps.mp4"
  }
}
