package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.internal.platformProber
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.NEUTRAL_CONTAINER_EXTENSION
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.containerExtensionOf
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ThreeAudioGapped
import dev.jordond.filmstrip.test.ThreeAudioMatroska
import dev.jordond.filmstrip.test.ThreeAudioTs
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test

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
      val video = withClue("no video track") { info.video.shouldNotBeNull() }

      // A file-level read reports the container's video/mp4 for both tracks, so a codec that tells
      // the two apart is the check that these come off the tracks.
      withClue("video codec: ${video.codec}") { video.codec.kind shouldBe CodecKind.H264 }
      withClue("audio codec") {
        info.audio
          .shouldNotBeNull()
          .codec.kind shouldBe CodecKind.Aac
      }
    }

  @Test
  fun readsTheFrameRateTheClipWasEncodedAt() =
    runTest(timeout = TIMEOUT) {
      val thirty = infoOf(CLIP)
      val twentyFour = infoOf(CLIP_24FPS)

      // The planner falls back to thirty when the source rate is absent, so a single thirty-fps
      // fixture cannot tell a real reading from the fallback.
      withClue("30fps clip") { thirty.video?.frameRate shouldBe 30f }
      withClue("24fps clip") { twentyFour.video?.frameRate shouldBe 24f }
    }

  @Test
  fun readsTheChannelsAndSampleRateOffTheAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val audio = withClue("no audio track") { infoOf(CLIP).audio.shouldNotBeNull() }

      withClue("the fixture is stereo") { audio.channelCount shouldBe 2 }
      withClue("the fixture is 48kHz") { audio.sampleRate shouldBe 48_000 }
    }

  @Test
  fun readsTenBitBt2020OffAnHdrClip() =
    runTest(timeout = TIMEOUT) {
      val video = withClue("no video track") { infoOf(HDR_CLIP).video.shouldNotBeNull() }

      video.codec.kind shouldBe CodecKind.Hevc
      withClue("the fixture is ten bit") { video.bitDepth shouldBe 10 }
      video.colorSpace shouldBe ColorSpace.Bt2020
      video.hdrTransfer shouldBe HdrTransfer.Pq
    }

  // Eight bit is a reading here, not a default. It comes from the profile, and the ladder
  // returns null when nothing in the track says.
  @Test
  fun readsEightBitOffAnSdrClip() =
    runTest(timeout = TIMEOUT) {
      val video = withClue("no video track") { infoOf(CLIP).video.shouldNotBeNull() }

      withClue("H.264 High is eight bit") { video.bitDepth shouldBe 8 }
    }

  @Test
  fun readsABufferTheSameWayItReadsTheFileItCameFrom() =
    runTest(timeout = TIMEOUT) {
      val bytes = bytesOf(CLIP)
      val fromFile = infoOf(CLIP)

      val fromBytes = probe.infoOf(MediaSource.ofBytes(bytes, FormatHint.Mp4))

      // The buffer is written to the cache and probed as the file it landed on, so the two readings
      // are of the same container and every field has to agree.
      withClue("duration") { fromBytes.duration shouldBe fromFile.duration }
      withClue("video codec") { fromBytes.video?.codec?.kind shouldBe fromFile.video?.codec?.kind }
      withClue("frame") { fromBytes.video?.displaySize shouldBe fromFile.video?.displaySize }
      withClue("frame rate") { fromBytes.video?.frameRate shouldBe fromFile.video?.frameRate }
      withClue("audio codec") { fromBytes.audio?.codec?.kind shouldBe fromFile.audio?.codec?.kind }
      withClue("sample rate") { fromBytes.audio?.sampleRate shouldBe fromFile.audio?.sampleRate }
    }

  @Test
  fun readsAnUnhintedBufferBySniffingIt() =
    runTest(timeout = TIMEOUT) {
      val bytes = bytesOf(CLIP)

      // Nothing names the container, so the file is written under a name that claims nothing and
      // the retriever has to work it out from the bytes.
      val info = probe.infoOf(MediaSource.ofBytes(bytes))

      withClue("no video track") { info.video.shouldNotBeNull() }.codec.kind shouldBe CodecKind.H264
    }

  // AMR has no magic of its own in the shared function, so it lands under the neutral name and the retriever sniffs it.
  @Test
  fun readsABufferUnderTheNeutralName() =
    runTest(timeout = TIMEOUT) {
      val bytes = silentAmr()

      containerExtensionOf(bytes) shouldBe NEUTRAL_CONTAINER_EXTENSION
      val audio = probe.infoOf(MediaSource.ofBytes(bytes)).audio

      withClue("no audio track") { audio.shouldNotBeNull() }.sampleRate shouldBe AMR_SAMPLE_RATE
    }

  // The ids 5, 9 and 12 are no track's place, so they can only come off the track headers.
  @Test
  fun namesTheTracksOfAGappedFixtureByTheirTrackHeaders() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(ThreeAudioGapped.fileName)

      withClue("core's platform prober") {
        ThreeAudioGapped.assertProbed(platformProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info)
      }
      withClue("the media3 prober") { ThreeAudioGapped.assertProbed(probe.infoOf(source)) }
    }

  @Test
  fun listsEveryAudioTrackWithItsIdLanguageAndDefaultFlag() =
    runTest(timeout = TIMEOUT) {
      ThreeAudio.assertProbed(infoOf(ThreeAudio.FILE_NAME))
    }

  @Test
  fun readsAMatroskaFilesTracksByTheirPlaceAndItsOwnDefaultFlag() =
    runTest(timeout = TIMEOUT) {
      ThreeAudioMatroska.assertProbed(infoOf(ThreeAudioMatroska.fileName))
    }

  @Test
  fun readsATransportStreamsTracksByTheirPlace() =
    runTest(timeout = TIMEOUT) {
      ThreeAudioTs.assertProbed(infoOf(ThreeAudioTs.fileName))
    }

  // The platform prober and media3 each open the packed range, and the track headers have to come from that range too.
  @Test
  fun readsTheTrackHeadersOfAnMp4PackedInsideALargerFile() =
    runTest(timeout = TIMEOUT) {
      probe.fixtureFile(ThreeAudio.FILE_NAME)
      val packed = MediaSource.ofUri(PackedFixtureProvider.uriOf(ThreeAudio.FILE_NAME))

      withClue("core's platform prober") {
        ThreeAudio.assertProbed(platformProber().probe(packed).shouldBeInstanceOf<ProbeResult.Success>().info)
      }
      withClue("the media3 prober") { ThreeAudio.assertProbed(probe.infoOf(packed)) }
    }

  private fun bytesOf(name: String): ByteArray = probe.fixtureFile(name).readBytes()

  // Android's decoders hand a video back opaque and the extractor names no alpha track, so a VP9 WebM tagged with alpha
  // reads as opaque and the planner never expects a fill to show through it.
  @Test
  fun readsAVp9WebmWithAlphaAsOpaque() =
    runTest(timeout = TIMEOUT) {
      val info = probe.infoOf(MediaSource.Bytes(Base64.decode(ALPHA_WEBM)))

      withClue("the fixture is a VP9 track") {
        info.video
          .shouldNotBeNull()
          .codec.kind shouldBe CodecKind.Vp9
      }
      withClue("alpha the platform cannot decode is dropped") { info.video?.hasAlpha shouldBe false }
    }

  private suspend fun infoOf(name: String): MediaInfo = probe.infoOf(probe.fixture(name))

  private companion object {
    const val CLIP_24FPS = "android_export_24fps.mp4"
  }
}

