package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.hdrProbeCodecType
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import platform.CoreMedia.kCMVideoCodecType_HEVC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The codec-membership decision that picks which encoder gets probed for HDR, kept separate from
 * the VideoToolbox call it feeds so it can be asserted without a real session.
 */
class HdrProbeTest {
  @Test
  fun `a device that lists no ladder codec is never probed`() {
    assertNull(hdrProbeCodecType(listOf(encoder(VideoCodec.H264))))
    assertNull(hdrProbeCodecType(emptyList()))
  }

  @Test
  fun `a device that lists Hevc is probed at its codec type`() {
    val codecType = hdrProbeCodecType(listOf(encoder(VideoCodec.H264), encoder(VideoCodec.Hevc)))

    assertEquals(kCMVideoCodecType_HEVC, codecType)
  }

  private fun encoder(codec: VideoCodec) =
    VideoEncoderCapability(
      codec = codec,
      encoderName = null,
      maxSize = Size(3840, 2160),
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = true,
      sizeAlignment = 2,
    )
}
