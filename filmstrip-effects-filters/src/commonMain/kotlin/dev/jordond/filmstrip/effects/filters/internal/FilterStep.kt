package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * One draw of a filter's chain of fragment shaders.
 *
 * @property name What a later step calls this step's output in its own [reads].
 * @property fragment The fragment shader source.
 * @property reads Each sampler this step reads, to the [name] of an earlier step or [INPUT].
 * @property targetAt The size this step draws at, for a frame of a width and height in pixels.
 * @property uniformsAt The uniforms for a frame at a composition time in seconds and a width and height in pixels, by
 * name. A name ending in `[0]` is a float array, and any other is a float or a vec by its length.
 */
@InternalFilmstripApi
public class FilterStep internal constructor(
  public val name: String,
  public val fragment: String,
  public val reads: Map<String, String>,
  public val targetAt: (width: Int, height: Int) -> Size,
  public val uniformsAt: (seconds: Double, width: Int, height: Int) -> Map<String, FloatArray>,
) {
  /**
   * The names every chain of steps shares.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * What a step names the frame the whole chain starts from in [reads].
     */
    public const val INPUT: String = "input"
  }
}
