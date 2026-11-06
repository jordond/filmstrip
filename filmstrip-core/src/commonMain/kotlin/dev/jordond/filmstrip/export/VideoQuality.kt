package dev.jordond.filmstrip.export

/**
 * How much bitrate an export spends on its video, relative to its frame size, codec and frame rate.
 *
 * Each tier resolves to one bitrate at plan time, the same on every backend, and that is what
 * [OutputFormat.bitrate] reports. The reference rate is 3 Mbps for a 1080p H.264 frame at 30 fps, and it scales with
 * the frame's pixel count, the codec and any frame rate above 30. A transcode that names neither a bitrate nor a tier
 * runs at [High].
 */
public enum class VideoQuality {
  /**
   * The lowest tier, at 0.3 times the reference rate for a frame. It gives the smallest file.
   */
  VeryLow,

  /**
   * The second tier, at about 0.57 times the reference rate for a frame.
   */
  Low,

  /**
   * The middle tier, at about 1.08 times the reference rate for a frame.
   */
  Medium,

  /**
   * The fourth tier, at about 2.04 times the reference rate for a frame. A transcode that names no bitrate runs at
   * this tier.
   */
  High,

  /**
   * The highest tier, at about 3.86 times the reference rate for a frame. It gives the largest file.
   */
  VeryHigh,
}
