package dev.jordond.filmstrip.media3.internal.preview

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import com.google.common.base.Supplier
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media3.internal.bounds.ChosenTracks
import dev.jordond.filmstrip.media3.internal.bounds.chosenTracks

/**
 * The media source factory a preview's `CompositionPlayer` builds each item's source with, and a frame reader its
 * item's.
 *
 * An item carrying [ChosenTracks] reads its container through extractors that hand the player only the tracks it
 * names, so the player has no other audio track to pick. Every other item goes to a factory built the way the player
 * builds its own. Every setting handed to this factory reaches both, and every factory built for a chosen item after.
 *
 * A player's host builds an instance of its own. A frame reader takes the one [readbackSourceFactory] shares, which
 * narrows a readback or a thumbnail to the first video track, as the player and an export read it.
 */
@InternalFilmstripApi
public class ChosenTrackMediaSourceFactory private constructor(
  private val context: Context,
  private val fallback: DefaultMediaSourceFactory,
) : MediaSource.Factory {
  private val settings = mutableListOf<(MediaSource.Factory) -> Unit>()

  /**
   * Builds the factory against [context]'s application context, which every source it builds reads through.
   *
   * @param context Any context of the app the preview runs in.
   */
  public constructor(
    context: Context,
  ) : this(context.applicationContext, playerFactory(context.applicationContext, null))

  override fun createMediaSource(mediaItem: MediaItem): MediaSource {
    val chosen = mediaItem.chosenTracks ?: return fallback.createMediaSource(mediaItem)
    val factory = playerFactory(context, chosen)
    settings.forEach { it(factory) }
    return factory.createMediaSource(mediaItem)
  }

  override fun getSupportedTypes(): IntArray = fallback.supportedTypes

  override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
    setting { it.setDrmSessionManagerProvider(provider) }

  override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
    setting { it.setLoadErrorHandlingPolicy(policy) }

  override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory): MediaSource.Factory =
    setting { it.setCmcdConfigurationFactory(factory) }

  override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): MediaSource.Factory =
    setting { it.setSubtitleParserFactory(factory) }

  @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
  override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): MediaSource.Factory =
    setting { it.experimentalParseSubtitlesDuringExtraction(parse) }

  override fun setDownloadExecutor(executor: Supplier<ReleasableExecutor>): MediaSource.Factory =
    setting { it.setDownloadExecutor(executor) }

  private fun setting(apply: (MediaSource.Factory) -> Unit): MediaSource.Factory {
    apply(fallback)
    settings += apply
    return this
  }
}

/**
 * The media source factory a frame reader builds a [Media3Readback]'s item with, a [ChosenTrackMediaSourceFactory] of
 * the reader's own beside the one each preview's player builds for itself.
 *
 * It narrows a readback or a thumbnail to the item's first video track, as the player and an export read it.
 * It cuts a clip trimmed before its source ends by the time each frame is shown at. The factory media3's frame
 * extraction builds for itself ends the clip on the first sample stored at or past the trim's end, and a source that
 * stores its frames out of the order they are shown in holds that sample ahead of the last frames before the end.
 *
 * Every call hands back the same factory. Frame extraction runs on one player shared across the process, and media3
 * rebuilds that player whenever a request names another factory. The factory is shared by every reader, so no caller
 * may change a setting on it.
 *
 * @param context Any context of the app the reader runs in.
 */
@InternalFilmstripApi
public fun readbackSourceFactory(context: Context): MediaSource.Factory = ReadbackSources.factoryFor(context)

private object ReadbackSources {
  private var factory: MediaSource.Factory? = null

  @Synchronized
  fun factoryFor(context: Context): MediaSource.Factory =
    factory ?: ChosenTrackMediaSourceFactory(context).also { factory = it }
}

/**
 * What `CompositionPlayer` builds when it is handed no factory, with the extractors narrowed to [chosen] when it is
 * set.
 */
internal fun playerFactory(
  context: Context,
  chosen: ChosenTracks?,
): DefaultMediaSourceFactory {
  val extractors =
    DefaultExtractorsFactory()
      .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
      .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
  val narrowed = chosen?.let { ChosenTrackExtractors(extractors, it) } ?: extractors
  return DefaultMediaSourceFactory(context, narrowed).setEnableClippingInMediaPeriod(true)
}

/**
 * Every extractor [extractors] makes, each one handing on only the tracks [chosen] names.
 */
private class ChosenTrackExtractors(
  private val extractors: ExtractorsFactory,
  private val chosen: ChosenTracks,
) : ExtractorsFactory {
  override fun createExtractors(): Array<Extractor> = extractors.createExtractors().narrowed()

  override fun createExtractors(
    uri: Uri,
    responseHeaders: Map<String, List<String>>,
  ): Array<Extractor> = extractors.createExtractors(uri, responseHeaders).narrowed()

  private fun Array<Extractor>.narrowed(): Array<Extractor> = map { ChosenTrackExtractor(it, chosen) }.toTypedArray()
}

/**
 * [extractor] with its output narrowed to the tracks [chosen] names.
 */
private class ChosenTrackExtractor(
  private val extractor: Extractor,
  private val chosen: ChosenTracks,
) : Extractor by extractor {
  override fun init(output: ExtractorOutput) {
    extractor.init(ChosenTrackOutput(output, chosen))
  }
}

/**
 * Hands [output] the first video track and the audio track at [ChosenTracks.audioOrdinal], counted in the order the
 * extractor declares them, and every track of another type. A video or audio track past those is read and dropped.
 */
private class ChosenTrackOutput(
  private val output: ExtractorOutput,
  private val chosen: ChosenTracks,
) : ExtractorOutput {
  private val declared = mutableMapOf<Int, TrackOutput>()
  private var videos = 0
  private var audios = 0

  override fun track(
    id: Int,
    type: Int,
  ): TrackOutput =
    declared.getOrPut(id) {
      val kept =
        when (type) {
          C.TRACK_TYPE_VIDEO -> videos++ == 0
          C.TRACK_TYPE_AUDIO -> audios++ == chosen.audioOrdinal
          else -> true
        }
      if (kept) output.track(id, type) else DiscardingTrackOutput()
    }

  override fun endTracks() {
    output.endTracks()
  }

  override fun seekMap(seekMap: SeekMap) {
    output.seekMap(seekMap)
  }
}
