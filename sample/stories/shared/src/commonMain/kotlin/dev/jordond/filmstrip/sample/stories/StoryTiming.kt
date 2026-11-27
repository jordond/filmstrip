package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.OverlayTime
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a caption arrives when its window opens.
 *
 * @property animation The animation the export is handed and the stage samples, or null to draw the caption still.
 */
internal enum class StoryEntrance(
  val animation: OverlayAnimation?,
) {
  None(null),
  Fade(fade(inDuration = ENTRANCE)),
  Rise(fade(inDuration = ENTRANCE) + slideIn(OverlayOffset(0f, RISE), ENTRANCE)),
  Slide(fade(inDuration = ENTRANCE) + slideIn(OverlayOffset(-SLIDE, 0f), ENTRANCE)),
  ;

  companion object {
    /**
     * How long every entrance takes.
     */
    val DURATION: Duration get() = ENTRANCE
  }
}

/**
 * How the stage draws [element] with the playhead at [playhead], or null when it is not drawn.
 *
 * A [selected] element is always drawn and drawn still, so it can be edited outside its window and midway through
 * its entrance. Any other is hidden outside its window and inside it takes the frame its animation gives, the same
 * one the export draws.
 *
 * @param duration How long the story runs.
 */
internal fun liveFrame(
  element: StoryElement,
  playhead: Duration,
  duration: Duration,
  selected: Boolean,
): OverlayFrame? {
  if (selected) return OverlayFrame.Identity
  if (!element.showsAt(playhead, selected = false)) return null
  val animation = element.animation ?: return OverlayFrame.Identity
  return animation.frameAt(storyOverlayTime(playhead, element.window, duration))
}

/**
 * Where the playhead sits inside the range an element is drawn over, for sampling its animation on the stage.
 *
 * The drawn range is [window] cut to the story, or the whole story when there is no window. It is the range an export
 * draws the baked element over, so the stage and the file read an animation at the same time. A playhead off the range
 * reads as its nearer end.
 *
 * @param playhead Where the preview is, on the story's timeline.
 * @param window The range the element shows for, or null for the whole story.
 * @param duration How long the story runs.
 */
internal fun storyOverlayTime(
  playhead: Duration,
  window: TimeRange?,
  duration: Duration,
): OverlayTime {
  val start = (window?.start ?: Duration.ZERO).coerceIn(Duration.ZERO, duration)
  val end = (window?.endExclusive ?: duration).coerceIn(start, duration)
  return OverlayTime(
    elapsed = (playhead - start).coerceAtLeast(Duration.ZERO),
    remaining = (end - playhead).coerceAtLeast(Duration.ZERO),
    composition = playhead,
  )
}

private val ENTRANCE = 400.milliseconds
private const val RISE = 0.08f
private const val SLIDE = 0.3f
