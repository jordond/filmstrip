package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition

/**
 * What the output should look like.
 *
 * Separate from [EditComposition] so one edit can be exported several ways. A 1080p upload and a
 * 480p preview share a composition and differ only here.
 *
 * @property heightPx Output height in pixels. Width follows from the composition's aspect. Null keeps the
 *   composition's own height. A height of zero or less is refused.
 * @property bitrate The video bitrate to aim for, or null to let filmstrip pick one.
 * @property videoCodec The codec to encode video with.
 * @property audioCodec The codec to encode audio with.
 * @property frameRate Output frame rate, or null to keep the source's. A rate of zero or less is refused.
 * @property hdr What to do about high dynamic range.
 * @property strict Fail rather than accept a fallback. False by default: fall back, and report every adjustment. Set it
 * when a byte budget or a codec requirement is non-negotiable. Only what the plan can see is refused. Some adjustments
 * only show once the export runs, such as media3 picking an encoder that writes another color space. Those reach
 * [ExportStatus.Success.adjustments] after a plan that said [Verdict.Capable], and strict cannot refuse them.
 */
@Poko
public class ExportSpec(
  public val heightPx: Int? = null,
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
      ExportSpec(heightPx = 1080, bitrate = Bitrate.mbps(4), videoCodec = VideoCodec.H264)
  }
}

/**
 * What to do about high dynamic range.
 *
 * Resolved once, up front, and the resolved mode is used by both the preview and the export, so the
 * grade a user looks at is the grade they get.
 */
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
