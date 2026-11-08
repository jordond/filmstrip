package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec

/**
 * The AAC encoder WebCodecs lists on a browser whose `AudioEncoder` takes AAC.
 *
 * It holds the sample rates and the channel count an export writes AAC at, and the capability probe answers with it.
 */
@InternalFilmstripApi
public val BROWSER_AAC_ENCODER: AudioEncoderCapability =
  AudioEncoderCapability(codec = AudioCodec.Aac, sampleRates = listOf(44_100, 48_000), maxChannelCount = 2)
