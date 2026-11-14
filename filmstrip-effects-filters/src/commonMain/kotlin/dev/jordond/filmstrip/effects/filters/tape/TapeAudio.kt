package dev.jordond.filmstrip.effects.filters.tape

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs

/**
 * Makes audio sound like it was played back from the linear track of a VHS tape.
 *
 * Narrows the band, lays hiss under the sound, and bends the pitch with a slow wow and a fast flutter. The hiss and
 * the bend are functions of the composition time and the [seed], so a seek, a second export and every clip of a track
 * land on the same tape.
 *
 * Each part scales the figure its [tape] speed has, and [intensity] scales them all. A [TapeAudio] and a
 * [Vhs] with equal [tape] and [seed] read as one recording.
 *
 * Register it with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property intensity How strong the sound is, from `0f` which leaves the audio alone to `1f`.
 * @property tape The tape speed, which sets the band, the hiss and how far the pitch bends.
 * @property seed Picks the hiss and where the wow and flutter start. Equal seeds play the same tape.
 * @property bandLimit How far the band narrows toward the tape's own, from `0f` for the full band to `1f`.
 * @property hiss How loud the hiss is against the tape's own, from `0f` for none to `1f`.
 * @property wow The slow bend in pitch.
 * @property flutter The fast bend in pitch.
 * @property mono Whether the channels are folded to one sound, as a linear track holds on nearly every deck.
 * @throws IllegalArgumentException when [intensity], [bandLimit] or [hiss] is outside `0f..1f`.
 */
@Poko
public class TapeAudio(
  public val intensity: Float = 1f,
  public val tape: TapeSpeed = TapeSpeed.EP,
  public val seed: Int = Vhs.DEFAULT_SEED,
  public val bandLimit: Float = 1f,
  public val hiss: Float = 1f,
  public val wow: Wobble = Wobble(),
  public val flutter: Wobble = Wobble(),
  public val mono: Boolean = true,
) : AudioEffectSpec {
  init {
    require(intensity in 0f..1f) { "A tape audio intensity has to be within 0 to 1, not $intensity." }
    require(bandLimit in 0f..1f) { "A tape audio band limit has to be within 0 to 1, not $bandLimit." }
    require(hiss in 0f..1f) { "A tape audio hiss has to be within 0 to 1, not $hiss." }
  }

  override val id: EffectId get() = ID

  override val isInert: Boolean
    get() = intensity == 0f || (bandLimit == 0f && hiss == 0f && wow.depth == 0f && flutter.depth == 0f && !mono)

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.tapeAudio")
  }
}

/**
 * One bend in a tape's pitch, which rises and falls at a steady rate.
 *
 * @property depth How far the pitch bends against the tape's own figure, from `0f` for none to `1f`.
 * @property rateHz How many times a second the bend repeats, or null for the tape's own rate.
 * @throws IllegalArgumentException when [depth] is outside `0f..1f`, or [rateHz] is outside [MIN_RATE_HZ] to
 * [MAX_RATE_HZ].
 */
@Poko
public class Wobble(
  public val depth: Float = 1f,
  public val rateHz: Float? = null,
) {
  init {
    require(depth in 0f..1f) { "A wobble depth has to be within 0 to 1, not $depth." }
    require(rateHz == null || rateHz in MIN_RATE_HZ..MAX_RATE_HZ) {
      "A wobble rate has to be within $MIN_RATE_HZ to $MAX_RATE_HZ hertz, not $rateHz."
    }
  }

  public companion object {
    /**
     * The slowest rate a [Wobble] takes, in hertz.
     */
    public const val MIN_RATE_HZ: Float = 0.1f

    /**
     * The fastest rate a [Wobble] takes, in hertz.
     */
    public const val MAX_RATE_HZ: Float = 100f
  }
}
