package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.AudioEffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs

/**
 * Adds a [TapeAudio] sound described by [block].
 *
 * ```
 * audioEffects {
 *   tapeAudio {
 *     tape = TapeSpeed.SP
 *     hiss = 0.5f
 *     wow = Wobble(depth = 0.8f, rateHz = 0.5f)
 *   }
 * }
 * ```
 */
public fun AudioEffectsBuilder.tapeAudio(block: TapeAudioBuilder.() -> Unit = {}): AudioEffectsBuilder =
  add(TapeAudioBuilder().apply(block).build())

/**
 * Describes a [TapeAudio]. Every value starts at the one [TapeAudio] uses unless it is given one.
 *
 * @property intensity How strong the sound is, from `0f` which leaves the audio alone to `1f`.
 * @property tape The tape speed, which sets the band, the hiss and how far the pitch bends.
 * @property seed Picks the hiss and where the wow and flutter start. Equal seeds play the same tape.
 * @property bandLimit How far the band narrows toward the tape's own, from `0f` for the full band to `1f`.
 * @property hiss How loud the hiss is against the tape's own, from `0f` for none to `1f`.
 * @property wow The slow bend in pitch.
 * @property flutter The fast bend in pitch.
 * @property mono Whether the channels are folded to one sound.
 */
@FilmstripDsl
public class TapeAudioBuilder internal constructor() {
  public var intensity: Float = 1f
  public var tape: TapeSpeed = TapeSpeed.EP
  public var seed: Int = Vhs.DEFAULT_SEED
  public var bandLimit: Float = 1f
  public var hiss: Float = 1f
  public var wow: Wobble = Wobble()
  public var flutter: Wobble = Wobble()
  public var mono: Boolean = true

  internal fun build(): TapeAudio = TapeAudio(intensity, tape, seed, bandLimit, hiss, wow, flutter, mono)
}
