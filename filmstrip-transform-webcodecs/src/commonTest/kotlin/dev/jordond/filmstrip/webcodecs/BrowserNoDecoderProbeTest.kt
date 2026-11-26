package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.UnreadableCase
import dev.jordond.filmstrip.test.withVideoCodec
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.measureTimedValue

/**
 * Probes clips whose video codec this browser has no decoder for and checks each is refused with the error every
 * prober gives, while such a clip still plans for its audio alone and whole H.264 and HEVC clips still probe.
 *
 * Whether a browser has an HEVC decoder to start depends on its hardware, and the default test browser draws through
 * SwiftShader with none, so the HEVC clip passes either as a success or as a missing decoder but never as damaged.
 */
class BrowserNoDecoderProbeTest {
  @Test
  fun aCodecNoPlatformKnowsIsRefused() =
    runTest {
      val bytes = UnreadableCase.UnknownCodec.bytesFrom(makeClip()).shouldNotBeNull()
      val source = MediaSource.ofBytes(bytes, FormatHint.Mp4)
      val refusal = UnreadableCase.UnknownCodec.refusalAt(source, bytes)

      val error = probeFailure(source).shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()

      error.message shouldBe refusal
      error.codec shouldBe "unknown"
    }

  @Test
  fun aProResClipIsRefused() =
    runTest {
      val source = MediaSource.ofBytes(withVideoCodec(makeClip(), PRORES), FormatHint.Mp4)

      val error = probeFailure(source).shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()

      error.codec shouldBe "prores"
    }

  @Test
  fun aProResClipPlansForItsAudioAlone() =
    runTest {
      val source = MediaSource.ofBytes(withVideoCodec(makeClipWithAudio(), PRORES), FormatHint.Mp4)
      val filmstrip = Filmstrip { webCodecsBackend() }

      val audioOnly = filmstrip.plan(compositionOf(source, AudioSpec.AudioOnly), ExportSpec())
      val withVideo = filmstrip.plan(compositionOf(source, AudioSpec.Keep), ExportSpec())

      audioOnly.shouldNotBeInstanceOf<Verdict.Incapable>()
      withVideo
        .shouldBeInstanceOf<Verdict.Incapable>()
        .reasons
        .single()
        .shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
    }

  @Test
  fun aWholeH264ClipStillProbes() =
    runTest {
      probedCodec(MediaSource.ofBytes(makeClip(), FormatHint.Mp4)) shouldBe CodecKind.H264
    }

  @Test
  fun aWholeHevcClipIsNeverCalledDamaged() =
    runTest {
      val (probed, took) = measureTimedValue { BrowserProber().probe(MediaSource.Uri(HEVC_FIXTURE)) }
      println("hevc probe took $took: $probed")

      when (probed) {
        is ProbeResult.Success -> {
          probed.info.video
            ?.codec
            ?.kind shouldBe CodecKind.Hevc
        }
        is ProbeResult.Failure -> {
          probed.error.shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
        }
      }
    }

  private fun compositionOf(
    source: MediaSource,
    audio: AudioSpec,
  ): Composition = Composition(tracks = listOf(Track(listOf(Clip(source)))), audio = audio)

  private suspend fun probeFailure(source: MediaSource): FilmstripError {
    val (probed, took) = measureTimedValue { BrowserProber().probe(source) }
    println("no decoder probe took $took")
    return probed.shouldBeInstanceOf<ProbeResult.Failure>().error
  }

  private suspend fun probedCodec(source: MediaSource): CodecKind {
    val (probed, took) = measureTimedValue { BrowserProber().probe(source) }
    println("whole probe took $took")
    return probed
      .shouldBeInstanceOf<ProbeResult.Success>()
      .info.video
      .shouldNotBeNull()
      .codec.kind
  }

  private companion object {
    const val PRORES = "apcn"

    // Ten-bit HEVC tagged hvc1, written by ffmpeg rather than by this browser.
    const val HEVC_FIXTURE = "/base/kotlin/export_hdr.mp4"
  }
}
