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
 * It is asked for the oriented frame, which is what every other browser decode of the same file
 * produces, so the bounds it reports are already the ones the picture is shown at and there is no
 * separate orientation to apply.
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

  return try {
    ImageMeasurement(
      size = Size(bitmap.width, bitmap.height),
      exifOrientation = EXIF_ORIENTATION_NORMAL,
      format = blob.type.substringAfterLast('/'),
    )
  } finally {
    bitmap.close()
  }
}

/**
 * The blob the bitmap is decoded from, or null for a source a browser cannot reach.
 *
 * A path names a filesystem no browser has. A URI is fetched, which is what an object URL minted
 * from a picked file already is.
 */
private suspend fun ImageSource.asBlob(): Blob? =
  when (this) {
    is ImageSource.Bytes -> {
      blobOf(bytes, "")
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
 * Named rather than left to the default, because a browser that defaults the other way would hand
 * back a sideways photo's stored bounds and nothing would say so.
 */
internal expect fun orientedBitmapOptions(): JsAny
