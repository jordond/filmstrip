package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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

  private suspend fun probe(name: String): MediaInfo =
    filmstrip.probe(MediaSource.of(fixture(name))).shouldBeInstanceOf<ProbeResult.Success>().info

  private companion object {
    val TIMEOUT = 5.minutes

    const val SDR_FIXTURE = "apple_export_a.mp4"
  }
}
