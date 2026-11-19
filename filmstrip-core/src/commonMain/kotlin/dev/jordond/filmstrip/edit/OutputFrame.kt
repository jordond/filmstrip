package dev.jordond.filmstrip.edit

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Size

/**
 * The frame a composition's clips land in, before the composition's own geometry effects and the export's height.
 *
 * Each clip lands in this frame by its own fit, and the composition's [Composition.fill] covers what it leaves.
 * Composition geometry effects then run on the frame, and `ExportSpec.heightPx` applies last.
 *
 * More arms will arrive as filmstrip learns other ways to set the frame, so a consumer must handle one it does not
 * recognise rather than assume the set is closed.
 */
public sealed interface OutputFrame {
  /**
   * The frame of the primary track's first clip that carries video, after that clip's and its track's effects. A
   * leading still is passed over for the first clip that decodes video, and a track of stills alone takes the first
   * still's frame.
   */
  public data object Auto : OutputFrame

  /**
   * The [Auto] frame's height at [aspect], the same frame a composition `crop(aspect, Fit.Contain)` placed before every
   * other composition effect leaves.
   *
   * @property aspect The width over height of the frame. Each side has to be positive, or the composition is refused
   * with [dev.jordond.filmstrip.FilmstripError.InvalidComposition] when it is planned.
   */
  @Poko
  @ExperimentalFilmstripApi
  public class Aspect(
    public val aspect: AspectRatio,
  ) : OutputFrame

  /**
   * Exactly [size], whatever the clips measure, the same frame `crop(aspect, Fit.Contain)` then `scale(height)` leaves
   * as the first composition effects. A `heightPx` on the export is taken as given against it, above or below
   * [size]'s height, the way a composition framed by a still reads it.
   *
   * @property size The frame in pixels. Each side has to be positive, or the composition is refused with
   * [dev.jordond.filmstrip.FilmstripError.InvalidComposition] when it is planned.
   */
  @Poko
  @ExperimentalFilmstripApi
  public class Fixed(
    public val size: Size,
  ) : OutputFrame
}
