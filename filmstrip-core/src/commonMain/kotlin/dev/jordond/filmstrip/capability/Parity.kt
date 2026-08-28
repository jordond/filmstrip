package dev.jordond.filmstrip.capability

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.export.ExportError

/**
 * How faithfully the preview reproduces the export, for one effect.
 *
 * Every effect filmstrip applies is one of these two. An effect that renders on only one of the two
 * paths is refused at plan time with [ExportError.UnpreviewableEffect] rather than classified here.
 */
public enum class EffectParity {
  /**
   * Preview and export produce the same pixels, within codec noise.
   *
   * Always exact: geometry (crop rect, rotation, output frame size, letterbox bars, overlay
   * position, scale and anchor), timing (trim points, concatenation boundaries, total duration),
   * presence and absence (mute, track selection, which overlays are drawn), and text layout (line
   * breaks, metrics, measured extent). If any of those differ, that is a bug in filmstrip.
   *
   * This is preview against export on one platform. Android and Apple can still differ from each
   * other, which is reported as [ExportError.PlatformEffectAsymmetry].
   */
  Exact,

  /**
   * Both paths render it, with a bounded, measured divergence named in [ParityNote.message].
   *
   * Colour delta from decoder range and matrix handling, a cheaper preview resampling filter, or
   * glyph antialiasing on a downscaled overlay texture. For text, only glyph antialiasing is in
   * this class: line breaking, metrics and measured extent are [Exact].
   */
  Approximate,
}

/**
 * Why an effect is not [EffectParity.Exact].
 *
 * @property specId The id of the effect this note is about.
 * @property parity The effect's parity.
 * @property message What the divergence is, in human-readable form.
 */
@Poko
public class ParityNote(
  public val specId: String,
  public val parity: EffectParity,
  public val message: String,
)

/**
 * How much information the preview carries about one observable property of the output.
 *
 * Distinct from [EffectParity]: that describes an effect, this describes an [OutputProperty]. An
 * effect can be [EffectParity.Exact] while [OutputProperty.EncoderArtifacts] is [NotPreviewable].
 */
public enum class PreviewFidelity {
  /**
   * The preview is authoritative for this property.
   */
  Exact,

  /**
   * Bounded and measured divergence. Trust the intent, not the last few percent of detail.
   */
  Approximate,

  /**
   * The preview carries no reliable information about this property.
   *
   * Judge it from a short test export instead.
   */
  NotPreviewable,
}

/**
 * An observable characteristic of the output, which the preview may or may not carry information
 * about.
 */
public enum class OutputProperty {
  /**
   * Crop, aspect, letterbox geometry, output frame size.
   */
  Framing,

  /**
   * Trim points, clip boundaries, total duration.
   */
  Timing,

  /**
   * Where overlays land, at what scale and rotation.
   */
  OverlayPlacement,

  /**
   * Line breaks, metrics, measured extent.
   */
  TextLayout,

  /**
   * Grade, range and matrix handling.
   */
  Colour,

  /**
   * Resampling detail on downscale.
   */
  Sharpness,

  /**
   * Glyph edges on a downscaled overlay texture.
   */
  TextAntialiasing,

  /**
   * Quantisation, banding, blocking, group-of-pictures structure.
   */
  EncoderArtifacts,

  /**
   * How HDR looks, which depends on the display rather than on the file.
   */
  HdrAppearance,

  /**
   * Whether frames arrive on time. A dropped preview frame is not an exported one.
   */
  Smoothness,

  /**
   * Clicks or gaps at a clip boundary, which the export normalises away and the preview may not.
   */
  AudioContinuity,
}

/**
 * How much to trust the preview for one [OutputProperty], and why.
 *
 * @property property The characteristic of the output this note is about.
 * @property fidelity How much the preview says about that characteristic.
 * @property message Why, in human-readable form.
 */
@Poko
public class FidelityNote(
  public val property: OutputProperty,
  public val fidelity: PreviewFidelity,
  public val message: String,
)
