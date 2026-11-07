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
   * @param newOverlay Builds the media3 overlay. It runs once for every graph that draws the effect, on that graph's GL
   * thread, and has to answer a new object each time, since an overlay holds the texture of the GL context it drew in.
   */
  public constructor(newOverlay: () -> TextureOverlay) : this(Media3Handle.Overlay(newOverlay))
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
   * @property newOverlay Builds the media3 overlay for one graph.
   */
  @InternalFilmstripApi
  public class Overlay(
    public val newOverlay: () -> TextureOverlay,
  ) : Media3Handle
}
