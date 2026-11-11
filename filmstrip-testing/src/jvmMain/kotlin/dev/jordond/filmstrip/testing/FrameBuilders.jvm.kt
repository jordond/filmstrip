package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.media.PlatformImage
import java.awt.image.BufferedImage

public actual fun testPlatformImage(
  widthPx: Int,
  heightPx: Int,
): PlatformImage = PlatformImage(BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB))
