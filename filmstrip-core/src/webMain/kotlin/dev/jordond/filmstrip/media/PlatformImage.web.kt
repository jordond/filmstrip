package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The browser form, holding an RGBA snapshot rather than a live frame.
 *
 * This is the one place the browser cannot honour the common contract. `VideoFrame.copyTo` returns
 * a Promise and there is no synchronous path to a frame's pixels, so the copy the common KDoc calls
 * "always a copy" is paid before the image is constructed rather than inside [toRgba8888]. The
 * signature holds. The sentence about pixels staying in whatever the platform decoded them into
 * does not.
 *
 * A `PlatformImage` costs `widthPx * heightPx * 4` bytes of wasm heap for as long as it is open, so
 * [close] it promptly and do not hold a strip of them.
 */
public actual class PlatformImage
  @InternalFilmstripApi
  constructor(
    public actual val widthPx: Int,
    public actual val heightPx: Int,
    private var pixels: ByteArray?,
  ) : AutoCloseable {
    public actual fun toRgba8888(): ByteArray = pixels?.copyOf() ?: ByteArray(0)

    actual override fun close() {
      pixels = null
    }
  }
