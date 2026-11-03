package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.writer.hdrProbeCodecType
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import platform.CoreMedia.kCMVideoCodecType_HEVC
import kotlin.test.Test

/**
 * The codec-membership decision that picks which encoder gets probed for HDR, kept separate from
 * the VideoToolbox call it feeds so it can be asserted without a real session.
 */
class HdrProbeTest {
  @Test
  fun `a device that lists no ladder codec is never probed`() {
    hdrProbeCodecType(listOf(encoder(VideoCodec.H264))).shouldBeNull()
    hdrProbeCodecType(emptyList()).shouldBeNull()
  }

  @Test
  fun `a device that lists Hevc is probed at its codec type`() {
    val codecType = hdrProbeCodecType(listOf(encoder(VideoCodec.H264), encoder(VideoCodec.Hevc)))

    codecType shouldBe kCMVideoCodecType_HEVC
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
