package dev.jordond.filmstrip.capability

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [DeviceCapabilities.encoderFor] returns when a codec has more than one encoder, which is
 * possible now that an engine can report every encoder it finds rather than just the first. The
 * backend orders the list, and that order is the whole rule.
 */
@OptIn(InternalFilmstripApi::class)
class DeviceCapabilitiesTest {
  @Test
  fun `takes the encoder the backend listed first`() {
    val preferred = h264(hardware = false, maxHeight = 1_080)
    val alternative = h264(hardware = true, maxHeight = 2_160)
    val device = deviceWith(listOf(preferred, alternative))

    assertEquals(preferred, device.encoderFor(VideoCodec.H264))
  }

  // The ffmpeg backend ranks libx264 above h264_videotoolbox and hevc_videotoolbox above libx265,
  // which only works if the order it declares is the order that is read back.
  @Test
  fun `does not let a hardware encoder jump the order`() {
    val hardware = h264(hardware = true, maxHeight = 2_160)
    val software = h264(hardware = false, maxHeight = 1_080)

    assertEquals(hardware, deviceWith(listOf(hardware, software)).encoderFor(VideoCodec.H264))
    assertEquals(software, deviceWith(listOf(software, hardware)).encoderFor(VideoCodec.H264))
  }

  @Test
  fun `skips the entries for other codecs`() {
    val hevc =
      VideoEncoderCapability(
        codec = VideoCodec.Hevc,
        encoderName = "hevc_videotoolbox",
        maxSize = Size(3_840, 2_160),
        maxFrameRate = null,
        maxBitrate = null,
        isHardwareAccelerated = true,
        sizeAlignment = 2,
      )
    val h264 = h264(hardware = false, maxHeight = 1_080)
    val device = deviceWith(listOf(hevc, h264))

    assertEquals(h264, device.encoderFor(VideoCodec.H264))
  }

  @Test
  fun `returns null for a codec the device has no encoder for`() {
    val device = deviceWith(listOf(h264(hardware = true, maxHeight = 2_160)))

    assertNull(device.encoderFor(VideoCodec.Hevc))
  }

  private fun deviceWith(video: List<VideoEncoderCapability>): DeviceCapabilities =
    DeviceCapabilities(
      video = video,
      audio = emptyList(),
      supportsHdrEncoding = false,
      concurrentSessionBudget = null,
    )

  private fun h264(
    hardware: Boolean?,
    maxHeight: Int,
  ): VideoEncoderCapability =
    VideoEncoderCapability(
      codec = VideoCodec.H264,
      encoderName = null,
      maxSize = Size(maxHeight * ASPECT_NUMERATOR / ASPECT_DENOMINATOR, maxHeight),
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = hardware,
      sizeAlignment = 2,
    )

  private companion object {
    const val ASPECT_NUMERATOR = 16
    const val ASPECT_DENOMINATOR = 9
  }
}
