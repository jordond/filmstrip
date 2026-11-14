package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

// Where one clip's pixels land in the output frame, derived once. A crop that letterboxes or stretches makes a canvas
// the clip's frame is placed in, and the clip's own fit then places the frame the chain leaves in the output frame.
// Every backend draws from these rects and none of them works the placement out again.

/**
 * Where one clip's pixels land, worked out from its own chain of geometry.
 *
 * A [Crop] whose fit is [Fit.Contain] or [Fit.Stretch] makes a canvas of its aspect and places the frame it is handed
 * inside it. Every effect after that crop runs on the canvas, bars included. A backend only builds a canvas when an
 * effect after its crop reads it, which is any geometry but a [Flip] or a [Scale], and any spatial or composite effect.
 * A canvas nothing reads is folded into [drawnRect] instead. The clip's [fit] places the frame the whole chain leaves
 * in the output frame.
 *
 * @property fit The clip's own fit, the last [Scale] across its clip, track and composition geometry, or
 * [Fit.Contain] when none of them has one.
 * @property canvases Every canvas a backend builds, in chain order. Each is built right after its crop and the effects
 * after that crop run on it.
 * @property drawnSize The frame the chain leaves, in whole pixels of the frame the planner hands the chain. That is the
 * last canvas built when nothing after it changed its size, or the clip's content when a canvas is folded.
 * @property drawnRect Where the frame of [drawnSize] lands in the output frame, with any canvas nothing reads folded
 * in. A [Fit.Crop] placement runs past the frame's edges.
 * @property canvasInFrame Where the frame the planner measures at the end of the chain lands in the output frame under
 * [fit]. That is [drawnRect] when no canvas is folded.
 * @property leavesBars Whether any part of the output frame, or of a canvas a later effect can move into view, is left
 * for the fill to show through. A bar under one pixel of the natural frame is no bar.
 */
@InternalFilmstripApi
public class ClipPlacement(
  public val fit: Fit,
  public val canvases: List<CanvasPlacement>,
  public val drawnSize: Size,
  public val drawnRect: NormalizedRect,
  public val canvasInFrame: NormalizedRect,
  public val leavesBars: Boolean,
) {
  /**
   * Whether a backend has to build at least one canvas inside the clip's chain.
   */
  public val readsCanvas: Boolean get() = canvases.isNotEmpty()

  @InternalFilmstripApi
  public companion object {
    /**
     * A clip whose frame already has the output's aspect, drawn edge to edge under the default fit.
     */
    public val Filling: ClipPlacement =
      ClipPlacement(
        fit = Fit.Contain,
        canvases = emptyList(),
        drawnSize = Size(1, 1),
        drawnRect = NormalizedRect.Full,
        canvasInFrame = NormalizedRect.Full,
        leavesBars = false,
      )
  }
}

/**
 * One canvas a letterboxing or stretching crop makes and a later effect reads.
 *
 * @property contentInCanvas Where the frame entering the crop lands in the canvas. A run of such crops with nothing
 * reading between them is folded into one, so this can place the frame through several of them.
 * @property size The canvas, in whole pixels of the frame the planner hands the crop that makes it. A backend that
 * builds the canvas in whole pixels builds it at this size.
 * @property chainIndex Where the crop that makes this canvas sits in the clip's chain, its stages flattened in
 * canonical order.
 */
@InternalFilmstripApi
public class CanvasPlacement(
  public val contentInCanvas: NormalizedRect,
  public val size: Size,
  internal val chainIndex: Int,
) {
  /**
   * The aspect of [size], width over height, which a backend that builds the canvas at any size it likes keeps.
   */
  public val aspect: Float get() = size.aspect
}

/**
 * The placement of a clip whose source frame is [sourceSize], run through [stages] and written to a frame of
 * [naturalSize].
 *
 * Each stage is sorted into canonical order on its own and the stages run in the order given, the same fold
 * [frameThrough] makes, so a clip's own and its track's geometry runs before the composition's.
 */
