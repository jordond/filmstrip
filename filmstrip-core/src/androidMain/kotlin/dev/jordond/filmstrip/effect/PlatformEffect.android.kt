package dev.jordond.filmstrip.effect

import androidx.media3.common.Effect
import androidx.media3.effect.TextureOverlay
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The Android form: a media3 [Effect] that runs as its own pass, or a [TextureOverlay] that the pipeline blends with
 * the overlays next to it.
 *
 * @property handle Which of the two this wraps.
 */
public actual class PlatformEffect private constructor(
  @property:InternalFilmstripApi
  public val handle: Media3Handle,
) {
  /**
   * Wraps an effect that runs as its own pass over the frame.
   *
   * @param effect The media3 effect to apply.
   */
  public constructor(effect: Effect) : this(Media3Handle.Video(effect))

  /**
   * Wraps an overlay, which the pipeline gathers with the overlays next to it into one blend.
   *
   * @param overlay The media3 overlay to draw.
   */
  public constructor(overlay: TextureOverlay) : this(Media3Handle.Overlay(overlay))
}

/**
 * The media3 object a [PlatformEffect] holds, read by the backends that render it.
 *
 * A [Video] runs as a pass of its own. An [Overlay] is blended with the overlays adjacent to it in the chain.
 */
@InternalFilmstripApi
public sealed interface Media3Handle {
  /**
   * An effect that runs as its own pass.
   *
   * @property effect The media3 effect.
   */
  @InternalFilmstripApi
  public class Video(
    public val effect: Effect,
  ) : Media3Handle

  /**
   * An overlay blended with its neighbours.
   *
   * @property overlay The media3 overlay.
   */
  @InternalFilmstripApi
  public class Overlay(
    public val overlay: TextureOverlay,
  ) : Media3Handle
}
