package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.browserPreviewOf
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * A browser preview on a page with no `VideoEncoder` and no `AudioEncoder`, which is a real page with those two globals
 * taken off it.
 */
class WebNoEncoderPreviewTest {
  private val composition = webFixtureComposition(listOf(Brightness(0.2f)), AudioSpec.Keep)

  @Test
  fun `a page with no encoders reports none`() =
    contractTest {
      withoutEncoders {
        val capabilities =
          webCodecsExportEngine(CONTRACT_COMPONENTS)
            .capabilities()
            .shouldBeInstanceOf<CapabilitiesResult.Success>()
            .capabilities

        capabilities.video.shouldBeEmpty()
        capabilities.audio.shouldBeEmpty()
      }
    }

  @Test
  fun `a page with no encoders reaches ready and renders its first frame`() =
    contractTest { scope ->
      withoutEncoders {
        val engine = BrowserPlayerEngine(scope, BrowserPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())
        withEngine(engine) { recorder ->
          engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()

          awaitContract("the preview to be ready") { recorder.lastState.status == PlaybackStatus.Ready }
          awaitContract("the first frame to land") {
            recorder.events.any { it is PlaybackEvent.FirstFrameRendered }
          }
        }
      }
    }

  @Test
  fun `a page with no encoders draws the frame and carries the audio`() =
    contractTest {
      withoutEncoders {
        val (plan, preview) = browserPreviewOf(composition)
        try {
          plan.resolved.path shouldBe ExportPath.Transcode
          plan.resolved.output.audioFormat
            .shouldNotBeNull()
          preview.carriesAudio shouldBe true
          preview.frameAt(PROBE_POSITIONS.first()).shouldNotBeNull()
        } finally {
          preview.release()
        }
      }
    }

  // Export runs on this page and writes no audio, so the preview doesn't promise any.
  @Test
  fun `a page with a video encoder and no audio encoder previews as silent as its export`() =
    contractTest {
      withoutGlobals("AudioEncoder") {
        val (plan, preview) = browserPreviewOf(composition)
        try {
          plan.resolved.output.audioCodec shouldBe AudioCodec.None
          preview.carriesAudio shouldBe false
          preview.frameAt(PROBE_POSITIONS.first()).shouldNotBeNull()
        } finally {
          preview.release()
        }
      }
    }

  private inline fun withoutEncoders(body: () -> Unit) = withoutGlobals("VideoEncoder", "AudioEncoder", body = body)
}
