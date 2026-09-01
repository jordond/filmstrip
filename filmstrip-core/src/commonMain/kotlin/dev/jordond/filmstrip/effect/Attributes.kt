package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
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
 * @property layoutSize The frame text is laid out against, in real pixels. Equal to [inputSize] for
 *   an export, and larger for a preview rendering below the frame an export writes. A resolver that
 *   lays text out measures the type and the wrap width against this, then scales the raster it gets
 *   back to [inputSize], so a line breaks on the same word whichever of the two is being drawn.
 * @property colorSpace The colour space the pipeline normalises to before the first effect.
 * @property hdrTransfer The transfer function the frame is held in, or null when the pipeline is
 *   working in SDR. An effect authored against an SDR encoding reads this to work out what its
 *   parameter means in the domain the backend is actually holding the frame in.
 * @property frameRate Frames per second the pipeline is targeting, or null when the source does not
 *   say.
 * @property span The composition time range the frames entering this effect fall in. A clip effect
 *   sees its clip's slot on the timeline, a composition effect sees the whole composition. Every
 *   backend hands an effect composition-relative timestamps, so an effect whose result varies over
 *   its run measures against this rather than against zero.
 */
public class Attributes
  @InternalFilmstripApi
  constructor(
    public val inputSize: Size,
    public val outputSize: Size,
    public val layoutSize: Size,
    public val colorSpace: ColorSpace,
    public val hdrTransfer: HdrTransfer?,
    public val frameRate: Float?,
    public val span: TimeRange,
  ) {
    override fun toString(): String = "Attributes($inputSize -> $outputSize)"
  }
