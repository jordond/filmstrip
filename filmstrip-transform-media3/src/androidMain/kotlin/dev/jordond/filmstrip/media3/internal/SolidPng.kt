package dev.jordond.filmstrip.media3.internal

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import dev.jordond.filmstrip.geometry.Size
import java.io.ByteArrayOutputStream

/**
 * A `data:` URI of a PNG [size] pixels across, every pixel the packed ARGB [color].
 *
 * media3 reads it through its default data source like any other picture, so a solid reaches the chain as a decoded
 * image at the size the plan laid it at. A few of the most recent are kept, since a preview lowers the same edit again
 * on every read.
 */
internal fun solidPngUri(
  color: Int,
  size: Size,
): Uri =
  synchronized(recent) {
    recent.getOrPut(color to size) {
      Uri.parse(PNG_DATA_PREFIX + Base64.encodeToString(solidPng(color, size), Base64.NO_WRAP))
    }
  }

private fun solidPng(
  color: Int,
  size: Size,
): ByteArray {
  val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
  try {
    bitmap.eraseColor(color)
    return ByteArrayOutputStream().use { out ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, LOSSLESS, out)) { "A solid could not be written as a PNG." }
      out.toByteArray()
    }
  } finally {
    bitmap.recycle()
  }
}

private val recent =
  object : LinkedHashMap<Pair<Int, Size>, Uri>(KEPT, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Size>, Uri>): Boolean = size > KEPT
  }

private const val PNG_DATA_PREFIX = "data:image/png;base64,"
private const val LOSSLESS = 100
private const val KEPT = 8
private const val LOAD_FACTOR = 0.75f
