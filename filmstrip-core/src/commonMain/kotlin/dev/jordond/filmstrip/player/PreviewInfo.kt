package dev.jordond.filmstrip.player

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.geometry.Size

/**
 * What the preview is actually delivering, while the user is watching.
 *
 * @property outputSize The frame size the composition outputs, before any preview-only downscale.
 *   Size the preview surface to this aspect rather than to the source's: the effect pipeline
 *   preserves the composition's aspect and lets the surface letterbox, so a container whose ratio
 *   does not match turns mostly black once effects are switched on.
 * @property renderScale The preview's resolution as a fraction of the export's, `1f` when they
 *   match. Every scale-dependent parameter normalises against this.
 * @property parity The weakest [EffectParity] across the composition's effects.
 * @property parityNotes The per-effect detail behind [parity].
 * @property fidelity Per-property trust. Mostly static, though smoothness moves with load.
 */
@Poko
public class PreviewInfo(
  public val outputSize: Size,
  public val renderScale: Float,
  public val parity: EffectParity,
  public val parityNotes: List<ParityNote>,
  public val fidelity: List<FidelityNote>,
)

/**
 * How hard the preview may work.
 */
public sealed interface PreviewQualityPolicy {
  /**
   * Render at the composition's full output size.
   */
  public data object Full : PreviewQualityPolicy

  /**
   * Render no taller than [heightPx], reporting the resulting scale in [PreviewInfo.renderScale].
   *
   * Scale-dependent parameters still normalise correctly. Perceptual sharpness does not, so read
   * [PreviewInfo.renderScale] before judging detail.
   *
   * @property heightPx The tallest the preview may render, in pixels.
   */
  @Poko
  public class CapHeight(
    public val heightPx: Int,
  ) : PreviewQualityPolicy
}

/**
 * How exact a seek needs to be.
 */
public enum class SeekAccuracy {
  /**
   * Land on the requested frame. Decodes from the preceding sync sample, which can be dozens of
   * frames.
   */
  Exact,

  /**
   * Land on the nearest sync sample. Near-instant, and the right choice while a finger is moving.
   */
  Nearest,
}

/**
 * Which kind of surface the preview renders into, where the platform offers a choice.
 *
 * Android only. Apple has a single path.
 */
public enum class PreviewSurfaceType {
  /**
   * A dedicated surface layer. The default, and the only path with HDR and protected content.
   *
   * It does not respect the UI layer's clipping, alpha or transforms: a rounded-corner clip, an
   * alpha fade or a rotation applied around it does nothing to the video pixels. Design the preview
   * so the video rectangle is axis-aligned and untransformed, with chrome drawn around it.
   */
  Surface,

  /**
   * A texture-backed view, which the UI layer can clip, fade, rotate and animate.
   *
   * Costs GPU time and power, and loses HDR and protected content. Worth it when the video
   * rectangle itself is being transformed, such as a cross-fade, a drag-to-dismiss or a
   * shared-element transition.
   */
  Texture,
}

/**
 * A backend ability that may or may not be present on this device.
 *
 * Query it through [PlayerFeatures.supports] rather than assuming a platform floor. A UI that
 * assumes a feature is always there appears frozen where it is missing, instead of degrading.
 */
public enum class PlayerFeature {
  /**
   * Parameter changes redraw the paused frame at finger speed.
   *
   * False means a change made while paused becomes visible on the next decoded frame, so a host can
   * degrade gracefully and show a crop box outline without live pixel feedback.
   */
  LiveParameterRedraw,

  /**
   * The preview can display HDR without tone-mapping.
   */
  HdrPreview,

  /**
   * A texture-backed surface is available. See [PreviewSurfaceType.Texture].
   */
  TextureSurface,

  /**
   * Stepping by whole frames is supported.
   */
  FrameStepping,

  /**
   * Playback speed can be changed.
   */
  PlaybackSpeed,

  /**
   * Rendered preview frames can be read back. See [PreviewFrameReadback].
   */
  FrameReadback,
}

/**
 * Which [PlayerFeature] values this backend actually has.
 */
public class PlayerFeatures
  @InternalFilmstripApi
  constructor(
    private val supported: Set<PlayerFeature>,
  ) {
    /**
     * Checks for one feature.
     *
     * @return true when [feature] is available on this device.
     */
    public fun supports(feature: PlayerFeature): Boolean = feature in supported

    /**
     * Lists what this backend has, for diagnostics.
     *
     * @return every supported feature.
     */
    public fun all(): Set<PlayerFeature> = supported

    override fun toString(): String = "PlayerFeatures($supported)"
  }