@InternalFilmstripApi
public fun placementOf(
  sourceSize: Size,
  stages: List<List<EffectSpec>>,
  naturalSize: Size,
): ClipPlacement {
  val chain = stages.flatMap { canonicalOrder(it) }
  val canvases = mutableListOf<CanvasPlacement>()
  // The frame the planner measures, the frame a backend really holds, and the canvas no effect has read yet.
  var size = sourceSize
  var held = sourceSize
  var open: CanvasPlacement? = null
  chain.forEachIndexed { index, spec ->
    val next = frameAfter(spec, size)
    val pending = open
    when {
      spec.makesCanvas() -> {
        val placed = placeIn(size, next, (spec as Crop).fit)
        open = CanvasPlacement(pending?.contentInCanvas?.placedIn(placed) ?: placed, next, index)
      }
      pending == null -> {
        held = next
      }
      spec.readsCanvas() -> {
        canvases += pending
        open = null
        held = next
      }
      else -> {
        if (spec is Flip) {
          open =
            CanvasPlacement(pending.contentInCanvas.flipped(spec.axis), pending.size, pending.chainIndex)
        }
        held = frameAfter(spec, held)
      }
    }
    size = next
  }
  val fit = chain.filterIsInstance<Scale>().lastOrNull()?.fit ?: Fit.Contain
  val canvasInFrame = placeIn(size, naturalSize, fit)
  val drawnRect = open?.contentInCanvas?.placedIn(canvasInFrame) ?: canvasInFrame
  val leavesBars = !drawnRect.covers(naturalSize) || canvases.any { !it.contentInCanvas.covers(it.size) }
  return ClipPlacement(fit, canvases, held, drawnRect, canvasInFrame, leavesBars)
}

/**
 * Where a frame of [inner] lands in a frame of [outer] under [fit], as a fraction of [outer].
 */
@InternalFilmstripApi
public fun placeIn(
  inner: Size,
  outer: Size,
  fit: Fit,
): NormalizedRect {
  if (inner.width <= 0 || inner.height <= 0 || outer.width <= 0 || outer.height <= 0) return NormalizedRect.Full
  val scale =
    when (fit) {
      Fit.Contain -> containScale(inner, outer)
      Fit.Crop -> coverScale(inner, outer)
      Fit.Stretch -> return NormalizedRect.Full
    }
  val width = inner.width * scale / outer.width
  val height = inner.height * scale / outer.height
  val left = (1f - width) / 2f
  val top = (1f - height) / 2f
  return NormalizedRect(left, top, left + width, top + height)
}

/**
 * This rect, measured in a frame that itself sits at [outer], measured in the frame [outer] is a fraction of.
 */
@InternalFilmstripApi
public fun NormalizedRect.placedIn(outer: NormalizedRect): NormalizedRect =
  NormalizedRect(
    left = outer.left + left * outer.width,
    top = outer.top + top * outer.height,
    right = outer.left + right * outer.width,
    bottom = outer.top + bottom * outer.height,
  )

// Whether this rect leaves under one pixel of a frame of [frame] uncovered on each axis.
internal fun NormalizedRect.covers(frame: Size): Boolean {
  val openWidth = (left.coerceAtLeast(0f) + (1f - right).coerceAtLeast(0f)) * frame.width
  val openHeight = (top.coerceAtLeast(0f) + (1f - bottom).coerceAtLeast(0f)) * frame.height
  return width > 0f && height > 0f && openWidth < 1f && openHeight < 1f
}

internal fun NormalizedRect.flipped(axis: FlipAxis): NormalizedRect =
  when (axis) {
    FlipAxis.Horizontal -> NormalizedRect(1f - right, top, 1f - left, bottom)
    FlipAxis.Vertical -> NormalizedRect(left, 1f - bottom, right, 1f - top)
  }

internal fun EffectSpec.makesCanvas(): Boolean = this is Crop && fit != Fit.Crop

// Whether this effect lands somewhere different on a canvas than on the frame inside it. A flip of a centred frame
// and a scale of the whole canvas move nothing relative to the canvas, and a color effect reads no position.
internal fun EffectSpec.readsCanvas(): Boolean =
  when (stage) {
    EffectStage.Geometry -> this !is Flip && this !is Scale
    EffectStage.Color -> false
    EffectStage.Spatial, EffectStage.Composite -> true
  }
