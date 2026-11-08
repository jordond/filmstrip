package dev.jordond.filmstrip.media3.internal.bounds

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * What every clip a composition lays on the output frame grid reads off its source: its [FrameBounds] and the SD color
 * primaries the source names.
 *
 * Read once per plan by `readClips` and handed to every lowering of that plan, so an export and a preview of it
 * lay the same items. A clip missing from it, which is every clip in [None], opens on its own start, is not held
 * past the end of what it decodes, and names no SD colour.
 */
@InternalFilmstripApi
public class Media3ClipReads internal constructor(
  private val windows: Map<ClipWindow, FrameBounds>,
  private val sdPrimaries: Map<MediaSource, Int> = emptyMap(),
) {
  /**
   * The bounds read for [clip]'s window, or null when none were.
   */
  internal operator fun get(clip: ResolvedClip): FrameBounds? = windows[ClipWindow(clip.source, clip.start, clip.end)]

  /**
   * The SD color primaries code point an SDR [clip]'s source names, or null when it names none.
   *
   * A clip with an HDR transfer gets null whatever its source names, since it is not a signal on BT.709's curve.
   */
  internal fun sdPrimariesOf(clip: ResolvedClip): Int? =
    sdPrimaries[clip.source].takeIf { clip.info.video?.hdrTransfer == null }

  @InternalFilmstripApi
  public companion object {
    /**
     * Bounds for no clip at all.
     *
     * For a lowering that never reaches a container, which lays each clip from its own start.
     */
    public val None: Media3ClipReads = Media3ClipReads(emptyMap())
  }
}

/**
 * Reads the samples every clip this plan lays on the output frame grid decodes and the SD color primaries it names,
 * off each source's own container.
 *
 * A clip is read once per window, however many passes of a looping track cut the same one. A clip whose container
 * cannot be read is left out, and the lowering lays it from its own start.
 *
 * @return what every lowering of this plan lays its video items by.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.readClips(): Media3ClipReads {
  val rate = output.frameRate
  if (path == ExportPath.Copy || audio == AudioSpec.AudioOnly) return Media3ClipReads.None

  val windows =
    tracks
      .filter { it.content != TrackContent.Audio }
      .flatMap { it.clips }
      .filter { clip ->
        clip.source !is MediaSource.Image && clip.info.video != null && !outputSlotsOf(clip.span, rate).isEmpty()
      }.map { ClipWindow(it.source, it.start, it.end) }
      .distinct()

  if (windows.isEmpty()) return Media3ClipReads.None
  return Media3ClipReads(
    windows
      .mapNotNull { window -> frameBoundsOf(window.source, window.start, window.end)?.let { window to it } }
      .toMap(),
    windows
      .map { it.source }
      .distinct()
      .mapNotNull { source -> sdPrimariesNamedBy(source)?.let { source to it } }
      .toMap(),
  )
}
