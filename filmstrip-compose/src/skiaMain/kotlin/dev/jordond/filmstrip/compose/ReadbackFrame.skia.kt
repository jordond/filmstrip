package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.jordond.filmstrip.player.ReadbackFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// The readback contract is tightly packed RGBA_8888 with no row padding, which is exactly what
// Skia's RGBA_8888 raster wants, so this is one copy and no reformatting.
internal actual fun ReadbackFrame.toImageBitmap(): ImageBitmap? {
  if (size.width <= 0 || size.height <= 0) return null
  val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
  return Image.makeRaster(info, pixels, size.width * BYTES_PER_PIXEL).toComposeImageBitmap()
}

private const val BYTES_PER_PIXEL = 4
