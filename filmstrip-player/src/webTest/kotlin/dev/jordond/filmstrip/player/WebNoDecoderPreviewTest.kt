package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.HeightLowering
import dev.jordond.filmstrip.player.internal.NO_AUDIO_DECODER
import dev.jordond.filmstrip.player.internal.NO_VIDEO_DECODER
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.browserPreviewOf
import dev.jordond.filmstrip.player.internal.thumbnail.BrowserThumbnailPlanner
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.webcodecs.webCodecsBackend
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A browser preview on a page with no `VideoDecoder` or no `AudioDecoder`, which is a real page with that global taken
off it.
 */
class WebNoDecoderPreviewTest {
  private val planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS)
  private val unreadable = EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.Uri("blob:nothing"))))))

  // Nothing can read this source, so anything but the named refusal means a probe ran first.
  @Test
  fun `a page with no decoder refuses by name before any probe`() =
    contractTest {
      withoutGlobals(DECODER) {
        val refused =
          planner
            .plan(unreadable, PreviewQualityPolicy.Full, ExportSpec())
            .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()

        refused.error shouldBe PlaybackError.UnsupportedFormat(NO_VIDEO_DECODER)
      }
    }

  @Test
  fun `the player reports the named refusal as its error`() =
    contractTest {
      withoutGlobals(DECODER) {
        val filmstrip =
          Filmstrip {
            webCodecsBackend()
            playerBackend()
          }
        val player = filmstrip.preview(webFixtureComposition())

        try {
          player.state
            .map { it.status }
            .filterIsInstance<PlaybackStatus.Error>()
            .first()
            .error shouldBe PlaybackError.UnsupportedFormat(NO_VIDEO_DECODER)
        } finally {
          player.close()
        }
      }
    }

  @Test
  fun `an audio only composition still previews with no decoder`() =
    contractTest {
      withoutGlobals(DECODER) {
        val (_, preview) = browserPreviewOf(webFixtureComposition(audio = AudioSpec.AudioOnly))
        try {
          preview.carriesAudio shouldBe true
        } finally {
          preview.release()
        }
      }
    }

  // Nothing can read this source either, so anything but the named limit means a probe ran first.
  @Test
  fun `a thumbnail on a page with no decoder refuses by name before any probe`() =
    contractTest {
      withoutGlobals(DECODER) {
        val request = ThumbnailRequest(unreadable, Duration.ZERO, FIXTURE_FRAME.height, REVISION, precise = true)

        BrowserThumbnailPlanner(CONTRACT_COMPONENTS)
          .lower(request)
          .shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>()
          .error shouldBe FilmstripError.InvalidComposition(NO_VIDEO_DECODER)
      }
    }

  @Test
  fun `a thumbnail on a page with a decoder lowers`() =
    contractTest {
      val request = ThumbnailRequest(webFixtureComposition(), Duration.ZERO, FIXTURE_FRAME.height, REVISION, true)

      BrowserThumbnailPlanner(CONTRACT_COMPONENTS)
        .lower(request)
        .shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>()
    }

  @Test
  fun `an audio only composition with no audio decoder refuses by name`() =
    contractTest {
      withoutGlobals(AUDIO_DECODER) {
        planner
          .plan(webFixtureComposition(audio = AudioSpec.AudioOnly), PreviewQualityPolicy.Full, ExportSpec())
          .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
          .error shouldBe PlaybackError.UnsupportedFormat(NO_AUDIO_DECODER)
      }
    }

  @Test
  fun `a preview on a page with no audio decoder carries no audio`() =
    contractTest {
      withoutGlobals(AUDIO_DECODER) {
        val (_, preview) = browserPreviewOf(webFixtureComposition(audio = AudioSpec.Keep))
        try {
          preview.carriesAudio shouldBe false
          preview.audio() shouldBe null
        } finally {
          preview.release()
        }
      }
    }

  @Test
  fun `a page with no audio decoder plays the video silently`() =
    contractTest { scope ->
      withoutGlobals(AUDIO_DECODER) {
        val engine = BrowserPlayerEngine(scope, planner, PlayerConfig())
        withEngine(engine) { recorder ->
          engine.features.supports(PlayerFeature.AudioMonitoring) shouldBe false
          engine
            .awaitComposition(webFixtureComposition(audio = AudioSpec.Keep))
            .shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the preview to be ready") { recorder.lastState.status == PlaybackStatus.Ready }

          engine.play()

          awaitContract("the playhead to advance") {
            recorder.lastState.isPlaying && (recorder.playhead ?: Duration.ZERO) > Duration.ZERO
          }
        }
      }
    }

  @Test
  fun `a page with a decoder is not refused`() =
    contractTest {
      planner
        .plan(webFixtureComposition(), PreviewQualityPolicy.Full, ExportSpec())
        .shouldBeInstanceOf<PlanResult.Ready<*>>()
    }

  private companion object {
    const val DECODER = "VideoDecoder"
    const val AUDIO_DECODER = "AudioDecoder"
    const val REVISION = 1L
  }
}
