package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.max
import kotlin.math.min

// Every number a backend needs to draw a Fill, derived once. A backend decides how to realise
// these and never how to compute them. Four backends each doing their own arithmetic off the same
// KDoc is how two exports of one edit end up looking different, and a shared function is something
// a test can pin where a sentence is not.

/**
 * The blur's standard deviation, in pixels of the output frame.
 *
 * A backend that cannot reach this figure lowers the resolution it blurs at rather than lowering
 * the figure. The one exception is a backend whose own blur has a documented ceiling, which clamps
 * and says so at the clamp.
 */
@InternalFilmstripApi
public fun Fill.Blurred.sigmaFor(outputSize: Size): Float =
  (radius * min(outputSize.width, outputSize.height)).coerceAtLeast(1f)

/**
 * The [BlurPlan] every backend blurs this fill's cover by, for a frame of [outputSize].
 *
 * It runs [sigmaFor] as a fraction of the output width, so the cover is boxed down, blurred and read back up the same
 * way a GaussianBlur effect is. Preview at its own size gets the plan for that size.
 */
@InternalFilmstripApi
public fun Fill.Blurred.blurPlan(outputSize: Size): BlurPlan =
  BlurPlan.of(sigmaFor(outputSize).toDouble() / outputSize.width, outputSize.width)

/**
 * Whether this fill is drawn from the clip's own pixels rather than named as a color.
 *
 * That is what decides whether a composition effect reaches it: a frame-derived fill is picture and
 * carries the grade with the rest of the frame, while a named color is furniture and is painted in
 * only once composition effects have run. An arm this build does not recognise falls back to a
 * color, matching every backend's own `else -> black`.
 */
@InternalFilmstripApi
public val Fill.derivesFromFrame: Boolean
  get() = this is Fill.Blurred

/**
 * What the background's color channels are multiplied by.
 *
 * A gain rather than an offset, so a mid-grey at half dim lands halfway to black rather than at
 * black. Alpha is never touched.
 *
 * The multiply lands on the encoded value, not on linear light. A backend whose color pipeline
 * works in linear light raises this by its own transfer curve first, or it darkens by less than it
 * was asked to.
 */
@InternalFilmstripApi
public val Fill.Blurred.backgroundGain: Float
  get() = (1f - dim).coerceIn(0f, 1f)

/**
 * The uniform scale that grows [sourceSize] until it covers [outputSize], cropping the overflow.
 *
 * What a blurred fill's background is drawn at.
 */
@InternalFilmstripApi
public fun coverScale(
  sourceSize: Size,
  outputSize: Size,
): Float =
  max(
    outputSize.width.toFloat() / sourceSize.width,
    outputSize.height.toFloat() / sourceSize.height,
  )

/**
 * The uniform scale that shrinks [sourceSize] until it fits inside [outputSize], leaving bars.
 *
 * What the sharp frame is drawn at, whatever the fill behind it is.
 */
@InternalFilmstripApi
public fun containScale(
  sourceSize: Size,
  outputSize: Size,
): Float =
  min(
    outputSize.width.toFloat() / sourceSize.width,
    outputSize.height.toFloat() / sourceSize.height,
  )
