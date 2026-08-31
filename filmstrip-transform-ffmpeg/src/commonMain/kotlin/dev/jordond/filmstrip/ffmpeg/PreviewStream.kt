package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * Rendered preview frames, arriving in composition order off a running ffmpeg process.
 *
 * The frames are the ones the graph an export would run emits, in the layout
 * [dev.jordond.filmstrip.player.ReadbackFrame.pixels] documents: tightly packed RGBA_8888, row
 * major, no row padding, `size.width * size.height * 4` bytes each.
 *
 * One stream is one process. It cannot jump, because ffmpeg settles a seek as it starts, so a
 * caller that needs a different position opens another and closes this one.
 */
@InternalFilmstripApi
public interface PreviewStream {
  /**
   * The frame size, which is the composition's output frame.
   */
  public val size: Size

  /**
   * The composition time the first frame carries.
   *
   * Zero for a composition whose timeline an input seek cannot window, which a caller that asked
   * for a later position reads forward to.
   */
  public val startPosition: Duration

  /**
   * Reads the next frame.
   *
   * @return The frame's pixels, or null once the stream ends.
   */
  public suspend fun next(): ByteArray?

  /**
   * What the process wrote to stderr, for a stream that ended sooner than expected.
   */
  public fun errors(): String

  /**
   * Kills the process and releases what it was reading. Idempotent.
   */
  public fun close()
}

/**
 * Whether a preview could be opened.
 */
@InternalFilmstripApi
public sealed interface PreviewStreamResult {
  /**
   * The process is running and its first frame is on the way.
   *
   * @property stream The frames, which the caller closes.
   */
  @InternalFilmstripApi
  public class Opened(
    public val stream: PreviewStream,
  ) : PreviewStreamResult

  /**
   * Nothing was spawned.
   *
   * @property error Why the edit cannot be previewed here.
   */
  @InternalFilmstripApi
  public class Refused(
    public val error: ExportError,
  ) : PreviewStreamResult
}
