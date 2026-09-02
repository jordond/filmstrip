package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * An image format a still can be encoded in.
 *
 * [Png] and [Jpeg] are writable on every target. [Webp] is not, and says so on its own entry.
 */
@Serializable
public enum class StillFormat {
  /**
   * PNG. Lossless, so [StillSpec.quality] has no effect on it.
   */
  Png,

  /**
   * JPEG. Lossy, and [StillSpec.quality] chooses how lossy.
   */
  Jpeg,

  /**
   * WebP, the one format that is not writable everywhere.
   *
   * Android and a browser encode it. The JDK ships no WebP writer, and Apple's ImageIO reads WebP
   * on systems where it will not write it, so both refuse with
   * [ExportError.UnsupportedStillFormat] naming the target rather than handing back a file that is
   * not WebP. Ask for [Png] or [Jpeg] in code that has to run on all four.
   */
  Webp,
}

/**
 * What an encoded still should look like.
 *
 * @property format The image format to encode in.
 * @property quality How much of the image a lossy [format] keeps, from 0 to 100. A value outside
 *   that range is clamped rather than refused, and [StillFormat.Png] ignores it.
 * @property heightPx The height to encode at, in pixels. The width follows from the frame's aspect.
 *   Null keeps the frame's own height, and so does a value that is not positive. A height that
 *   would scale the frame past the largest still filmstrip encodes is held to that size instead,
 *   so an upscale can grow a frame only so far.
 */
@Serializable
@Poko
public class StillSpec(
  public val format: StillFormat = StillFormat.Jpeg,
  public val quality: Int = DEFAULT_QUALITY,
  public val heightPx: Int? = null,
)

/**
 * An encoded still held in memory, or why there is none.
 *
 * A format this target cannot write is a [Failure] carrying
 * [ExportError.UnsupportedStillFormat], so branch on it rather than reading an empty buffer as an
 * encode that worked.
 */
public sealed interface StillBytes {
  /**
   * The frame was encoded.
   *
   * @property bytes The encoded image, ready to write or upload.
   * @property size The size the image was encoded at, which honours [StillSpec.heightPx].
   * @property format The format [bytes] holds.
   */
  @Poko
  public class Success(
    @Poko.ReadArrayContent public val bytes: ByteArray,
    public val size: Size,
    public val format: StillFormat,
  ) : StillBytes

  /**
   * The frame could not be encoded.
   *
   * @property error Why the encode failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : StillBytes
}

/**
 * A still that was written somewhere, or why it was not.
 *
 * The mirror of [StillBytes] for the one-call [still] form, which delivers the bytes instead of
 * handing them back.
 */
public sealed interface StillResult {
  /**
   * The still was written.
   *
   * @property output Where the still is. A [MediaSink.Temporary] request is resolved to a real
   *   [MediaSink.Path] here.
   * @property size The size the image was encoded at.
   * @property format The format that was written.
   */
  @Poko
  public class Success(
    public val output: MediaSink,
    public val size: Size,
    public val format: StillFormat,
  ) : StillResult

  /**
   * No still was written.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : StillResult
}

/**
 * Encodes this frame as a still image.
 *
 * The frame stays open, so closing it is still the caller's job. A [StillSpec.heightPx] that
 * differs from the frame's own height scales it on the way out, keeping the frame's aspect.
 *
 * @param spec What the encoded still should look like.
 * @return The encoded bytes, or why they could not be produced.
 */
public suspend fun PlatformImage.encodeStill(spec: StillSpec = StillSpec()): StillBytes {
  val size = stillSizeOf(Size(widthPx, heightPx), spec.heightPx)
  if (size.width <= 0 || size.height <= 0) {
    return StillBytes.Failure(ExportError.SourceUnreadable(FRAME, CLOSED_FRAME))
  }
  return encode(spec, size)
}

/**
 * Renders one frame of a composition and writes it as a still image.
 *
 * [FrameRenderer.frame] is asked for the frame at [StillSpec.heightPx], so the still is decoded at the
 * size it is written at rather than at full resolution and shrunk. The frame is closed before this
 * returns.
 *
 * Delivery follows the target. Android, Apple and the JVM write a file, and a
 * [MediaSink.Temporary] comes back as the [MediaSink.Path] it resolved to. A browser has no
 * filesystem, so a [MediaSink.Uri] hands back a `blob:` URL that the caller owns and has to revoke,
 * and a [MediaSink.Path] or [MediaSink.Temporary] starts a download under that name.
 *
 * @param composition The edit to render from.
 * @param at Where in the composition to render.
 * @param to Where to put the still.
 * @param spec What the encoded still should look like.
 * @return Where the still ended up, or why it did not get there.
 */
public suspend fun FrameRenderer.still(
  composition: EditComposition,
  at: Duration,
  to: MediaSink,
  spec: StillSpec = StillSpec(),
): StillResult {
  val encoded =
    when (val rendered = frame(composition, at, spec.heightPx ?: 0)) {
      is FrameResult.Failure -> return StillResult.Failure(rendered.error)
      is FrameResult.Success -> rendered.image.use { it.encodeStill(spec) }
    }

  return when (encoded) {
    is StillBytes.Failure -> {
      StillResult.Failure(encoded.error)
    }
    is StillBytes.Success -> {
      when (val written = writeStill(encoded.bytes, to, spec.format)) {
        is StillWrite.Failure -> StillResult.Failure(written.error)
        is StillWrite.Success -> StillResult.Success(written.output, encoded.size, encoded.format)
      }
    }
  }
}

/**
 * Renders one frame of a single source, with no composition to build by hand.
 *
 * Shorthand for the composition-taking [FrameRenderer.frame], over a single-clip composition built
 * from [source]. Reach for that form directly for anything bigger than one clip.
 *
 * @param source The media to render from.
 * @param at Where in [source] to render.
 * @param heightPx The height to render at, in pixels. Zero renders at the source's own height.
 * @return The frame, which the caller owns and must close, or why it could not be produced.
 */
public suspend fun FrameRenderer.frame(
  source: MediaSource,
  at: Duration,
  heightPx: Int = 0,
): FrameResult = frame(compositionOf { clip(source) }, at, heightPx)

/**
 * Encodes one frame of a single source as a still image, with no composition to build by hand.
 *
 * Shorthand for the composition-taking [still], over a single-clip composition built from
 * [source]. Reach for that form directly for anything bigger than one clip.
 *
 * @param source The media to render from.
 * @param at Where in [source] to render.
 * @param to Where to put the still.
 * @param spec What the encoded still should look like.
 * @return Where the still ended up, or why it did not get there.
 */
public suspend fun FrameRenderer.still(
  source: MediaSource,
  at: Duration,
  to: MediaSink,
  spec: StillSpec = StillSpec(),
): StillResult = still(compositionOf { clip(source) }, at, to, spec)

private const val DEFAULT_QUALITY = 90

private const val FRAME = "PlatformImage"

private const val CLOSED_FRAME = "The frame holds no pixels. It has been closed, or it never had any."
