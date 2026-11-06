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
 * @property heightPx The most the output can be tall, in pixels. Width follows from the composition's aspect. A height
 * below the composition's own scales it down, and one at or above it keeps the composition's height, so a frame is
 * never scaled up. Use the `Scale` effect for an exact size. A composition framed by a still takes this height as it
 * is. Null keeps the composition's own height. A height of zero or less is refused.
 * @property bitrate The video bitrate to aim for, or null to let filmstrip pick one from [quality].
 * @property quality The quality tier filmstrip picks a bitrate from, or null for [VideoQuality.High] on a transcode.
 * Setting both this and [bitrate] is refused. Either one also blocks a stream copy.
 * @property videoCodec The codec to encode video with.
 * @property audioCodec The codec to encode audio with.
 * @property frameRate Output frame rate, or null to keep the source's. A rate of zero or less is refused.
 * @property hdr What to do about high dynamic range.
 * @property strict Fail rather than accept a fallback. False by default: fall back, and report every adjustment. Set it
 * when a byte budget or a codec requirement is non-negotiable. A tone map on a curve that departs from the one the
 * other backends run counts as a fallback too, so a strict tone mapped export on such an engine is refused, even with
 * [HdrMode.ToneMapToSdr] asked for. Only what the plan can see is refused. Some adjustments only show once the export
 * runs, such as media3 picking an encoder that writes another color space. Those reach
 * [ExportStatus.Success.adjustments] after a plan that said [Verdict.Capable], and strict cannot refuse them.
 */
@Poko
public class ExportSpec(
  public val heightPx: Int? = null,
  public val bitrate: Bitrate? = null,
  public val quality: VideoQuality? = null,
  public val videoCodec: VideoCodec = VideoCodec.Auto,
  public val audioCodec: AudioCodec = AudioCodec.Auto,
  public val frameRate: Int? = null,
  public val hdr: HdrMode = HdrMode.Auto,
  public val strict: Boolean = false,
) {
  public companion object {
    /**
     * A preset for sharing online. It asks for at most 1080p at 4 Mbps in H.264, tone mapped to SDR. Audio codec and
     * frame rate are left to filmstrip.
     */
    public val Upload: ExportSpec =
      ExportSpec(
        heightPx = 1080,
        bitrate = Bitrate.mbps(4),
        videoCodec = VideoCodec.H264,
        hdr = HdrMode.ToneMapToSdr,
      )

    /**
     * A preset for sending in a message, small enough to travel. It asks for at most 720p at 2 Mbps in H.264 with
     * AAC audio, tone mapped to SDR. Frame rate is left to filmstrip.
     */
    public val Share: ExportSpec =
      ExportSpec(
        heightPx = 720,
        bitrate = Bitrate.mbps(2),
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.Aac,
        hdr = HdrMode.ToneMapToSdr,
      )

    /**
     * A preset for keeping a copy at full size. It encodes HEVC with AAC audio and keeps an HDR grade, refusing an HDR
     * source on a device that cannot keep it. Height, frame rate and bitrate are left to filmstrip, which encodes at
     * [VideoQuality.High].
     */
    public val Archive: ExportSpec =
      ExportSpec(
        videoCodec = VideoCodec.Hevc,
        audioCodec = AudioCodec.Aac,
        hdr = HdrMode.KeepHdr,
      )

    /**
     * A preset that stays as close to the source as the edit allows. It encodes with the first clip's own codec,
     * through [VideoCodec.MatchSource], and leaves everything else to filmstrip. Naming no size, rate or bitrate, it
     * keeps a stream copy open to an edit that could otherwise take one.
     */
    public val Original: ExportSpec = ExportSpec(videoCodec = VideoCodec.MatchSource)
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
