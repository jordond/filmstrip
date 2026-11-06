package dev.jordond.filmstrip.transform.internal.encode

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * The tier a transcode runs at when its spec names neither a bitrate nor a quality.
 */
@InternalFilmstripApi
public val DEFAULT_QUALITY: VideoQuality = VideoQuality.High

/**
 * The video bitrate a [quality] tier resolves to for a frame of [size] in [codec] at [frameRate].
 *
 * It starts from 3 Mbps at 1080p in H.264, scales with the pixel count to the power 0.95, then by how much less a
 * codec needs than H.264 and by the tier's factor, `0.3 * e^(2.5538 * q)` for q from 0 to 1. Above 30 fps it scales
 * with the frame rate over 30, and at or below 30 it stays flat. The result rounds up to a whole kilobit per second.
 * The tiers and codec factors match mediabunny's `Quality` scale.
 *
 * The planner is the only caller, and every backend reads the answer off the plan's output format.
 */
@InternalFilmstripApi
public fun resolveVideoBitrate(
  quality: VideoQuality,
  codec: VideoCodec,
  size: Size,
  frameRate: Int,
): Bitrate {
  val pixels = size.width.toDouble() * size.height.toDouble()
  val base = REFERENCE_BITRATE * (pixels / REFERENCE_PIXELS).pow(PIXEL_EXPONENT)
  val tier = TIER_BASE * exp(TIER_EXPONENT * quality.position)
  val rate = max(1.0, frameRate / REFERENCE_FRAME_RATE)
  val bits = base * codec.efficiency * tier * rate
  return Bitrate(ceil(bits / KILOBIT).toLong() * KILOBIT.toLong())
}

private val VideoQuality.position: Double
  get() =
    when (this) {
      VideoQuality.VeryLow -> 0.0
      VideoQuality.Low -> 0.25
      VideoQuality.Medium -> 0.5
      VideoQuality.High -> 0.75
      VideoQuality.VeryHigh -> 1.0
    }

private val VideoCodec.efficiency: Double
  get() =
    when (this) {
      VideoCodec.H264 -> 1.0
      VideoCodec.Hevc, VideoCodec.Vp9 -> 0.6
      VideoCodec.Av1 -> 0.4
      VideoCodec.Vp8 -> 1.2
      VideoCodec.Auto, VideoCodec.MatchSource -> error("$this is resolved to a codec before a bitrate is.")
    }

private const val REFERENCE_BITRATE = 3_000_000.0
private const val REFERENCE_PIXELS = 1920.0 * 1080.0
private const val REFERENCE_FRAME_RATE = 30.0
private const val PIXEL_EXPONENT = 0.95
private const val TIER_BASE = 0.3
private const val TIER_EXPONENT = 2.5538
private const val KILOBIT = 1000.0
