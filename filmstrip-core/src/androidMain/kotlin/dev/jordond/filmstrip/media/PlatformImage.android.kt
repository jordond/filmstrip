package dev.jordond.filmstrip.media

import android.graphics.Bitmap
import dev.jordond.filmstrip.InternalFilmstripApi
import java.nio.ByteBuffer

/**
 * The Android form, wrapping a [Bitmap].
 *
 * The bitmap belongs to this object. Nothing else may hold it after [close].
 */
public actual class PlatformImage
  @InternalFilmstripApi
  constructor(
    private var bitmap: Bitmap?,
  ) : AutoCloseable {
    public actual val widthPx: Int
      get() = bitmap?.width ?: 0

    public actual val heightPx: Int
      get() = bitmap?.height ?: 0

    /**
     * The underlying bitmap, still owned by this object.
     *
     * For a caller who wants to draw the frame rather than copy it. Do not recycle it, call [close].
     *
     * @return the bitmap, or null once [close] has been called.
     */
    public fun asBitmap(): Bitmap? = bitmap

    public actual fun toRgba8888(): ByteArray {
      val source = bitmap ?: return ByteArray(0)
      val buffer = ByteBuffer.allocate(source.width * source.height * BYTES_PER_PIXEL)
      source.copyPixelsToBuffer(buffer)
      return buffer.array()
    }

    actual override fun close() {
      bitmap?.recycle()
      bitmap = null
    }
  }

private const val BYTES_PER_PIXEL = 4
