package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.jordond.filmstrip.media.PlatformImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// PlatformImage hands out tightly packed RGBA_8888 with no row padding, which is what Skia's raster
// of the same type wants, so this is one copy and no reformatting.
internal actual fun PlatformImage.toImageBitmap(): ImageBitmap? {
  if (widthPx <= 0 || heightPx <= 0) return null
  val info = ImageInfo(widthPx, heightPx, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
  return Image.makeRaster(info, toRgba8888(), widthPx * BYTES_PER_PIXEL).toComposeImageBitmap()
}

private const val BYTES_PER_PIXEL = 4
