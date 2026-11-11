package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.media.PlatformImage

public actual fun testPlatformImage(
  widthPx: Int,
  heightPx: Int,
): PlatformImage =
  PlatformImage(
    widthPx = widthPx,
    heightPx = heightPx,
    pixels = ByteArray(widthPx * heightPx * BYTES_PER_PIXEL) { if (it % BYTES_PER_PIXEL == ALPHA) OPAQUE else 0 },
  )

private const val BYTES_PER_PIXEL = 4
private const val ALPHA = 3
private const val OPAQUE: Byte = -1
