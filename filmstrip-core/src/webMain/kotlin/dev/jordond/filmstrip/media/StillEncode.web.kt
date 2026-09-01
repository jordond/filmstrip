@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A browser encodes through a canvas, and `convertToBlob` is the only asynchronous encoder of the
 * four, which is what makes the whole path suspend.
 *
 * A browser that will not encode the media type that was asked for does not fail: it falls back to
 * PNG and says so on the blob's own type. Reading that back is what refuses WebP where WebP is not
 * there, rather than handing the caller a PNG named `.webp`.
 */
internal actual suspend fun PlatformImage.encode(
  spec: StillSpec,
  size: Size,
): StillBytes {
  val pixels = toRgba8888()
  if (pixels.isEmpty()) return StillBytes.Failure(closedFrame())

  val canvas = OffscreenCanvas(widthPx, heightPx)
  val context = canvas.getContext(CONTEXT_2D) ?: return StillBytes.Failure(noContext())
  context.putImageData(imageDataOf(pixels, widthPx, heightPx), 0, 0)

  val target = canvas.scaledTo(size) ?: return StillBytes.Failure(noContext())
  val mimeType = spec.format.mimeType
  val blob =
    try {
      target.convertToBlob(blobOptions(mimeType, spec.qualityFraction)).await()
    } catch (refused: Throwable) {
      return StillBytes.Failure(ExportError.Underlying(NO_CODE, refused.message ?: REFUSED))
    }

  if (blob.type != mimeType) {
    return StillBytes.Failure(unsupportedStillFormat(spec.format, TARGET))
  }

  return StillBytes.Success(blob.arrayBuffer().await().toByteArray(), size, spec.format)
}

/**
 * A browser has no filesystem, so a still is delivered rather than written. This follows the same
 * convention the browser export engine hands a finished file over on.
 */
internal actual suspend fun writeStill(
  bytes: ByteArray,
  to: MediaSink,
  format: StillFormat,
): StillWrite {
  val blob = blobOf(bytes, format.mimeType)

  return when (to) {
    is MediaSink.Uri -> {
      StillWrite.Success(MediaSink.Uri(URL.createObjectURL(blob)))
    }
    is MediaSink.Path -> {
      blob.download(to.path.substringAfterLast('/').ifBlank { to.path })
      StillWrite.Success(to)
    }
    is MediaSink.Temporary -> {
      val name = temporaryStillName(format)
      blob.download(name)
      StillWrite.Success(MediaSink.Path(name))
    }
  }
}

/**
 * The canvas the blob is taken from: this one when it is already [size], and a second one drawn
 * down to [size] otherwise.
 */
private fun OffscreenCanvas.scaledTo(size: Size): OffscreenCanvas? {
  if (width == size.width && height == size.height) return this

  val scaled = OffscreenCanvas(size.width, size.height)
  val context = scaled.getContext(CONTEXT_2D) ?: return null
  context.drawImage(this, 0, 0, size.width, size.height)
  return scaled
}

/**
 * Saves the still under [filename] through a download anchor.
 *
 * The URL is revoked on the next task rather than after a delay, because the browser has taken its
 * own reference to the blob by the time the click dispatch returns.
 */
private fun Blob.download(filename: String) {
  val url = URL.createObjectURL(this)
  val anchor = document.createElement("a")
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout({ URL.revokeObjectURL(url) }, 0)
}

private fun noContext() = ExportError.Underlying(NO_CODE, "This browser gave back no 2D canvas context.")

private const val CONTEXT_2D = "2d"

private const val TARGET = "This browser"

private const val REFUSED = "The canvas refused to encode the still."
