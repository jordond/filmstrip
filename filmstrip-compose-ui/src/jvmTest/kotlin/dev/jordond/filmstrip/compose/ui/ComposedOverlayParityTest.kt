package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

class ComposedOverlayParityTest : ComposedOverlayParityContract() {
  override fun decodePng(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
