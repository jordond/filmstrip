package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.describe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreImage.CIImage
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.create

/**
 * The stills a chain draws, opened once each and held for as long as the chain runs.
 *
 * The filter handler is called once per output frame, so a photo opened there would be read
 * hundreds of times over one export. Holding the same `CIImage` is also what lets the context
 * rendering it keep the pixels it already decoded.
 *
 * Read from AVFoundation's render queue, which is never the one a lowering ran on, so the map is
 * locked rather than left to whichever frame arrives first.
 */
internal class CoreImageStills {
  private val lock = NSLock()

  private val opened = mutableMapOf<Any, CIImage>()

  /**
   * [source] as a Core Image image.
   *
   * @throws AppleLoweringFailure when the image cannot be opened, since a span with nothing to draw
   *   would otherwise pass the seed's own frame off as the photo.
   */
  fun of(source: ImageSource): CIImage {
    val key = source.key()
    lock.lock()
    try {
      opened[key]?.let { return it }
      val image = source.open() ?: throw AppleLoweringFailure(unreadable(source))
      opened[key] = image
      return image
    } finally {
      lock.unlock()
    }
  }
}

/**
 * What this source is looked up by, which is the buffer itself for encoded bytes.
 *
 * [ImageSource.Bytes] compares and hashes over its whole array, and the lookup runs once per output
 * frame, so keying on the source would walk a multi-megabyte photo for every frame of its span. An
 * array keys on identity, and every span drawing the same photo carries the same buffer.
 */
private fun ImageSource.key(): Any =
  when (this) {
    is ImageSource.Path -> "path:$path"
    is ImageSource.Uri -> "uri:$uri"
    is ImageSource.Bytes -> bytes
  }

/**
 * Opens this source as a Core Image image, or null when nothing here can read it.
 *
 * Nothing is decoded yet. A `CIImage` over a URL or over data is a recipe, and the pixels are read
 * the first time a context renders it. It comes back the way up the file stored it, since the
 * rotation the header asked for is what the clip's span already carries and bakes in.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ImageSource.open(): CIImage? =
  when (this) {
    is ImageSource.Path -> {
      CIImage.imageWithContentsOfURL(NSURL.fileURLWithPath(path))
    }
    is ImageSource.Uri -> {
      NSURL.URLWithString(uri)?.let { CIImage.imageWithContentsOfURL(it) }
    }
    is ImageSource.Bytes -> {
      if (bytes.isEmpty()) {
        null
      } else {
        bytes.usePinned { pinned ->
          CIImage.imageWithData(NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()))
        }
      }
    }
  }

private fun unreadable(source: ImageSource): String =
  "${source.describe()} is held on the timeline as a still, and Core Image could not open it. It " +
    "is either missing or in a format this platform does not decode."
