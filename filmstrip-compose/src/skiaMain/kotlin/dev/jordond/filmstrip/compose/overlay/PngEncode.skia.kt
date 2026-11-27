package dev.jordond.filmstrip.compose.overlay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun ImageBitmap.encodePng(): ByteArray {
  val encoded = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
  return checkNotNull(encoded) { "The bitmap did not encode." }.bytes
}
