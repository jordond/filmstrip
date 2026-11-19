@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The one frame a [solid] shows for its whole span, a single RGBA pixel of its colour at time zero.
 *
 * The compositor stretches a clip's frame over the quad the plan placed, so one pixel draws the whole solid. The caller
 * owns the frame and closes it.
 */
internal fun solidSample(solid: MediaSource.Solid): VideoSample {
  val argb = solid.color
  val pixel =
    byteArrayOf(
      (argb shr RED_SHIFT).toByte(),
      (argb shr GREEN_SHIFT).toByte(),
      argb.toByte(),
      (argb ushr ALPHA_SHIFT).toByte(),
    )
  return VideoSample(
    pixel.toUint8Array(),
    JsOptions()
      .put("format", "RGBA")
      .put("codedWidth", 1)
      .put("codedHeight", 1)
      .put("timestamp", 0.0)
      .build(),
  )
}

/**
 * How opaque this solid is, from its packed alpha, as a fraction from 0 to 1.
 *
 * A kept grade draws a solid from its color and this rather than from [solidSample].
 */
internal val MediaSource.Solid.opacity: Float
  get() = (color ushr ALPHA_SHIFT) / OPAQUE_ALPHA

private const val OPAQUE_ALPHA = 255f
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_SHIFT = 24
