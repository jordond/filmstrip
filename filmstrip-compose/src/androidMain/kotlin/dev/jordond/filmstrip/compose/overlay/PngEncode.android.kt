package dev.jordond.filmstrip.compose.overlay

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

// A layer reads back as a hardware bitmap, which compress takes as it is.
internal actual fun ImageBitmap.encodePng(): ByteArray {
  val out = ByteArrayOutputStream()
  check(asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "The bitmap did not encode." }
  return out.toByteArray()
}

// PNG is lossless and ignores the quality, which compress still asks for.
private const val PNG_QUALITY = 100
