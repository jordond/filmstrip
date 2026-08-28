package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.export.ExportError
import kotlin.time.Duration

/**
 * A decoded frame, owned by the caller.
 *
 * The pixels stay in whatever the platform decoded them into. Call [close] when you are done with
 * the frame, from Kotlin or from Swift. Nothing else releases it for you.
 *
 * Use [toRgba8888] from Kotlin. Swift callers should use `toNSData()` on the Apple form of this
 * class instead, which reads the same pixels without a per-byte bridged call.
 */
public expect class PlatformImage : AutoCloseable {
  /**
   * Frame width in pixels.
   */
  public val widthPx: Int

  /**
   * Frame height in pixels.
   */
  public val heightPx: Int

  /**
   * Copies the pixels out as tightly packed RGBA_8888, row-major, with no row padding.
   *
   * Always a copy. Prefer handing the image itself onward when you can.
   *
   * @return The frame's pixels, `widthPx * heightPx * 4` bytes long.
   */
  public fun toRgba8888(): ByteArray

  /**
   * Releases the underlying pixels. Idempotent, and using the image afterwards is a programming
   * error.
   */
  override fun close()
}

/**
 * A single frame rendered through a composition's effect chain.
 *
 * A frame that could not be produced is a [Failure] arm rather than a thrown exception, so branch
 * on it.
 */
public sealed interface FrameResult {
  /**
   * The frame was produced.
   *
   * @property image The rendered frame. The caller owns it and must [PlatformImage.close] it.
   * @property presentationTime The composition time that was actually rendered, which may sit
   *   a little either side of the time that was asked for.
   * @property colorSpace The colour space the frame was rendered in.
   */
  @Poko
  public class Success(
    public val image: PlatformImage,
    public val presentationTime: Duration,
    public val colorSpace: ColorSpace,
  ) : FrameResult

  /**
   * The frame could not be produced.
   *
   * @property error Why the render failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : FrameResult
}
