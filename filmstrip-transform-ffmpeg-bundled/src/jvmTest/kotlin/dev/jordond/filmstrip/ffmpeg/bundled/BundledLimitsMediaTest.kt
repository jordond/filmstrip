package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * What the LGPL build this artifact carries can and cannot encode, measured on the real binary.
 *
 * The limits are the build's own. It has no libx264 and no libx265, so off a Mac there is no HEVC encoder and nothing
 * that writes ten bits, and openh264 is the only H264 encoder.
 */
class BundledLimitsMediaTest {
  @Test
  fun `openh264 is measured up to the largest rung inside its 4096 by 2304 ceiling`() =
    runTest(timeout = TIMEOUT) {
      val openh264 = capabilities().video.first { it.encoderName == "libopenh264" }

      openh264.codec shouldBe VideoCodec.H264
      openh264.maxSize shouldBe
        RESOLUTION_LADDER.first { it.width <= OPENH264_MAX_WIDTH && it.height <= OPENH264_MAX_HEIGHT }
    }

  @Test
  fun `HEVC and HDR are offered only where VideoToolbox encodes them`() =
    runTest(timeout = TIMEOUT) {
      val capabilities = capabilities()

      if (IS_MAC) {
        capabilities.encoderFor(VideoCodec.Hevc).shouldNotBeNull().encoderName shouldBe "hevc_videotoolbox"
        capabilities.supportsHdrEncoding shouldBe true
      } else {
        capabilities.encoderFor(VideoCodec.Hevc) shouldBe null
        capabilities.supportsHdrEncoding shouldBe false
      }
    }

  private suspend fun capabilities(): DeviceCapabilities =
    Filmstrip().capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

  private companion object {
    val TIMEOUT = 2.minutes
    const val OPENH264_MAX_WIDTH = 4096
    const val OPENH264_MAX_HEIGHT = 2304
  }
}
