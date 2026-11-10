package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

/**
 * What the pipeline needs for one clip: a quad, a texture matrix and a color matrix, all lowered
 * from the clip's effects, plus the trim window and the slots it fills on the output frame grid.
 *
 * @property firstSlot The output slot the clip opens on, counted from the start of the composition,
 *   so the render's lead frames come before it. The source's trim start is drawn on this slot.
 * @property quadHalfW Half the quad's width as a fraction of the output's, under the clip's own placement. A contained
 * clip is no wider than the output, a cropped one may be wider, and a stretched one is exactly as wide.
 * @property quadHalfH The same, for height.
 * @property coverHalfW Half the quad's width when the frame the clip's chain leaves is scaled to cover the output by
 * the shared cover scale. This is what a blurred fill's background draws with.
 * @property coverHalfH The same, for height.
 * @property hasBars Whether this clip leaves anything for a blurred fill to show through. A clip whose aspect already
 *   matches the output leaves nothing, nor does one cropped or stretched to fill it or a canvas that fills it, and a
 *   blurred fill skips its background passes for one that does not need them.
 * @property matrix What the quad samples through. That is the clip's source, or its last canvas when it builds any, in
 * which case only the effects after the crop that builds that canvas are folded in, along with the composition's.
 * @property canvases Every canvas a letterboxing or stretching crop builds for the effects after it to read, bars
 * included, in chain order. Empty when nothing after such a crop reads it.
 * @property overlayMatrix What the clip's overlays are drawn through, the geometry that runs after them, so a
 * composition crop or flip moves them with the picture.
 * @property colorMatrix The clip's own color chain as the sixteen floats a GL `mat4` uniform
 *   takes, column-major, applied to `vec4(rgb, 1)`. The identity when the chain grades nothing.
 * @property compositionColorMatrix The composition's color chain, in the same form. The planner
 *   folds a run of color effects per stage and every backend clamps between the two, so the two
 *   matrices stay apart here rather than being multiplied into one.
 * @property frames How many output slots this clip's span covers on the grid at the resolved rate,
 *   which is zero for a span too short to reach the point half way between two slots. The pipeline
 *   walks exactly this many slots, so progress is measured against what really gets encoded.
 * @property heldFrames How many slots past [frames] this clip holds its last covered frame over. That is the
 * composition's held tail on the last clip that covers a slot, and zero on every other. Each held slot is drawn at the
 * last covered slot's time and written at its own.
 * @property overlays The rasters the clip's own chain composites over it, in chain order. Each one is placed on the
 * clip's frame, so it is drawn inside the clip's quad and cut at its edge.
 * @property toneMapFrom The transfer the clip's source carries when the render tone maps it to SDR, which is the curve
 * its frames are read through before the composite. Null for an SDR source and for a render that keeps the grade.
 * @property passStage What the clip draws onto a frame of its own ahead of the composite, or null when no pass of the
 * clip's runs a shader. With one, [matrix], [colorMatrix] and [canvases] are left holding only what runs after it.
 * @property passesUnderFill The shader passes the composition runs over its picture before the fill, in chain order.
 * With any, the clip is drawn on a clear frame of the output's size, these run over it, and the result is laid over the
 * fill.
 * @property speed How fast the slots walk through the trim, which [sourceUsOf] and [outputUsOf] read.
 * @property spanStart Where the clip's span opens on the composition timeline, which its trim start plays at.
 */
internal class RenderedClip(
  val source: MediaSource,
  val trimStart: Duration,
  val trimEndUs: Double,
  val firstSlot: Long,
  val quadHalfW: Float,
  val quadHalfH: Float,
  val coverHalfW: Float,
  val coverHalfH: Float,
  val hasBars: Boolean,
  val matrix: TextureMatrix,
  val canvases: List<ClipCanvas>,
  val overlayMatrix: TextureMatrix,
  val colorMatrix: FloatArray,
  val compositionColorMatrix: FloatArray,
  val frames: Long,
  val heldFrames: Long,
  val overlays: List<WebGlOverlay>,
  val toneMapFrom: HdrTransfer?,
  val passStage: ClipPassStage? = null,
  val passesUnderFill: List<WebGlPass> = emptyList(),
  val speed: Double = 1.0,
  val spanStart: Duration,
) {
  /**
   * Where the trim opens in the source, in microseconds.
   */
  val trimStartUs: Double get() = trimStart.microseconds()

  /**
   * The source time in microseconds that this clip's [slot]th slot shows on a grid of [frameRate], the shared
   * [sourceTimeOfSlot].
   */
  fun sourceUsOf(
    slot: Long,
    frameRate: Int,
  ): Double = sourceTimeOfSlot(trimStart, spanStart, speed, firstSlot + slot, frameRate).microseconds()

  /**
   * How far past where this clip's span opens, in microseconds, the source time [sourceUs] past the trim start lands.
   */
  fun outputUsOf(sourceUs: Double): Double =
    if (speed == 1.0) sourceUs else outputLengthOf(sourceUs.microseconds, speed).toDouble(DurationUnit.MICROSECONDS)
}
