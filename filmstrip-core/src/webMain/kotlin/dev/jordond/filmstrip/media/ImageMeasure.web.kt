@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * A browser has no header-only image reader, so `createImageBitmap` is what answers, and the bitmap
 * is closed the moment its bounds have been read.
 *
 * It hands back the frame the picture is shown at and there is no longer a way to ask it for the
 * stored one, because `imageOrientation` has no value left that declines the file's own tag. That is
 * the browser's own limit, so the tag is read off the head of the blob's bytes and the shown bounds
 * are turned back through [codedSizeOf] into the stored bounds every other target reports.
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

  val orientation = exifOrientationOf(blob.readHeader())

  return ImageMeasurement(
    size = codedSizeOf(shown, imageRotationOf(orientation)),
    exifOrientation = orientation,
    format = blob.type.substringAfterLast('/'),
  )
}

/**
 * The head of the blob, or nothing when it cannot be read back.
 *
 * Bounded by a slice, because an EXIF block sits ahead of the pixels and a photo is a great deal
 * larger than one.
 */
private suspend fun Blob.readHeader(): ByteArray =
  try {
    slice(0, IMAGE_HEADER_BYTES).arrayBuffer().await().toByteArray()
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
