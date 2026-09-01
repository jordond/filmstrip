package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.StillFormat

/**
 * Every way an export, probe or plan can fail, as branchable values.
 *
 * Nothing in filmstrip's public API throws one of these. Every entry point returns a sealed result
 * carrying the error instead. Cancellation is not an arm: cancelling the collecting scope cancels
 * the export and propagates a `CancellationException`.
 *
 * This hierarchy is open: a backend filmstrip has not shipped yet will fail in ways this list does
 * not name, so arms arrive in minor versions and an exhaustive `when` over them will break.
 */
public sealed interface ExportError {
  /**
   * A human-readable description, safe to log and unsuitable for parsing.
   */
  public val message: String

  /**
   * The device has no encoder for the requested codec.
   *
   * @property codec The codec that was asked for.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class NoEncoder(
    public val codec: VideoCodec,
    override val message: String,
  ) : ExportError

  /**
   * The requested resolution exceeds what the encoder publishes.
   *
   * @property requested The size that was asked for.
   * @property max The largest size this encoder accepts.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class UnsupportedResolution(
    public val requested: Size,
    public val max: Size,
    override val message: String,
  ) : ExportError

  /**
   * A decoder rejected the source while being configured, before any transcoding started.
   *
   * @property codec The source codec the decoder was configured for.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class DecoderRejectedInput(
    public val codec: String,
    override val message: String,
  ) : ExportError

  /**
   * The source is DRM-protected or otherwise not exportable.
   *
   * Also reported when previewing protected content.
   *
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class SourceNotExportable(
    override val message: String,
  ) : ExportError

  /**
   * The composition does not describe a valid graph: instructions that do not tile the timeline, a
   * clip with no readable track, a crop with no area.
   *
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class InvalidComposition(
    override val message: String,
  ) : ExportError

  /**
   * No resolver claimed an effect on this platform.
   *
   * @property specId The id of the effect that went unclaimed.
   * @property message A human-readable description of the failure, naming the backend and the
   *   context the effect was resolved in.
   */
  @Poko
  public class UnsupportedEffect(
    public val specId: String,
    override val message: String,
  ) : ExportError

  /**
   * An effect renders on one path but not the other, so it is refused rather than classified.
   *
   * @property specId The id of the effect that was refused.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class UnpreviewableEffect(
    public val specId: String,
    override val message: String,
  ) : ExportError

  /**
   * A caller-supplied effect is implemented on one platform and not the other.
   *
   * A different axis from preview-versus-export: on each platform the preview and the export agree,
   * and it is Android and Apple that differ.
   *
   * @property specId The id of the effect that is missing on one platform.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class PlatformEffectAsymmetry(
    public val specId: String,
    override val message: String,
  ) : ExportError

  /**
   * Apple only: hardware codec access was revoked because the app was suspended.
   *
   * Neither cancellation nor a bug. Android never emits it.
   *
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class InterruptedByBackgrounding(
    override val message: String,
  ) : ExportError

  /**
   * Not enough free space to write the output.
   *
   * @property requiredBytes How many bytes the output needs, or null when that is not known.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class InsufficientStorage(
    public val requiredBytes: Long?,
    override val message: String,
  ) : ExportError

  /**
   * The source could not be opened or read.
   *
   * @property source The source that could not be read.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class SourceUnreadable(
    public val source: String,
    override val message: String,
  ) : ExportError

  /**
   * The destination could not be opened or written.
   *
   * @property sink The destination that could not be written.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class SinkUnwritable(
    public val sink: String,
    override val message: String,
  ) : ExportError

  /**
   * This target cannot write the still image format that was asked for.
   *
   * [StillFormat.Webp] is the one that is not writable everywhere. Distinct from [BackendMissing],
   * whose [BackendMissing.artifact] is a Maven coordinate: nothing a build file can add fixes this,
   * so a caller that has to run on all four targets asks for another format instead.
   *
   * @property format The format the target refused.
   * @property message A human-readable description naming the target and the format.
   */
  @Poko
  public class UnsupportedStillFormat(
    public val format: StillFormat,
    override val message: String,
  ) : ExportError

  /**
   * A backend that this operation needs was not registered.
   *
   * @property artifact The Maven coordinate of the missing artifact.
   * @property message A human-readable description of the failure, naming the artifact to add and
   *   the builder call that adds it.
   */
  @Poko
  public class BackendMissing(
    public val artifact: String,
    override val message: String,
  ) : ExportError

  /**
   * An external program this backend drives was not found, or is too old to use.
   *
   * Distinct from [BackendMissing], whose [BackendMissing.artifact] is a Maven coordinate: nothing
   * a build file can add fixes this one. A Swift caller matching arms with `as?` gets no compile
   * error for leaving this out, and no Apple backend drives an external program, so it will not
   * reach one.
   *
   * @property tool The program that is missing, such as `ffmpeg`.
   * @property foundVersion What was found, when something was found but was unusable. Null when
   *   nothing was found at all.
   * @property message A human-readable description naming what to install and the minimum version.
   */
  @Poko
  public class ToolchainMissing(
    public val tool: String,
    public val foundVersion: String?,
    override val message: String,
  ) : ExportError

  /**
   * A platform failure filmstrip could not classify, with whatever code the platform gave.
   *
   * @property platformCode The raw error code the platform reported, or [NO_PLATFORM_CODE] when
   *   the platform gave none.
   * @property message A human-readable description of the failure.
   */
  @Poko
  public class Underlying(
    public val platformCode: Int,
    override val message: String,
  ) : ExportError {
    public companion object {
      /**
       * Sentinel for [platformCode] when the platform reported no numeric code.
       */
      public const val NO_PLATFORM_CODE: Int = 0
    }
  }
}
