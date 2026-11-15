package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed

/**
 * A tape format at one of its speeds.
 *
 * [TapeSpeed], [BetaSpeed] and [Hi8Speed] are the stocks this module has. A [TapeAudio] plays one, and a [Camcorder]
 * display can name one.
 *
 * @property label The short name a camcorder display shows for this stock.
 */
public interface TapeStock {
  public val label: String

  /**
   * What the sound track of this stock does to audio, which the tape audio renderers read.
   */
  @InternalFilmstripApi
  public fun audioFigures(): TapeAudioFigures
}

/**
 * What the sound track of a [TapeStock] does to audio at full strength.
 *
 * @property lowPassHz The top of the band, in hertz.
 * @property hissDb How far the hiss sits under full scale, in decibels.
 * @property wow How far the slow bend moves the pitch, as a fraction.
 * @property flutter How far the fast bend moves the pitch, as a fraction.
 */
@InternalFilmstripApi
public class TapeAudioFigures(
  public val lowPassHz: Double,
  public val hissDb: Double,
  public val wow: Double,
  public val flutter: Double,
)
