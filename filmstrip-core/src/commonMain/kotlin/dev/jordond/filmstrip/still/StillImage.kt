package dev.jordond.filmstrip.still

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
import kotlin.time.Duration

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
    public val error: FilmstripError,
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
  outputHeightRefusal(spec.heightPx)?.let { return StillBytes.Failure(it) }
  val size = stillSizeOf(Size(widthPx, heightPx), spec.heightPx)
  if (size.width <= 0 || size.height <= 0) {
    return StillBytes.Failure(FilmstripError.SourceUnreadable(FRAME, CLOSED_FRAME))
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
  outputHeightRefusal(spec.heightPx)?.let { return StillResult.Failure(it) }
  val encoded =
    when (val rendered = frame(composition, at, spec.heightPx)) {
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
 * @param heightPx The height to render at, in pixels. Null renders at the source's own height, and a
 *   height of zero or less fails with [FilmstripError.InvalidComposition].
 * @return The frame, which the caller owns and must close, or why it could not be produced.
 */
public suspend fun FrameRenderer.frame(
  source: MediaSource,
  at: Duration,
  heightPx: Int? = null,
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

private const val FRAME = "PlatformImage"

private const val CLOSED_FRAME = "The frame holds no pixels. It has been closed, or it never had any."
