package dev.jordond.filmstrip.media3.internal.bounds

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * What every clip a composition lays reads off its source: the [FrameBounds] and SD color primaries of the clips on the
 * output frame grid, and the audio access unit each audio clip opens its decode on.
 *
 * Read once per plan by `readClips` and handed to every lowering of that plan, so an export and a preview of it
 * lay the same video items. A clip missing from it, which is every clip in [None], opens on its own start, is not held
 * past the end of what it decodes, and names no SD colour.
 */
@InternalFilmstripApi
public class Media3ClipReads internal constructor(
  private val windows: Map<ClipWindow, FrameBounds>,
  private val sdPrimaries: Map<MediaSource, Int> = emptyMap(),
  private val audioPrerolls: Map<AudioWindow, AudioPreroll> = emptyMap(),
) {
  /**
   * The bounds read for [clip]'s window, or null when none were.
   */
  internal operator fun get(clip: ResolvedClip): FrameBounds? = windows[clip.window]

  /**
   * Where [clip]'s audio decode opens on its source, or null when it was not read.
   */
  internal fun audioPrerollOf(clip: ResolvedClip): AudioPreroll? = clip.audioWindow?.let(audioPrerolls::get)

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
 * Reads the samples every clip this plan lays on the output frame grid decodes and the SD color primaries it names, off
 * each source's own container.
 *
 * A clip is read once per window, however many passes of a looping track cut the same one. A clip whose container
 * cannot be read is left out, and the lowering lays it from its own start. A copy writes the source's own samples, so
 * nothing is read for one. No audio unit is read, since only a lowering that runs each clip's audio speed itself opens
 * on one.
 *
 * @return what every lowering of this plan lays its items by.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.readClips(): Media3ClipReads = readClips(ownsAudioSpeed = false)

/**
 * Reads what [readClips] does, and with [ownsAudioSpeed] set also the audio access unit every audio clip opens its
 * decode on.
 *
 * @param ownsAudioSpeed Whether the lowering these reads go to opens each clip's audio on its trim start and runs its
 * speed itself, as an export does.
 */
internal suspend fun ResolvedComposition.readClips(ownsAudioSpeed: Boolean): Media3ClipReads {
  if (path == ExportPath.Copy) return Media3ClipReads.None
  val rate = output.frameRate
  val clips = tracks.flatMap { track -> track.clips.map { track.content to it } }

  val windows =
    if (audio == AudioSpec.AudioOnly) {
      emptyList()
    } else {
      clips
        .filter { (content, clip) ->
          content != TrackContent.Audio &&
            clip.source !is MediaSource.Image &&
            clip.info.video != null &&
            !outputSlotsOf(clip.span, rate).isEmpty()
        }.map { (_, clip) -> clip.window }
        .distinct()
    }
  val audioWindows =
    if (!ownsAudioSpeed || output.audioCodec == AudioCodec.None) {
      emptyList()
    } else {
      clips
        .filter { (content, _) -> content != TrackContent.Video }
        .mapNotNull { (_, clip) -> clip.audioWindow }
        .distinct()
    }

  if (windows.isEmpty() && audioWindows.isEmpty()) return Media3ClipReads.None
  return Media3ClipReads(
    windows
      .mapNotNull { window -> frameBoundsOf(window.source, window.start, window.end)?.let { window to it } }
      .toMap(),
    windows
      .map { it.source }
      .distinct()
      .mapNotNull { source -> sdPrimariesNamedBy(source)?.let { source to it } }
      .toMap(),
    audioWindows
      .mapNotNull { window ->
        audioPrerollStartOf(window.source, window.start, window.audioOrdinal)?.let { window to it }
      }.toMap(),
  )
}

private val ResolvedClip.window: ClipWindow get() = ClipWindow(source, start, end)

// Null for a clip whose source has no audio track to open.
private val ResolvedClip.audioWindow: AudioWindow? get() = audioTrack?.let { AudioWindow(source, start, it.ordinal) }
