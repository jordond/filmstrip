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
 * The [FrameBounds] of every clip a composition lays on the output frame grid.
 *
 * Read once per plan by `readFrameBounds` and handed to every lowering of that plan, so an export and a preview of it
 * lay the same items. A clip missing from it, which is every clip in [None], opens on its own start and is not held
 * past the end of what it decodes.
 */
@InternalFilmstripApi
public class Media3FrameBounds internal constructor(
  private val windows: Map<ClipWindow, FrameBounds>,
) {
  /**
   * The bounds read for [clip]'s window, or null when none were.
   */
  internal operator fun get(clip: ResolvedClip): FrameBounds? = windows[ClipWindow(clip.source, clip.start, clip.end)]

  @InternalFilmstripApi
  public companion object {
    /**
     * Bounds for no clip at all.
     *
     * For a lowering that never reaches a container, which lays each clip from its own start.
     */
    public val None: Media3FrameBounds = Media3FrameBounds(emptyMap())
  }
}

/**
 * Reads the samples every clip this plan lays on the output frame grid decodes, off each source's own container.
 *
 * A clip is read once per window, however many passes of a looping track cut the same one. A clip whose container
 * cannot be read, or whose window holds no sample, is left out, and the lowering lays it from its own start.
 *
 * @return what every lowering of this plan lays its video items by.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.readFrameBounds(): Media3FrameBounds {
  val rate = output.frameRate
  if (path == ExportPath.Copy || audio == AudioSpec.AudioOnly) return Media3FrameBounds.None

  val windows =
    tracks
      .filter { it.content != TrackContent.Audio }
      .flatMap { it.clips }
      .filter { clip ->
        clip.source !is MediaSource.Image && clip.info.video != null && !outputSlotsOf(clip.span, rate).isEmpty()
      }.map { ClipWindow(it.source, it.start, it.end) }
      .distinct()

  if (windows.isEmpty()) return Media3FrameBounds.None
  return Media3FrameBounds(
    windows
      .mapNotNull { window -> frameBoundsOf(window.source, window.start, window.end)?.let { window to it } }
      .toMap(),
  )
}
