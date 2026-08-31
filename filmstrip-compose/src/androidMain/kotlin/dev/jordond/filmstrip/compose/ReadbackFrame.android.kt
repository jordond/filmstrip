package dev.jordond.filmstrip.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.jordond.filmstrip.player.ReadbackFrame
import java.nio.ByteBuffer

internal actual fun ReadbackFrame.toImageBitmap(): ImageBitmap? {
  if (size.width <= 0 || size.height <= 0) return null
  val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
  bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
  // ARGB_8888 is premultiplied by default and copyPixelsFromBuffer moves bytes without converting
  // them. A readback frame is opaque, so saying so is what makes the two agree.
  bitmap.setHasAlpha(false)
  return bitmap.asImageBitmap()
}
