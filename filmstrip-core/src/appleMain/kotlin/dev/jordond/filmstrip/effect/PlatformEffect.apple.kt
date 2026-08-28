package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.Foundation.setValue
import kotlin.time.Duration

/**
 * The Apple form: one step of a Core Image chain.
 *
 * A step, not a bare `CIFilter`, so an effect can be a transform plus a composite, or a rect
 * operation with no filter at all.
 *
 * @property step The transformation this effect applies.
 */
public actual class PlatformEffect(
  public val step: CoreImageEffect,
) {
  /**
   * Wraps a single [CIFilter], applied by setting the image on `inputImage`.
   *
   * @param filter The filter to apply.
   */
  public constructor(filter: CIFilter) : this(
    CoreImageEffect { image, _ ->
      filter.setValue(image, forKey = "inputImage")
      filter.outputImage ?: image
    },
  )
}

/**
 * One step of a Core Image chain.
 *
 * Implement it to write an effect, from Kotlin or from Swift.
 */
public fun interface CoreImageEffect {
  /**
   * Applies this step to [image].
   *
   * @param image the frame coming out of the previous step.
   * @param frame what the pipeline knows about this frame. Everything scale-dependent is read from
   *   here, never off [image], so the same declaration lands in the same place at preview
   *   scale and at export scale.
   * @return the transformed frame.
   */
  public fun apply(
    image: CIImage,
    frame: FrameInfo,
  ): CIImage
}

/**
 * What a [CoreImageEffect] is told about the frame it has been handed.
 *
 * Constructed by filmstrip only. New fields may be added in a later release, so read the ones you
 * need instead of destructuring.
 *
 * @property attributes The frame size, colour space and render scale the pipeline resolved before
 *   any effect ran. Settled once per effect, so it is the same object on every frame.
 * @property compositionTime Where this frame sits on the composition's timeline, counted from the
 *   start of the whole composition, not from the clip the frame came from. That is the base
 *   a [dev.jordond.filmstrip.edit.TimeRange] on an effect is measured against, on every backend.
 */
public class FrameInfo
  @InternalFilmstripApi
  constructor(
    public val attributes: Attributes,
    public val compositionTime: Duration,
  ) {
    override fun toString(): String = "FrameInfo($attributes at $compositionTime)"
  }
