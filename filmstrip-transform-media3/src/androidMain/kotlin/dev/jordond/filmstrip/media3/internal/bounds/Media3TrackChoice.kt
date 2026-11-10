package dev.jordond.filmstrip.media3.internal.bounds

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.SampleConsumer
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.toAndroidUri
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which of a source's tracks of one type an export decodes, out of the [formats] an extractor lists, or null when it
 * lists none of that type or media3's pick cannot be found among them.
 *
 * A source with one track of [trackType] decodes that one. One with more asks media3 which it picks, through
 * [media3PickOf], and finds it among [formats] by its track id, or by its place among the tracks of its type when
 * either side names no id.
 *
 * @param formats Each track's format, in the extractor's own order.
 * @param trackType The media3 track type, [C.TRACK_TYPE_AUDIO] or [C.TRACK_TYPE_VIDEO].
 */
internal fun exportedTrackOf(
  source: MediaSource,
  formats: List<MediaFormat>,
  trackType: Int,
): Int? {
  val ofType = formats.indices.filter { trackTypeOf(formats[it]) == trackType }
  if (ofType.size <= 1) return ofType.firstOrNull()
  val pick = media3PickOf(source, trackType) ?: return null
  val id = pick.format.id?.toIntOrNull()
  val byId = ofType.filter { id != null && formats[it].trackIdOrNull() == id }
  return byId.singleOrNull() ?: ofType.getOrNull(pick.placeInType)
}

/**
 * The track media3's export picks of [trackType] in [source], or null without an installed context or when media3
 * cannot read the source or picks no track of that type.
 *
 * The pick is read off an asset loader built the way `Transformer` builds the one its export runs, so it is the
 * export's own answer. The loader is released once each picked track has read its first format, before any sample is
 * decoded.
 */
internal fun media3PickOf(
  source: MediaSource,
  trackType: Int,
): Media3Pick? {
  val context = FilmstripContext.get() ?: return null
  val item = MediaItem.fromUri(source.toAndroidUri())
  val format = loadedFormatOf(context, item, trackType) ?: return null
  val groups =
    try {
      MetadataRetriever.Builder(context, item).build().use { it.retrieveTrackGroups().get() }
    } catch (unread: ExecutionException) {
      // The retriever fails its future when media3 cannot read the source.
      return null
    }
  val ofType = (0 until groups.length).map { groups[it].getFormat(0) }.filter { trackTypeOf(it) == trackType }
  val byId = ofType.indexOfFirst { format.id != null && it.id == format.id }
  return Media3Pick(format, if (byId >= 0) byId else ofType.indexOf(format))
}

/**
 * A track media3 picks.
 *
 * @property format The track's format, as the export's asset loader reads it.
 * @property placeInType Where the track sits among the source's tracks of its type, counted from zero, or -1 when it
 * cannot be found among them.
 */
internal class Media3Pick(
  val format: Format,
  val placeInType: Int,
)

/**
 * Starts the export's asset loader on [item] and waits for the format of the track of [trackType] it adds. Answers
 * null when the loader fails, or adds every track it picked without one of that type.
 */
private fun loadedFormatOf(
  context: Context,
  item: MediaItem,
  trackType: Int,
): Format? {
  val thread = HandlerThread("filmstrip track pick").apply { start() }
  val handler = Handler(thread.looper)
  val picked = CompletableFuture<Format?>()
  var loader: AssetLoader? = null
  handler.post {
    try {
      loader =
        DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, null)
          .createAssetLoader(
            EditedMediaItem.Builder(item).build(),
            thread.looper,
            TrackListener(trackType, picked),
            AssetLoader.CompositionSettings(Composition.HDR_MODE_KEEP_HDR, false),
          ).also { it.start() }
    } catch (failed: RuntimeException) {
      picked.completeExceptionally(failed)
    }
  }
  return try {
    picked.get()
  } catch (failed: ExecutionException) {
    null
  } finally {
    handler.post {
      loader?.release()
      thread.quitSafely()
    }
  }
}

/**
 * Completes [picked] with the first format of [trackType] the loader adds, or with null once it has added as many
 * tracks as it counted without one, or when it fails.
 */
private class TrackListener(
  private val trackType: Int,
  private val picked: CompletableFuture<Format?>,
) : AssetLoader.Listener {
  private val counted = AtomicInteger(-1)
  private val added = AtomicInteger(0)

  override fun onDurationUs(durationUs: Long) = Unit

  override fun onTrackCount(trackCount: Int) {
    counted.set(trackCount)
    completeOnceAllAdded()
  }

  override fun onTrackAdded(
    inputFormat: Format,
    supportedOutputTypes: Int,
  ): Boolean {
    if (trackTypeOf(inputFormat) == trackType) picked.complete(inputFormat)
    added.incrementAndGet()
    completeOnceAllAdded()
    // Asking for encoded samples keeps the loader from opening a decoder it is released before using.
    return false
  }

  override fun onOutputFormat(format: Format): SampleConsumer? = null

  override fun onError(exportException: ExportException) {
    picked.complete(null)
  }

  private fun completeOnceAllAdded() {
    if (added.get() == counted.get()) picked.complete(null)
  }
}

private fun trackTypeOf(format: MediaFormat): Int = MimeTypes.getTrackType(format.getString(MediaFormat.KEY_MIME))

private fun trackTypeOf(format: Format): Int = MimeTypes.getTrackType(format.sampleMimeType)

private fun MediaFormat.trackIdOrNull(): Int? =
  if (containsKey(MediaFormat.KEY_TRACK_ID)) getInteger(MediaFormat.KEY_TRACK_ID) else null
