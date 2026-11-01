package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * What the prober reports about a track, read off the track's format description.
 *
 * The fixtures are generated to a spec, so the expected values are that spec, never whatever the
 * platform happened to say.
 *
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
class AppleProbeTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `reads ten bit BT2020 PQ off an HDR clip`() =
    runTest(timeout = TIMEOUT) {
      val video = assertNotNull(probe(HDR_FIXTURE).video, "no video track")

      assertEquals(CodecKind.Hevc, video.codec.kind, "codec: ${video.codec}")
      assertEquals("hvc1", video.codec.name, "AVFoundation names a codec by its four-character code")
      assertEquals(10, video.bitDepth, "the fixture is ten bit")
      assertEquals(ColorSpace.Bt2020, video.colorSpace)
      assertEquals(HdrTransfer.Pq, video.hdrTransfer)
    }

  // CoreMedia reads the depth out of the HEVC configuration record and has no equivalent for
  // H.264, so null here means the track does not say, not that nothing looked.
  @Test
  fun `says nothing about the depth of an H264 clip`() =
    runTest(timeout = TIMEOUT) {
      val video = assertNotNull(probe(SDR_FIXTURE).video, "no video track")

      assertEquals(CodecKind.H264, video.codec.kind)
      assertNull(video.bitDepth, "H.264 carries no depth CoreMedia will report")
    }

  @Test
  fun `reads the channels and sample rate off the audio track`() =
    runTest(timeout = TIMEOUT) {
      val audio = assertNotNull(probe(SDR_FIXTURE).audio, "no audio track")

      assertEquals(CodecKind.Aac, audio.codec.kind, "codec: ${audio.codec}")
      assertEquals(2, audio.channelCount, "the fixture is stereo")
      assertEquals(48_000, audio.sampleRate, "the fixture is 48kHz")
    }

  private suspend fun probe(name: String): MediaInfo =
    assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(fixture(name)))).info

  private companion object {
    val TIMEOUT = 5.minutes

    const val SDR_FIXTURE = "apple_export_a.mp4"
  }
}
