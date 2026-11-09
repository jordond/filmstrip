package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import kotlin.time.Duration

/**
 * The transport commands a [VideoPlayer] and a [PlayerEngine] share.
 *
 * A [VideoPlayer] passes each one on to the engine it was built over.
 */
public interface PlayerTransport {
  /**
   * Starts playback, or resumes it.
   *
   * Flips [PlayerState.playWhenReady] straight away. Pixels start moving once data is available.
   */
  public fun play()

  /**
   * Pauses playback, holding the current position.
   */
  public fun pause()

  /**
   * Seeks to [position].
   *
   * Every issued seek eventually yields exactly one completion, real or synthesised, so an unseekable source never
   * wedges a scrubber.
   *
   * Completes with [PlaybackEvent.SeekCompleted] carrying the frame it landed on, which is the output slot nearest
   * [position] on an exact seek, or the slot of the sync sample the platform settled on for a relaxed one. Until the
   * newest seek completes, the playhead reads the output slot nearest the position it asked for. A relaxed seek then
   * moves the playhead once, to the slot it landed on, which can be before [position] on the browser and Apple.
   *
   * @param accuracy How exact the landing frame has to be.
   */
  public fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy = SeekAccuracy.Exact,
  )

  /**
   * Steps by whole frames, forwards for a positive count and backwards for a negative one.
   */
  public fun stepFrames(frames: Int = 1)

  /**
   * Enters scrubbing mode, where seek accuracy may relax to keep up with a moving finger.
   */
  public fun beginScrub()

  /**
   * Leaves scrubbing mode and settles on an exact frame.
   */
  public fun endScrub()

  /**
   * Sets monitor volume in `0f..1f`.
   *
   * Requires [PlayerFeature.AudioMonitoring]. Monitor volume only, it does not reach the exported
   * file. Gain that should end up in the file belongs in the composition's audio spec.
   */
  public fun setVolume(volume: Float)

  /**
   * Loops within [range], or plays straight through when null.
   *
   * Playback that passes the range's last frame wraps to the output slot its start lands on and reports
   * [PlaybackEvent.RangeLooped]. When the composition ends inside the range, playback wraps at that end instead of
   * stopping and reports no [PlaybackEvent.Ended]. That covers a range with no end, one ending exactly on the
   * composition's duration, and one ending past it, whose end is held to the duration. A range that starts at or past
   * the composition's end has nothing to loop, so playback runs to the end and reports [PlaybackEvent.Ended] as though
   * no range were set. The playhead reports the slot a wrap lands on.
   */
  public fun setLoopRange(range: TimeRange?)

  /**
   * Restricts playback to [range], or to the whole composition when null.
   */
  public fun setPlaybackRange(range: TimeRange?)

  /**
   * Sets how hard the preview may work.
   */
  public fun setQualityPolicy(policy: PreviewQualityPolicy)

  /**
   * Sets the export this preview stands in for, so it renders at the HDR grade [spec] resolves to.
   *
   * A spec that moves the grade rebuilds the pipeline and keeps the transport and the playhead. One that leaves the
   * grade alone rebuilds nothing. A spec its export refuses reaches the listeners as [PlaybackError.UnsupportedFormat]
   * in the planner's words, and the frame on screen stays. That is a refused grade, and also a refused setting such as
   * a height of zero or a bitrate named beside a quality.
   */
  public fun setExportSpec(spec: ExportSpec)
}
