@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExportRefusalTest {
  private fun answered(vararg video: VideoEncoderCapability) =
    CapabilitiesResult.Success(
      DeviceCapabilities(
        video = video.toList(),
        audio = emptyList(),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      ),
    )

  @Test
  fun `a device with no video encoder is told it can preview but not export`() {
    exportRefusalOf(answered()) shouldBe "This device can't encode video, so it can preview but not export."
  }

  @Test
  fun `a device with an encoder can export`() {
    val h264 =
      VideoEncoderCapability(
        codec = VideoCodec.H264,
        encoderName = null,
        maxSize = Size(1920, 1080),
        maxFrameRate = null,
        maxBitrate = null,
        isHardwareAccelerated = null,
        sizeAlignment = 2,
      )

    exportRefusalOf(answered(h264)).shouldBeNull()
  }

  @Test
  fun `no answer yet refuses nothing`() {
    exportRefusalOf(null).shouldBeNull()
  }
}
