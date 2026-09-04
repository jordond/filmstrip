package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import kotlinx.serialization.Serializable

/**
 * What the output should look like.
 *
 * Separate from [EditComposition] so one edit can be exported several ways. A 1080p upload and a
 * 480p preview share a composition and differ only here.
 *
 * @property targetHeight Output height in pixels. Width follows from the composition's aspect. Null
 *   keeps the source height.
 * @property bitrate The video bitrate to aim for, or null to let filmstrip pick one.
 * @property videoCodec The codec to encode video with.
 * @property audioCodec The codec to encode audio with.
 * @property frameRate Output frame rate, or null to keep the source's.
 * @property hdr What to do about high dynamic range.
 * @property strict Fail rather than accept a fallback. False by default: fall back, and report
 *   every adjustment. Set it when a byte budget or a codec requirement is non-negotiable.
 */
@Serializable
@Poko
public class ExportSpec(
  public val targetHeight: Int? = null,
  public val bitrate: Bitrate? = null,
  public val videoCodec: VideoCodec = VideoCodec.Auto,
  public val audioCodec: AudioCodec = AudioCodec.Auto,
  public val frameRate: Int? = null,
  public val hdr: HdrMode = HdrMode.Auto,
  public val strict: Boolean = false,
) {
  public companion object {
    /**
     * Sensible upload defaults: 1080p, roughly 4 Mbps, H.264, tone-mapped to SDR.
     */
    public val Upload: ExportSpec =
      ExportSpec(targetHeight = 1080, bitrate = Bitrate.mbps(4), videoCodec = VideoCodec.H264)
  }
}

/**
 * A bitrate.
 *
 * @property bitsPerSecond The rate, in bits per second.
 */
@Serializable
@Poko
public class Bitrate(
  public val bitsPerSecond: Long,
) {
  public companion object {
    /**
     * Creates a bitrate of [value] megabits per second.
     */
    public fun mbps(value: Double): Bitrate = Bitrate((value * 1_000_000).toLong())

    /**
     * Creates a bitrate of [value] megabits per second.
     */
    public fun mbps(value: Int): Bitrate = mbps(value.toDouble())

    /**
     * Creates a bitrate of [value] kilobits per second.
     */
    public fun kbps(value: Double): Bitrate = Bitrate((value * 1_000).toLong())

    /**
     * Creates a bitrate of [value] kilobits per second.
     */
    public fun kbps(value: Int): Bitrate = kbps(value.toDouble())
  }
}

/**
 * This many megabits per second.
 */
public val Int.Mbps: Bitrate get() = Bitrate.mbps(this)

/**
 * This many kilobits per second.
 */
public val Int.Kbps: Bitrate get() = Bitrate.kbps(this)

/**
 * A video codec filmstrip can produce, whether by asking a device's hardware encoder for it or by
 * copying it across from a source untouched.
 *
 * H.264 is encodable everywhere and HEVC almost everywhere. VP9 is a browser encode target too.
 * The rest are never an encode target on any backend today and only ever reach an output by copy.
 */
@Serializable
public enum class VideoCodec {
  /**
   * Let filmstrip choose, preferring the source's codec when the device can encode it.
   */
  Auto,

  /**
   * H.264, also known as AVC.
   */
  H264,

  /**
   * HEVC, also known as H.265.
   */
  Hevc,

  /**
   * VP9. Browser encoders report it slightly more often than H.264, and no mobile backend offers
   * it today.
   */
  Vp9,

  /**
   * VP8. No backend encodes it, so it only ever reaches an output by copy.
   */
  Vp8,

  /**
   * AV1. No backend encodes it, so it only ever reaches an output by copy.
   */
  Av1,
}

/**
 * An audio codec filmstrip can produce, whether by encoding to it or by copying it across from a
 * source untouched.
 */
@Serializable
public enum class AudioCodec {
  /**
   * Let filmstrip choose.
   */
  Auto,

  /**
   * AAC.
   */
  Aac,

  /**
   * Apple Lossless.
   */
  Alac,

  /**
   * Opus. No backend encodes it, so it only ever reaches an output by copy.
   */
  Opus,

  /**
   * MP3. No backend encodes it, so it only ever reaches an output by copy.
   */
  Mp3,

  /**
   * FLAC. No backend encodes it, so it only ever reaches an output by copy.
   */
  Flac,

  /**
   * Vorbis. No backend encodes it, so it only ever reaches an output by copy.
   */
  Vorbis,

  /**
   * Write no audio track. To keep a silent track instead, see [AudioSpec].
   */
  None,
}

/**
 * What to do about high dynamic range.
 *
 * Resolved once, up front, and the resolved mode is used by both the preview and the export, so the
 * grade a user looks at is the grade they get.
 */
@Serializable
public enum class HdrMode {
  /**
   * Keep HDR if the device can encode it, otherwise tone-map and report the adjustment.
   */
  Auto,

  /**
   * Keep HDR, and refuse the export if the device cannot.
   */
  KeepHdr,

  /**
   * Always tone-map to SDR.
   */
  ToneMapToSdr,
}
