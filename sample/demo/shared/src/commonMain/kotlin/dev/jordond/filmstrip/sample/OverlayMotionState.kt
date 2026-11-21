package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.effects.overlay.slideOut

/**
 * The edge of the frame an overlay slides in from or out to. The travel is one whole frame, so the overlay starts or
 * ends off screen wherever it is anchored.
 */
public enum class SlideEdge(
  internal val offset: OverlayOffset,
) {
  Start(OverlayOffset(-1f, 0f)),
  End(OverlayOffset(1f, 0f)),
  Top(OverlayOffset(0f, -1f)),
  Bottom(OverlayOffset(0f, 1f)),
}

/**
 * How one overlay moves: a fade at either end and a slide in or out. Text and the watermark each hold one.
 *
 * The out half of a fade and a slide out count back from the overlay's last frame, so they only show on an overlay
 * with a visible window.
 */
@Stable
public class OverlayMotionState {
  public var fadeInSeconds: Float by mutableStateOf(0f)
  public var fadeOutSeconds: Float by mutableStateOf(0f)
  public var slideIn: SlideEdge? by mutableStateOf(null)
  public var slideOut: SlideEdge? by mutableStateOf(null)
  public var slideSeconds: Float by mutableStateOf(DEFAULT_SLIDE_SECONDS)

  /**
   * The animation the controls add up to, the fade first, then the slide in, then the slide out. Null when nothing
   * is set, which leaves the overlay still.
   */
  public val animation: OverlayAnimation?
    get() {
      val slide = slideSeconds.asSeconds()
      val parts =
        listOfNotNull(
          if (fadeInSeconds > 0f || fadeOutSeconds > 0f) {
            fade(fadeInSeconds.asSeconds(), fadeOutSeconds.asSeconds())
          } else {
            null
          },
          slideIn?.let { slideIn(it.offset, slide) },
          slideOut?.let { slideOut(it.offset, slide) },
        )
      return parts.reduceOrNull { all, next -> all + next }
    }

  internal fun reset() {
    fadeInSeconds = 0f
    fadeOutSeconds = 0f
    slideIn = null
    slideOut = null
    slideSeconds = DEFAULT_SLIDE_SECONDS
  }

  private companion object {
    const val DEFAULT_SLIDE_SECONDS = 0.5f
  }
}
