package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko

/**
 * One part of an edit that renders nothing, which filmstrip left out of the export rather than run.
 *
 * The output is exactly what the caller asked for, so a notice is not an [Adjustment], does not change the
 * [Verdict] and gives [ExportSpec.strict] nothing to refuse. It is there so an edit that looks like it should
 * change the frame or the audio can say why it did not. Leaving it out also means it costs nothing, so a clip
 * whose only effects are inert can still copy its streams across.
 *
 * @property subject What was left out: an effect id, `fill`, `fadeIn` or `fadeOut`.
 * @property reason Why it renders nothing.
 * @property message A human-readable description naming where in the edit it sits.
 */
@Poko
public class InertNotice(
  public val subject: String,
  public val reason: InertReason,
  public val message: String,
)

/**
 * Why an [InertNotice] renders nothing.
 *
 * More members may arrive as filmstrip learns to spot more of these, so a consumer must handle one it does not
 * recognise.
 */
public enum class InertReason {
  /**
   * The effect leaves every pixel as it was, like a brightness of one, a crop to the aspect the frame already has,
   * a whole turn, or a fill no frame ever shows.
   */
  NoChangeToFrame,

  /**
   * The overlay is never drawn, because its opacity is zero, its text is empty or its window falls outside the run it
   * is drawn over.
   */
  NeverVisible,

  /**
   * The fade or audio effect has no audio to apply to, because the output keeps no audio, the composition is muted,
   * or the track or clip carries none.
   */
  NoStreamToApplyTo,

  /**
   * The audio effect leaves every sample as it was, as configured.
   */
  NoChangeToAudio,
}