/**
 * One second of AMR silence, which is short enough to spell out here and holds no box a sniffer could take for ISO.
 */
internal fun silentAmr(): ByteArray {
  val frame = ByteArray(AMR_FRAME_BYTES).also { it[0] = AMR_12K2_FRAME_HEADER }
  return "#!AMR\n".encodeToByteArray() + ByteArray(AMR_FRAME_BYTES * AMR_FRAMES_PER_SECOND) { frame[it % frame.size] }
}

private const val AMR_SAMPLE_RATE = 8_000
private const val AMR_FRAME_BYTES = 32
private const val AMR_FRAMES_PER_SECOND = 50
private const val AMR_12K2_FRAME_HEADER: Byte = 0x3C

// A 64 by 64 VP9 WebM of one half transparent frame, written by libvpx with its alpha plane kept.
private const val ALPHA_WEBM =
  "GkXfo59ChoEBQveBAULygQRC84EIQoKEd2VibUKHgQJChYECGFOAZwEAAAAAAAMrEU2bdLpNu4tTq4QVSalmU6yBoU27i1OrhBZU" +
    "rmtTrIHWTbuMU6uEElTDZ1OsggE2TbuMU6uEHFO7a1OsggMV7AEAAAAAAABZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVSalmsCrXsYMPQkBNgIxM" +
    "YXZmNjMuMS4xMDJXQYxMYXZmNjMuMS4xMDJEiYhAaQAAAAAAABZUrmvbrgEAAAAAAABS14EBc8WIzLtOl87j/oqcgQAitZyDdW5k" +
    "iIEAhoVWX1ZQOYOBASPjg4QB/KBV4JSwgUC6gUCagQJTwIEBVbCEVbmBAVXugQHsAQAAAAAAAAIAABJUw2f+c3OfY8CAZ8iZRaOH" +
    "RU5DT0RFUkSHjExhdmY2My4xLjEwMnNz2WPAi2PFiMy7TpfO4/6KZ8ikRaOHRU5DT0RFUkSHl0xhdmM2My4xLjEwMiBsaWJ2cHgt" +
    "dnA5Z8ihRaOIRFVSQVRJT05Eh5MwMDowMDowMC4yMDAwMDAwMDAAH0O2dUFW54EAoNKhroEAAACCSYNCAAPwA/YAOCQcGEIAADBg" +
    "AAATv//3FNgAEf///+qHH/////OIAAB1oZ+mne6BAaWYgkmDQgAD8AP2ADgkHBhCAAAwYAAATgAAoLGhk4EAIQCGAECSnABJQAAD" +
    "IAAAVHB1oZamlO6BAaWPhgBAkpwASUAAAyAAAFRw+4HfoLGhk4EAQwCGAECSnABKwAADIAAAVHB1oZamlO6BAaWPhgBAkpwASsAA" +
    "AyAAAFRw+4HeoLGhk4EAZACGAECSnABJwAADIAAAVHB1oZamlO6BAaWPhgBAkpwAScAAAyAAAFRw+4HfoLGhk4EAhQCGAECSnABI" +
    "oAADIAAAVHB1oZamlO6BAaWPhgBAkpwASKAAAyAAAFRw+4HfoLGhk4EApwCGAECSnABHgAADIAAAVHB1oZamlO6BAaWPhgBAkpwA" +
    "R4AAAyAAAFRw+4HeHFO7a5G7j7OBALeK94EB8YIBufCBAw=="
