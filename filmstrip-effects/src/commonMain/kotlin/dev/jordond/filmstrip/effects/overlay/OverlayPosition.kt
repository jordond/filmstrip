package dev.jordond.filmstrip.effects.overlay

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.geometry.Anchor

/**
 * Where an [ImageOverlay] sits on the frame it is drawn on.
 *
 * [Corner] tucks the overlay into one corner, inset from both edges. [At] puts the overlay's centre on any point of the
 * frame, which is how a freely placed sticker or title lands where it was dropped.
 */
@Immutable
public sealed interface OverlayPosition {
  /**
   * The overlay sits in [corner], inset from both edges by [margin].
   *
   * A margin large enough to carry the overlay past the middle of the frame is held at the middle.
   *
   * @property corner Which corner the overlay sits in.
   * @property margin Inset from the corner, as a fraction of the shorter side of the frame it is drawn on.
   */
  @Poko
  @Immutable
  public class Corner(
    public val corner: dev.jordond.filmstrip.geometry.Corner,
    public val margin: Float = ImageOverlay.DEFAULT_MARGIN,
  ) : OverlayPosition

  /**
   * The overlay's centre lands on [center].
   *
   * The point is not held inside the frame, so an overlay placed near an edge hangs off and the part outside is not
   * drawn. On Android, media3 holds the point itself inside the frame, so a point past an edge lands on that edge
   * there.
   *
   * @property center The point the overlay's centre lands on, as a fraction of the frame.
   */
  @Poko
  @Immutable
  public class At(
    public val center: Anchor,
  ) : OverlayPosition
}
