package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.MediaSource
import kotlin.math.PI
import kotlin.math.sin

// region spec
internal data class Tremolo(
  val rateHz: Float,
  val depth: Float,
) : AudioEffectSpec {
  override val id: EffectId get() = EffectId("sample.tremolo")

  override val isInert: Boolean get() = depth == 0f
}
// endregion

// region kernel
internal class TremoloKernel(
  private val format: AudioFormat,
  private val spec: Tremolo,
) : AudioKernel() {
  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    val channels = format.channelCount
    for (frame in 0 until frames) {
      val seconds = (position + frame).toDouble() / format.sampleRate
      val swing = 0.5f + 0.5f * sin(2 * PI * spec.rateHz * seconds).toFloat()
      val gain = 1f - spec.depth * swing
      for (channel in 0 until channels) buffer[frame * channels + channel] *= gain
    }
  }
}
// endregion

/**
 * What [spec] runs as on this platform.
 */
internal expect fun tremoloOn(spec: Tremolo): AudioEffectResolution

internal fun registered(): Filmstrip {
  // region register
  val tremolo = AudioEffectResolver { spec, _ -> (spec as? Tremolo)?.let(::tremoloOn) }

  val filmstrip =
    Filmstrip {
      addAudioEffectResolver(tremolo)
    }
  // endregion
  return filmstrip
}

internal fun scopes(): Composition {
  // region scopes
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/interview.mp4")) {
        audioEffects { add(Tremolo(rateHz = 5f, depth = 0.4f)) }
      }
      track(TrackContent.Audio) {
        clip(MediaSource.of("/path/to/music.m4a"))
        audioEffects { add(Tremolo(rateHz = 0.5f, depth = 0.2f)) }
      }
      audioEffects { add(Tremolo(rateHz = 8f, depth = 0.1f)) }
    }
  // endregion
  return composition
}
