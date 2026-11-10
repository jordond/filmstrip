package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioMatroska
import dev.jordond.filmstrip.test.ThreeAudioTs
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * What the prober reports about a track, read off the track's format description.
 *
 * The fixtures are generated to a spec, so the expected values are that spec, never whatever the
 * platform happened to say.
 *
 * A case fails when its fixture is missing, as in [AppleExportHardwareTest].
 */
@OptIn(ExperimentalNativeApi::class)
class AppleProbeTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `reads ten bit BT2020 PQ off an HDR clip`() =
    runTest(timeout = TIMEOUT) {
      val video = probe(HDR_FIXTURE).video.shouldNotBeNull()

      withClue("codec: ${video.codec}") { video.codec.kind shouldBe CodecKind.Hevc }
      withClue("AVFoundation names a codec by its four-character code") { video.codec.name shouldBe "hvc1" }
      video.bitDepth shouldBe 10
      video.colorSpace shouldBe ColorSpace.Bt2020
      video.hdrTransfer shouldBe HdrTransfer.Pq
    }

  // CoreMedia reads the depth out of the HEVC configuration record and has no equivalent for
  // H.264, so null here means the track does not say, not that nothing looked.
  @Test
  fun `says nothing about the depth of an H264 clip`() =
    runTest(timeout = TIMEOUT) {
      val video = probe(SDR_FIXTURE).video.shouldNotBeNull()

      video.codec.kind shouldBe CodecKind.H264
      withClue("H.264 carries no depth CoreMedia will report") { video.bitDepth.shouldBeNull() }
    }

  @Test
  fun `reads the channels and sample rate off the audio track`() =
    runTest(timeout = TIMEOUT) {
      val audio = probe(SDR_FIXTURE).audio.shouldNotBeNull()

      withClue("codec: ${audio.codec}") { audio.codec.kind shouldBe CodecKind.Aac }
      audio.channelCount shouldBe 2
      audio.sampleRate shouldBe 48_000
    }

  @Test
  fun `lists every audio track with its id language and default flag`() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.assertProbed(probe(ThreeAudio.FILE_NAME))
    }

  // AVFoundation reads no language off a transport stream, so only the ids, their order and the default are compared.
  // On iOS it opens no transport stream at all, which is that platform's limit.
  @Test
  fun `names the tracks of a transport stream by their place`() =
    runTest(timeout = TIMEOUT) {
      val result = filmstrip.probe(MediaSource.of(fixture(ThreeAudioTs.fileName)))

      if (Platform.osFamily == OsFamily.IOS) {
        result.shouldBeInstanceOf<ProbeResult.Failure>().error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      } else {
        ThreeAudioTs.assertProbed(result.shouldBeInstanceOf<ProbeResult.Success>().info, readsLanguage = false)
      }
    }

  // Nothing but AVFoundation reads a file it streams, so the track ids come from what AVFoundation says the file is.
  @Test
  fun `names the tracks of an mp4 it streams by their track ids`() =
    runTest(timeout = TIMEOUT) {
      RangeServer(readBytes(fixture(ThreeAudio.FILE_NAME)), ThreeAudio.FILE_NAME).use { server ->
        val info = filmstrip.probe(MediaSource.ofUri(server.url)).shouldBeInstanceOf<ProbeResult.Success>().info

        info.audioTracks.map { it.id } shouldBe ThreeAudio.tracks.map { it.id }
      }
    }

  // AVFoundation opens no Matroska file, so the other backends' reading of its default flag has no Apple side.
  @Test
  fun `refuses a matroska file as unreadable`() =
    runTest(timeout = TIMEOUT) {
      val result = filmstrip.probe(MediaSource.of(fixture(ThreeAudioMatroska.fileName)))

      result.shouldBeInstanceOf<ProbeResult.Failure>().error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    }

  private suspend fun probe(name: String): MediaInfo =
    filmstrip.probe(MediaSource.of(fixture(name))).shouldBeInstanceOf<ProbeResult.Success>().info

  private companion object {
    val TIMEOUT = 5.minutes

    const val SDR_FIXTURE = "apple_export_a.mp4"
  }
}
