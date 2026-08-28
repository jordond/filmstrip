package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
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

  // Chromium 151 was measured to encode VP9 Profile 2, vp09.02.10.10, and it is the only HDR
  // profile any browser encoder here reports. HEVC Main10, H.264 High10 and AV1 10-bit all read
  // supported=false. The encoder taking a grade is not the same as this backend being able to hand
  // it one: the WebGL pass reads back through an 8-bit canvas, so HDR is not claimed yet. Flip this
  // with the compositor.
  @Test
  fun hdrEncodingIsNotClaimedWhileTheCompositorIsEightBit() =
    runTest {
      val result = filmstrip().capabilities()
      val capabilities = assertIs<CapabilitiesResult.Success>(result, "capabilities failed: $result").capabilities

      assertFalse(
        capabilities.supportsHdrEncoding,
        "the browser cannot render ten-bit frames yet, so claiming HDR would tag an SDR file as HDR",
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
