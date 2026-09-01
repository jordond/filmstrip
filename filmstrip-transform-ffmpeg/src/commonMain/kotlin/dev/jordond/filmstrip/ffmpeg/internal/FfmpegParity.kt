package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.effect.EffectIds

/**
 * What each built-in effect's parity is on this backend.
 *
 * A plain lookup, with no I/O and no suspending, because it backs `Filmstrip.parityOf`, which
 * answers without doing any work.
 *
 * This backend keeps its own table. `Scale` is `Approximate` here because swscale is a different
 * resampler, and `Text` is left out because it has no lowering here.
 */
internal object FfmpegParity {
  fun of(specId: String): EffectParity? = TABLE[specId]

  fun noteFor(specId: String): String? = NOTES[specId]

  private val TABLE: Map<String, EffectParity> =
    mapOf(
      EffectIds.ROTATE to EffectParity.Exact,
      EffectIds.FLIP to EffectParity.Exact,
      EffectIds.CROP to EffectParity.Exact,
      EffectIds.CROP_RECT to EffectParity.Exact,
      EffectIds.BRIGHTNESS to EffectParity.Exact,
      EffectIds.IMAGE_OVERLAY to EffectParity.Exact,
      EffectIds.SCALE to EffectParity.Approximate,
    )

  private val NOTES: Map<String, String> =
    mapOf(
      EffectIds.SCALE to
        "swscale resamples bicubic, which is a different kernel from the one a preview would use, " +
        "so resampling detail on a downscale differs.",
    )
}
