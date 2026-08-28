package dev.jordond.filmstrip.geometry

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What fills the frame where no clip's pixels land.
 *
 * [Fit.Contain] can leave bars, a track that starts after the composition does leaves a gap before
 * it plays, and rounding can leave a frame a pixel short of an edge. This is what fills all three.
 *
 * More arms will arrive as filmstrip adds fill kinds, so a consumer must handle one it does not
 * recognise rather than assume the set is closed.
 */
@Serializable
public sealed interface Fill {
  /**
   * A flat colour behind everything.
   *
   * @property color Packed ARGB, as `0xAARRGGBB`. Alpha is ignored, since no output filmstrip
   *   writes carries an alpha channel.
   */
  @Serializable
  @SerialName("solid")
  @Poko
  public class Solid(
    public val color: Int = BLACK,
  ) : Fill

  /**
   * The clip's own frame, scaled to cover the output and blurred, behind everything.
   *
   * The background is the frame scaled uniformly until it covers the output, centre-cropped, then
   * blurred. A gap where no clip plays has no frame to blur, and fills with black instead.
   *
   * @property radius The blur's standard deviation as a fraction of the output frame's shorter
   *   side, in `0f..1f`, so the same edit blurs identically at any export resolution.
   * @property dim How far the background is darkened toward black, in `0f..1f`. The background's
   *   encoded colour channels are multiplied by `1 - dim`, so `0f` leaves it alone, `0.5f` halves
   *   the stored value and `1f` is black. The multiply lands on the encoded value rather than on
   *   linear light, so a backend whose colour pipeline works in linear light compensates for its
   *   own transfer curve. Applies only to the background, never to the clip's own pixels.
   */
  @Serializable
  @SerialName("blurred")
  @Poko
  public class Blurred(
    public val radius: Float = 0.04f,
    public val dim: Float = 0f,
  ) : Fill

  public companion object {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Opaque black, which is what every backend wrote before a fill could be authored.
     */
    public val Black: Fill = Solid(BLACK)

    /**
     * Opaque white.
     */
    public val White: Fill = Solid(WHITE)

    /**
     * A blurred copy of the frame at the default radius, undimmed.
     */
    public val Blur: Fill = Blurred()
  }
}
