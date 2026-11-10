package dev.jordond.filmstrip.effects.filters.camcorder

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Draws a camcorder's on screen display over the frame, like a blinking `REC`, the date and a tape counter.
 *
 * The text comes from a pixel font filmstrip draws itself, white with a black edge and the record dot in red, so it
 * looks the same on every backend and needs no platform font. Each [OsdElement] sits in a corner inside the title safe
 * margin. The date, clock and counter read the frame's composition time, so a seek shows the reading that time had.
 *
 * Sizes are fractions of the frame height, so a smaller preview shows the export scaled down.
 *
 * The effect runs over the fill and under any overlay at the same scope. Declared ahead of a `Vhs`, which is what
 * `vhs { camcorder { } }` does, the display is recorded on the tape and degraded with it. Declared after one, it stays
 * clean, like a VCR's playback display.
 *
 * An HDR source tone maps to SDR first. Register the renderer with `filters()` on the builder.
 *
 * @property elements The lines on screen. With none the effect leaves the frame alone.
 * @property recordedAt The date and time the date and clock show at the start of the composition.
 * @property textSize The height of a line of text, as a fraction of the frame height from `0.01f` to `0.2f`.
 * @throws IllegalArgumentException when [textSize] is outside its range.
 */
@Poko
public class Camcorder(
  public val elements: List<OsdElement>,
  public val recordedAt: RecordedAt = DEFAULT_RECORDED_AT,
  public val textSize: Float = DEFAULT_TEXT_SIZE,
) : EffectSpec {
  init {
    require(textSize in MIN_TEXT_SIZE..MAX_TEXT_SIZE) {
      "A camcorder text size has to be within $MIN_TEXT_SIZE to $MAX_TEXT_SIZE, not $textSize."
    }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = elements.isEmpty()

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.camcorder"

    /**
     * The text size a [Camcorder] uses unless it is given one.
     */
    public const val DEFAULT_TEXT_SIZE: Float = OsdParams.TEXT_SIZE

    /**
     * The date and time a [Camcorder] starts at unless it is given one.
     */
    public val DEFAULT_RECORDED_AT: RecordedAt = RecordedAt(1997, 6, 14, 15, 32, 0)

    private const val MIN_TEXT_SIZE = 0.01f
    private const val MAX_TEXT_SIZE = 0.2f
  }
}
