package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.webcodecs.internal.browserCanRenderFloat
import dev.jordond.filmstrip.webcodecs.internal.browserEncodesHdrVp9
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
      val capabilities = assertIs<CapabilitiesResult.Success>(result, "capabilities failed: $result").capabilities

      assertTrue(capabilities.video.isNotEmpty(), "a browser with WebCodecs reported no video encoder")
      assertTrue(
        capabilities.video.any { it.codec == VideoCodec.Vp9 },
        "VP9 is the codec every browser encoder reports, and this one reported ${capabilities.video.map { it.codec }}",
      )
    }

  // VP9 Profile 2, vp09.02.10.10, is the only HDR profile any browser encoder here reports. HEVC
  // Main10, H.264 High10 and AV1 10-bit all read supported=false. Claiming it needs the compositor
  // as well as the encoder, so the claim is checked against the same two questions the probe asks
  // rather than pinned true: Firefox and Safari answer them differently.
  @Test
  fun hdrEncodingIsClaimedWhenTheEncoderAndTheFloatFramebufferAreBothThere() =
    runTest {
      val result = filmstrip().capabilities()
      val capabilities = assertIs<CapabilitiesResult.Success>(result, "capabilities failed: $result").capabilities

      assertEquals(
        browserCanRenderFloat() && browserEncodesHdrVp9(),
        capabilities.supportsHdrEncoding,
        "the HDR claim and what this browser can actually do parted ways",
      )
    }

  @Test
  fun anUnreadableUriIsRefusedRatherThanThrown() =
    runTest {
      val result = filmstrip().probe(MediaSource.Uri("blob:nothing"))
      assertIs<ProbeResult.Failure>(result, "the browser prober claimed to have read a blob that does not exist")
    }

  private fun filmstrip(): Filmstrip = Filmstrip { webCodecsBackend() }
}
