package dev.jordond.filmstrip.compose.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

class ComposedOverlayParityTest : ComposedOverlayParityContract() {
  override fun decodePng(bytes: ByteArray): ImageBitmap =
    checkNotNull(
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
    ) { "The baked PNG did not decode." }.asImageBitmap()
}
