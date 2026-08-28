package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * Cross-cutting facts the pipeline resolved before any effect ran.
 *
 * Every scale-dependent parameter in filmstrip is authored as a fraction of the frame, and these are
 * the values a resolver multiplies it by. Constructed by filmstrip only.
 *
 * @property inputSize The frame size entering this effect, in real pixels. It changes as the
 *   pipeline lowers each effect in canonical order, so a crop sees the size rotation left behind.
 * @property outputSize The composition's final output frame size, in real pixels. A normalised
 *   measurement is multiplied by [inputSize], the frame the effect is drawn on. The two agree for
 *   a composition-scoped effect and differ for a clip-scoped one, which runs before the size stage.
 * @property colorSpace The colour space the pipeline normalises to before the first effect.
 * @property hdrTransfer The transfer function the frame is held in, or null when the pipeline is
 *   working in SDR. An effect authored against an SDR encoding reads this to work out what its
 *   parameter means in the domain the backend is actually holding the frame in.
 * @property renderScale The preview's resolution as a fraction of the export's, `1f` when they
 *   match. Only ever below `1f` in [ExecutionContext.Preview].
 * @property frameRate Frames per second the pipeline is targeting, or null when the source does not
 *   say.
 */
public class Attributes
  @InternalFilmstripApi
  constructor(
    public val inputSize: Size,
    public val outputSize: Size,
    public val colorSpace: ColorSpace,
    public val hdrTransfer: HdrTransfer?,
    public val renderScale: Float,
    public val frameRate: Float?,
  ) {
    override fun toString(): String = "Attributes($inputSize -> $outputSize @ ${renderScale}x)"
  }
