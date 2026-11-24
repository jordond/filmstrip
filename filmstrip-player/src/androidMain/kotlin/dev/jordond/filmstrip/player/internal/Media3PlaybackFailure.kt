package dev.jordond.filmstrip.player.internal

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.diagnostics.causeChainOf
import dev.jordond.filmstrip.media3.internal.failure.MEDIA3_BACKEND

/**
 * What media3 said about this failure, for the failure event a player reports.
 *
 * The code and its name come off the exception itself. A renderer failure carries the format that renderer was fed,
 * and a decoder that failed to start or to decode carries the codec it ran, both somewhere in the cause chain.
 */
internal fun PlaybackException.platformFailure(): PlatformFailure {
  val chain = generateSequence<Throwable>(this) { it.cause }.take(MAX_LEVELS).toList()
  val format = (this as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
  val decoding = chain.firstNotNullOfOrNull { it as? MediaCodecDecoderException }
  val starting = chain.firstNotNullOfOrNull { it as? MediaCodecRenderer.DecoderInitializationException }
  val codecName = decoding?.codecInfo?.name ?: starting?.codecInfo?.name

  return PlatformFailure(
    backend = MEDIA3_BACKEND,
    code = errorCode,
    codeName = errorCodeName,
    codecName = codecName,
    codecRole = if (decoding != null || starting != null) CodecRole.Decoder else null,
    inputMime = format?.sampleMimeType ?: starting?.mimeType,
    inputCodecs = format?.codecs,
    inputSize = format?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}x${it.height}" },
    inputFrameRate = format?.frameRate?.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f },
    causes = causeChainOf(this),
  )
}

private const val MAX_LEVELS = 8
