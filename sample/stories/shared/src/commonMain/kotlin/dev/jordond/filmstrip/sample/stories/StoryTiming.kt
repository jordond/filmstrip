package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a caption arrives when its window opens.
 *
 * @property animation The animation the caption carries on the stage and in the export, or null to draw it still.
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

private val ENTRANCE = 400.milliseconds
private const val RISE = 0.08f
private const val SLIDE = 0.3f
