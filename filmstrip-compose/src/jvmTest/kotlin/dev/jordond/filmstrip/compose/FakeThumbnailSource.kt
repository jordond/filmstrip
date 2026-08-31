package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import java.awt.image.BufferedImage
import kotlin.time.Duration

/**
 * A source with no decoder under it, recording what the strip asks of it.
 *
 * Requests are held rather than answered while [autoDeliver] is off, which is what lets a test move
 * the window with one in flight. The dispatcher serialises, so there is never more than one.
 */
internal class FakeThumbnailSource : ThumbnailSource {
  private var pending: Pending? = null

  val requested: MutableList<Duration> = mutableListOf()
  val cancelled: MutableList<Duration> = mutableListOf()
  val images: MutableList<PlatformImage> = mutableListOf()

  var autoDeliver: Boolean = true

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    requested += request.position
    val entry = Pending(request.position, callback)
    pending = entry
    if (autoDeliver) deliver()

    return Cancellable {
      if (entry.settled) return@Cancellable
      entry.settled = true
      cancelled += entry.position
      if (pending === entry) pending = null
    }
  }

  /**
   * Answers the request in flight, if there is one still waiting.
   */
  fun deliver() {
    val entry = pending ?: return
    if (entry.settled) return
    entry.settled = true
    pending = null

    val image = PlatformImage(BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB))
    images += image
    entry.callback.onThumbnail(ThumbnailResult.Success(image, entry.position))
  }

  private class Pending(
    val position: Duration,
    val callback: ThumbnailCallback,
  ) {
    var settled: Boolean = false
  }

  companion object {
    const val FRAME_WIDTH: Int = 16
    const val FRAME_HEIGHT: Int = 9

    /**
     * What one frame from this source costs the strip's cache.
     */
    const val FRAME_BYTES: Long = FRAME_WIDTH.toLong() * FRAME_HEIGHT * BYTES_PER_PIXEL
  }
}

/**
 * A `Filmstrip` whose only backend is [source].
 */
internal fun filmstripWith(source: FakeThumbnailSource): Filmstrip =
  Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

/**
 * Whether this image's pixels have been released.
 */
internal val PlatformImage.isClosed: Boolean get() = widthPx == 0
