package dev.jordond.filmstrip.picture

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * The map [PictureOrientation.sourceOf] makes from points of the shown picture to points of the stored one, as one
 * affine step.
 *
 * A shown point at x and y reads the stored point at `x * acrossX + y * downX + offsetX` and
 * `x * acrossY + y * downY + offsetY`. Both points count in texels from their picture's top left corner, so the centre
 * of every shown texel lands on the centre of the stored texel `sourceOf` names.
 *
 * @property acrossX How far the stored point moves across as the shown one moves one texel across.
 * @property acrossY How far the stored point moves down as the shown one moves one texel across.
 * @property downX How far the stored point moves across as the shown one moves one texel down.
 * @property downY How far the stored point moves down as the shown one moves one texel down.
 * @property offsetX Where across the stored picture the shown top left corner reads.
 * @property offsetY Where down the stored picture the shown top left corner reads.
 */
@Poko
@InternalFilmstripApi
public class TexelAffine(
  public val acrossX: Int,
  public val acrossY: Int,
  public val downX: Int,
  public val downY: Int,
  public val offsetX: Double,
  public val offsetY: Double,
)

/**
 * The affine map from the picture this shows to the one stored at [stored], read off [PictureOrientation.sourceOf] at
 * three texels.
 */
@InternalFilmstripApi
public fun PictureOrientation.texelAffine(stored: Size): TexelAffine {
  val shown = orientedSize(stored)
  val origin = sourceOf(0, 0, shown)
  val across = sourceOf(1, 0, shown)
  val down = sourceOf(0, 1, shown)
  val acrossX = across.x - origin.x
  val acrossY = across.y - origin.y
  val downX = down.x - origin.x
  val downY = down.y - origin.y
  return TexelAffine(
    acrossX = acrossX,
    acrossY = acrossY,
    downX = downX,
    downY = downY,
    offsetX = origin.x + HALF_TEXEL - HALF_TEXEL * (acrossX + downX),
    offsetY = origin.y + HALF_TEXEL - HALF_TEXEL * (acrossY + downY),
  )
}

private const val HALF_TEXEL = 0.5
