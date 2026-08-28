package dev.jordond.filmstrip.capability

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size

/**
 * What this device's encoders can actually do.
 *
 * Built once per process and cached. Check a source against it before planning an export.
 *
 * @property video Every video encoder the device offers for a codec this library probes, most
 *   preferred first. A codec can appear more than once, one entry per encoder the device offers for
 *   it, and the backend that built the list ordered each codec's entries.
 * @property audio Every audio encoder the device offers.
 * @property supportsHdrEncoding Whether any encoder here can produce HDR.
 * @property concurrentSessionBudget How many hardware encode sessions the device will grant at
 *   once, or null when it does not say. A floor rather than a guarantee.
 */
public class DeviceCapabilities
  @InternalFilmstripApi
  constructor(
    public val video: List<VideoEncoderCapability>,
    public val audio: List<AudioEncoderCapability>,
    public val supportsHdrEncoding: Boolean,
    public val concurrentSessionBudget: Int?,
  ) {
    /**
     * Looks up what the device can do with [codec].
     *
     * The first entry for [codec] wins, since [video] is ordered. Ranking is the backend's to
     * declare. [VideoEncoderCapability.isHardwareAccelerated] is reported for a caller that wants
     * it, and is not what selection sorts on.
     *
     * @param codec The codec to look up.
     * @return The capability entry, or null when the device has no encoder for [codec].
     */
    public fun encoderFor(codec: VideoCodec): VideoEncoderCapability? = video.firstOrNull { it.codec == codec }

    override fun toString(): String =
      "DeviceCapabilities(video=${video.map { it.codec }.distinct()}, hdr=$supportsHdrEncoding)"
  }

/**
 * One video encoder the device offers.
 *
 * @property codec The codec this encoder writes.
 * @property encoderName What the backend calls this encoder, or null when the backend does not name
 *   one: media3 and AVFoundation hand a codec to the platform and let it choose, so there is no name
 *   filmstrip could report that it also invokes.
 * @property maxSize The largest frame it accepts.
 * @property maxFrameRate The highest frame rate it accepts, or null when it does not say.
 * @property maxBitrate The highest bitrate it accepts, or null when it does not say.
 * @property isHardwareAccelerated False for a software encoder, true for a hardware one, or null
 *   when the platform will not say which it is.
 * @property sizeAlignment The alignment each dimension must have, typically 2 and sometimes 16.
 *   Filmstrip rounds a frame to this and reports the rounding as an [Adjustment].
 */
@Poko
public class VideoEncoderCapability(
  public val codec: VideoCodec,
  public val encoderName: String?,
  public val maxSize: Size,
  public val maxFrameRate: Int?,
  public val maxBitrate: Bitrate?,
  public val isHardwareAccelerated: Boolean?,
  public val sizeAlignment: Int,
) {
  /**
   * Checks a frame size against this encoder's limits.
   *
   * @param size The frame size to check.
   * @return True when the encoder accepts [size] as-is, without rounding or downscaling.
   */
  public fun accepts(size: Size): Boolean =
    size.width <= maxSize.width &&
      size.height <= maxSize.height &&
      size.width % sizeAlignment == 0 &&
      size.height % sizeAlignment == 0
}

/**
 * One audio encoder the device offers.
 *
 * @property codec The codec this encoder writes.
 * @property sampleRates The sample rates it accepts, in hertz.
 * @property maxChannelCount The most channels it accepts.
 */
@Poko
public class AudioEncoderCapability(
  public val codec: AudioCodec,
  public val sampleRates: List<Int>,
  public val maxChannelCount: Int,
)
