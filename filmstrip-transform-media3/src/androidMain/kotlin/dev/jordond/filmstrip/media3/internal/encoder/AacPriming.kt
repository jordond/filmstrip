package dev.jordond.filmstrip.media3.internal.encoder

import androidx.media3.common.Format
import androidx.media3.transformer.Codec

/**
 * A [Codec] whose output format names the [primingFrames] of priming its encoder writes ahead of its stream.
 *
 * The muxer moves the audio back by whatever delay the output format names, and media3 names one of its own that can
 * differ from what the encoder writes, so the stream would start the difference off. Naming the priming the encoder
 * writes moves the stream's first frame to the start of the file.
 *
 * @param delegate The encoder whose output format is renamed.
 * @property primingFrames The frames of priming [delegate] writes.
 */
internal class AacPriming(
  private val delegate: Codec,
  val primingFrames: Long,
) : Codec by delegate {
  override fun getOutputFormat(): Format? =
    delegate.outputFormat
      ?.buildUpon()
      ?.setEncoderDelay(primingFrames.toInt())
      ?.build()
}

/**
 * The encoder [created] wrapped in an [AacPriming] when [aacPrimingFrames] names its priming, or [created] itself
 * otherwise.
 */
internal fun aacPrimed(created: Codec): Codec =
  aacPrimingFrames(created.name)?.let { AacPriming(created, it) } ?: created

/**
 * The frames of priming the AAC encoder named [encoder] writes ahead of its stream, or null when it is not one of
 * Android's software encoders.
 *
 * `c2.android.aac.encoder` and `OMX.google.aac.encoder`, which the factory picks on API 29 and below, both write
 * [AAC_PRIMING_FRAMES], measured on an emulator at every API level from 24 to 36. media3 names 1600 frames on the first
 * and none on the second.
 */
internal fun aacPrimingFrames(encoder: String): Long? =
  when (encoder) {
    CODEC2_AAC_ENCODER, OMX_AAC_ENCODER -> AAC_PRIMING_FRAMES
    else -> null
  }
