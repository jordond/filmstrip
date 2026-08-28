package dev.jordond.filmstrip.media3

import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

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
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun readsTheTrackRatherThanTheContainer() =
    runTest(timeout = TIMEOUT) {
      val info = probe(CLIP_A) ?: return@runTest
      val video = assertNotNull(info.video, "no video track")

      // A file-level read reports the container's video/mp4 for both tracks, so a codec that tells
      // the two apart is the check that these come off the tracks.
      assertEquals(CodecKind.H264, video.codec.kind, "video codec: ${video.codec}")
      assertEquals(CodecKind.Aac, assertNotNull(info.audio).codec.kind, "audio codec")
    }

  @Test
  fun readsTheFrameRateTheClipWasEncodedAt() =
    runTest(timeout = TIMEOUT) {
      val thirty = probe(CLIP_A) ?: return@runTest
      val twentyFour = probe(CLIP_24FPS) ?: return@runTest

      // The planner falls back to thirty when the source rate is absent, so a single thirty-fps
      // fixture cannot tell a real reading from the fallback.
      assertEquals(30f, thirty.video?.frameRate, "30fps clip")
      assertEquals(24f, twentyFour.video?.frameRate, "24fps clip")
    }

  @Test
  fun readsTheChannelsAndSampleRateOffTheAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val audio = assertNotNull((probe(CLIP_A) ?: return@runTest).audio, "no audio track")

      assertEquals(2, audio.channelCount, "the fixture is stereo")
      assertEquals(48_000, audio.sampleRate, "the fixture is 48kHz")
    }

  @Test
  fun readsTenBitBt2020OffAnHdrClip() =
    runTest(timeout = TIMEOUT) {
      val video = assertNotNull((probe(CLIP_HDR) ?: return@runTest).video, "no video track")

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
      val video = assertNotNull((probe(CLIP_A) ?: return@runTest).video, "no video track")

      assertEquals(8, video.bitDepth, "H.264 High is eight bit")
    }

  private suspend fun probe(name: String): MediaInfo? {
    val stream = javaClass.classLoader?.getResourceAsStream(name) ?: return null
    val file = File(context.cacheDir, name)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(file.path))).info
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val CLIP_A = "android_export_a.mp4"
    const val CLIP_24FPS = "android_export_24fps.mp4"
    const val CLIP_HDR = "android_export_hdr.mp4"
  }
}
