package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.webcodecs.internal.browserCanRenderFloat
import dev.jordond.filmstrip.webcodecs.internal.browserEncodesHdrVp9
import dev.jordond.filmstrip.webcodecs.internal.encoderSupports
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.renderCapabilities
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The two facade entry points the export tests never reach, driven in a real browser.
 *
 * Everything else here plans or exports, which needs a clip. These two answer before there is one.
 */
class BrowserCapabilitiesTest {
  @Test
  fun capabilitiesComeFromTheBrowser() =
    runTest {
      val result = filmstrip().capabilities()
      val capabilities =
        withClue("capabilities failed: $result") {
          result.shouldBeInstanceOf<CapabilitiesResult.Success>()
        }.capabilities

      withClue("a browser with WebCodecs reported no video encoder") { capabilities.video.isNotEmpty() shouldBe true }
      withClue(
        "VP9 is the codec every browser encoder reports, and this one reported ${capabilities.video.map { it.codec }}",
      ) {
        capabilities.video.any { it.codec == VideoCodec.Vp9 } shouldBe true
      }
    }

  // VP9 Profile 2, vp09.02.10.10, is the only HDR profile any browser encoder here reports. HEVC
  // Main10, H.264 High10 and AV1 10-bit all read supported=false. Claiming it needs the compositor
  // as well as the encoder, so the claim is checked against the same two questions the probe asks
  // rather than pinned true: Firefox and Safari answer them differently.
  @Test
  fun hdrEncodingIsClaimedWhenTheEncoderAndTheFloatFramebufferAreBothThere() =
    runTest {
      val result = filmstrip().capabilities()
      val capabilities =
        withClue("capabilities failed: $result") {
          result.shouldBeInstanceOf<CapabilitiesResult.Success>()
        }.capabilities

      withClue("the HDR claim and what this browser can actually do parted ways") {
        capabilities.supportsHdrEncoding shouldBe (browserCanRenderFloat() && browserEncodesHdrVp9())
      }
    }

  // The job is cancelled before the encoder is asked, so the probe's await is the first thing to see it.
  @Test
  fun aCancelledEncoderProbeCancelsRatherThanAnsweringNo() =
    runTest {
      var answered: Boolean? = null
      val job =
        launch {
          cancel()
          answered = encoderSupports(VP9_PROBE, Size(PROBE_SIDE, PROBE_SIDE))
        }
      job.join()

      withClue("a cancelled probe answered instead of cancelling") { answered.shouldBeNull() }
    }

  // Read from a context of the test's own, so the planner's figure is checked against the page rather than itself.
  @Test
  fun theLongestTextureSideIsTheOneThisPageReports() {
    val gl =
      withClue("no WebGL2 context") {
        OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()).shouldNotBeNull()
      }
    val reported =
      try {
        gl.getParameter(MAX_TEXTURE_SIZE)
      } finally {
        gl.getExtension("WEBGL_lose_context")?.loseContext()
      }

    renderCapabilities(encodesHdr = false).maxTextureSize shouldBe reported
    renderCapabilities(encodesHdr = true).maxTextureSize shouldBe reported
  }

  @Test
  fun anUnreadableUriIsRefusedRatherThanThrown() =
    runTest {
      val result = filmstrip().probe(MediaSource.Uri("blob:nothing"))
      withClue("the browser prober claimed to have read a blob that does not exist") {
        result.shouldBeInstanceOf<ProbeResult.Failure>()
      }
    }

  private fun filmstrip(): Filmstrip = Filmstrip { webCodecsBackend() }

  private companion object {
    const val MAX_TEXTURE_SIZE = 0x0D33
    const val VP9_PROBE = "vp09.00.10.08"
    const val PROBE_SIDE = 640
  }
}
