package dev.jordond.filmstrip.media3.internal.bounds

import android.content.Context
import android.media.MediaFormat
import android.os.Looper
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader

/**
 * The tracks of its source a media3 item plays, carried as the item's tag so every media3 component that builds the
 * item's source can read it.
 *
 * The video is always the source's first video track, which is the one every prober describes. The audio is the one
 * the plan chose for the clip.
 *
 * @property audioOrdinal Where the clip's audio track sits among the source's audio tracks, counted from zero, or null
 * when the source has none.
 */
internal data class ChosenTracks(
  val audioOrdinal: Int?,
)

/**
 * The [ChosenTracks] this item was laid with, or null for an item that is not a clip of a source with tracks, such as
 * a still or a gap.
 */
internal val MediaItem.chosenTracks: ChosenTracks? get() = localConfiguration?.tag as? ChosenTracks

/**
 * The asset loaders an export runs, each one decoding only the tracks its item's [ChosenTracks] name.
 *
 * An item carrying none is loaded the way media3 loads it by default.
 */
internal class ChosenTrackAssetLoaderFactory(
  context: Context,
) : AssetLoader.Factory {
  private val context = context.applicationContext
  private val decoders = DefaultDecoderFactory.Builder(this.context).build()
  private val fallback = DefaultAssetLoaderFactory(this.context, decoders, Clock.DEFAULT, null)

  override fun createAssetLoader(
    editedMediaItem: EditedMediaItem,
    looper: Looper,
    listener: AssetLoader.Listener,
    compositionSettings: AssetLoader.CompositionSettings,
  ): AssetLoader {
    val chosen =
      editedMediaItem.mediaItem.chosenTracks
        ?: return fallback.createAssetLoader(editedMediaItem, looper, listener, compositionSettings)
    return ExoPlayerAssetLoader
      .Factory(context, decoders, Clock.DEFAULT, null, { ChosenTrackSelector(it, chosen) }, null, null)
      .createAssetLoader(editedMediaItem, looper, listener, compositionSettings)
  }
}

/**
 * A track selector that selects the tracks [chosen] names and no other of their type.
 *
 * The audio track is found by its place among the audio tracks media3 lists, which is the order the container lists
 * them in. Every other parameter is the one an export's own selector runs with.
 */
private class ChosenTrackSelector(
  context: Context,
  private val chosen: ChosenTracks,
) : DefaultTrackSelector(context, EXPORT_PARAMETERS) {
  override fun selectVideoTrack(
    mappedTrackInfo: MappedTrackInfo,
    rendererFormatSupports: Array<Array<IntArray>>,
    mixedMimeTypeSupports: IntArray,
    params: Parameters,
    selectedAudioLanguage: String?,
  ): Pair<ExoTrackSelection.Definition, Int>? = mappedTrackInfo.selectionOf(C.TRACK_TYPE_VIDEO, 0)

  override fun selectAudioTrack(
    mappedTrackInfo: MappedTrackInfo,
    rendererFormatSupports: Array<Array<IntArray>>,
    rendererMixedMimeTypeAdaptationSupports: IntArray,
    params: Parameters,
  ): Pair<ExoTrackSelection.Definition, Int>? =
    chosen.audioOrdinal?.let { mappedTrackInfo.selectionOf(C.TRACK_TYPE_AUDIO, it) }
}

/**
 * The track group of [trackType] at [ordinal] among those mapped to renderers of that type, paired with its renderer,
 * or null when there are not that many.
 */
private fun MappedTrackInfo.selectionOf(
  trackType: Int,
  ordinal: Int,
): Pair<ExoTrackSelection.Definition, Int>? {
  val groups = mutableListOf<kotlin.Pair<TrackGroup, Int>>()
  for (renderer in 0 until rendererCount) {
    if (getRendererType(renderer) != trackType) continue
    val mapped = getTrackGroups(renderer)
    for (group in 0 until mapped.length) groups += mapped[group] to renderer
  }
  val (group, renderer) = groups.getOrNull(ordinal) ?: return null
  return Pair(ExoTrackSelection.Definition(group, 0), renderer)
}

// What an export's own asset loader selects with: the highest bitrate, and every channel the source has.
private val EXPORT_PARAMETERS =
  DefaultTrackSelector.Parameters
    .Builder()
    .setForceHighestSupportedBitrate(true)
    .setConstrainAudioChannelCountToDeviceCapabilities(false)
    .build()

/**
 * The index among [formats] of the track of [trackType] at [ordinal] among the tracks of that type, in the order the
 * container lists them, or null when there are not that many.
 *
 * @param formats Each track's format, in the extractor's own order.
 * @param trackType The media3 track type, [C.TRACK_TYPE_AUDIO] or [C.TRACK_TYPE_VIDEO].
 */
internal fun trackIndexOf(
  formats: List<MediaFormat>,
  trackType: Int,
  ordinal: Int,
): Int? = trackIndicesOf(formats, trackType).getOrNull(ordinal)

/**
 * The index among [formats] of each track media3 reads as [trackType], in the order the container lists them.
 *
 * @param formats Each track's format, in the extractor's own order.
 * @param trackType The media3 track type, such as [C.TRACK_TYPE_AUDIO].
 */
internal fun trackIndicesOf(
  formats: List<MediaFormat>,
  trackType: Int,
): List<Int> =
  formats.indices.filter {
    MimeTypes.getTrackType(formats[it].getString(MediaFormat.KEY_MIME)) == trackType
  }
