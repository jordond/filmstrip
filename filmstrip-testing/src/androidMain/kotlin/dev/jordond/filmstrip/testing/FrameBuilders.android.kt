package dev.jordond.filmstrip.testing

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.media.PlatformImage

public actual fun testPlatformImage(
  widthPx: Int,
  heightPx: Int,
): PlatformImage {
  val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(Color.BLACK)
  return PlatformImage(bitmap)
}
