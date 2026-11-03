package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.Effect

/**
 * Sees every lowered video effect as it is produced, in chain order, and says what goes into the
 * chain in its place.
 *
 * A preview installs one to keep hold of the positions a parameter change can reach. An export
 * takes the default and lowers exactly what it always did.
 */
internal fun interface EffectWrapper {
  /**
   * Returns what belongs in the chain where [effect] was lowered.
   */
  fun wrap(effect: Effect): Effect
}

/**
 * Lowers each effect and passes it straight through.
 */
internal val UnwrappedEffects: EffectWrapper = EffectWrapper { it }
