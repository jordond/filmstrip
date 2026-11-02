@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.ByteRangeRead
import dev.jordond.filmstrip.internal.pictureExtentOf
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * A browser has no header-only image reader, so `createImageBitmap` is what answers whether the picture decodes, and
 * the bitmap is closed the moment its bounds have been read.
 *
 * The bounds it hands back are the frame the picture is shown at, turned by whichever tags that browser chooses to
 * honour, so the stored bounds and the tag are read out of the picture itself instead. Bytes are read straight from
 * the array and a fetched picture from slices of its blob, through [pictureExtentOf] and [exifOrientationOf]. Only a
 * picture whose container core reads no size from falls back to the shown bounds, turned back through [codedSizeOf]
 * by the tag that was read.
 */
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement? {
  val blob = image.asBlob() ?: return null

  val bitmap =
    try {
      createImageBitmap(blob, orientedBitmapOptions()).await()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (undecodable: Throwable) {
      return null
    }

  val shown =
    try {
      Size(bitmap.width, bitmap.height)
    } finally {
      bitmap.close()
    }

  val orientation: Int
  val stored: Size?
  if (image is ImageSource.Bytes) {
    orientation = exifOrientationOf(image.bytes)
    stored = pictureExtentOf(image.bytes)
  } else {
    val read: ByteRangeRead = { start, count -> blob.readRange(start, count) }
    orientation = exifOrientationOf(blob.size.toLong(), read)
    stored = pictureExtentOf(blob.size.toLong(), read)
  }

  return ImageMeasurement(
    size = stored ?: codedSizeOf(shown, imageRotationOf(orientation)),
    exifOrientation = orientation,
    format = blob.type.substringAfterLast('/'),
  )
}

/**
 * Up to [count] bytes of the blob from [start], fewer when it ends first, or nothing when they cannot be read back.
 *
 * Each read is a slice, so only the bytes asked for leave the blob and the pixels around a tag are never copied out.
 */
private suspend fun Blob.readRange(
  start: Long,
  count: Int,
): ByteArray =
  try {
    slice(start.toDouble(), (start + count).toDouble()).arrayBuffer().await().toByteArray()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unreadable: Throwable) {
    ByteArray(0)
  }

/**
 * The blob the bitmap is decoded from, or null for a source a browser cannot reach.
 *
 * A path names a filesystem no browser has. A URI is fetched, which is what an object URL minted
 * from a picked file already is, and it arrives carrying the type it was minted with. Bytes arrive
 * carrying none, so the type is read out of the bytes themselves and the two paths agree on it.
 */
private suspend fun ImageSource.asBlob(): Blob? =
  when (this) {
    is ImageSource.Bytes -> {
      blobOf(bytes, imageMediaTypeOf(bytes))
    }
    is ImageSource.Path -> {
      null
    }
    is ImageSource.Uri -> {
      try {
        fetch(uri)
          .await()
          .takeIf { it.ok }
          ?.blob()
          ?.await()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (unreachable: Throwable) {
        null
      }
    }
  }

/**
 * The decoded image, held only long enough to read its bounds.
 */
internal external interface ImageBitmap : JsAny {
  val width: Int

  val height: Int

  fun close()
}

internal external fun createImageBitmap(
  image: JsAny,
  options: JsAny,
): Promise<ImageBitmap>

/**
 * Enough of a fetch response to pull a blob out of it.
 */
internal external interface FetchResponse : JsAny {
  val ok: Boolean

  fun blob(): Promise<Blob>
}

internal external fun fetch(input: String): Promise<FetchResponse>

/**
 * The options object that asks `createImageBitmap` to apply the file's own orientation.
 *
 * Named rather than left to the default, so that the frame handed back is the oriented one on every
 * browser and [codedSizeOf] is turning back a frame that was definitely turned.
 */
internal expect fun orientedBitmapOptions(): JsAny
