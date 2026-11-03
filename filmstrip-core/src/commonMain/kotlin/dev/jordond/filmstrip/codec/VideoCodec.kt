package dev.jordond.filmstrip.codec

/**
 * A video codec filmstrip can produce, whether by asking a device's hardware encoder for it or by
 * copying it across from a source untouched.
 *
 * H.264 is encodable everywhere and HEVC almost everywhere. VP9 is a browser encode target too.
 * The rest are never an encode target on any backend today and only ever reach an output by copy.
 */
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
