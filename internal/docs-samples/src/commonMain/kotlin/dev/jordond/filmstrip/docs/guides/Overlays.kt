package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextStyle
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun imageWatermark(): EditComposition {
  // region image
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/logo.png"),
          corner = Corner.BottomEnd,
          scale = 0.15f,
          opacity = 0.8f,
        )
      }
    }
  // endregion
  return composition
}

internal fun caption(): EditComposition {
  // region text
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        textOverlay(
          text = "Day one",
          at = 2.seconds..6.seconds,
          style =
            TextStyle(
              fontSize = 0.05f,
              weight = FontWeight.Bold,
              backgroundColor = 0x80000000.toInt(),
            ),
          anchor = Anchor.TopCenter,
        )
      }
    }
  // endregion
  return composition
}

internal fun fadedOverlay(): EditComposition {
  // region fade
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        textOverlay(
          text = "Day one",
          at = 2.seconds..6.seconds,
          animation = fade(inDuration = 500.milliseconds, outDuration = 1.seconds),
        )
      }
    }
  // endregion
  return composition
}

internal fun slidingOverlay(): EditComposition {
  // region slide
  val appear = fade(inDuration = 300.milliseconds)
  val arrive = appear + slideIn(OverlayOffset(x = -0.2f, y = 0f), 300.milliseconds)

  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/logo.png"),
          corner = Corner.TopStart,
          animation = arrive,
        )
      }
    }
  // endregion
  return composition
}

internal fun pulse(): OverlayAnimation {
  // region custom-animation
  val pulse =
    OverlayAnimation { time ->
      val grown = 1f + (time.elapsed.inWholeMilliseconds % 1000) / 10000f
      OverlayFrame(scale = grown)
    }
  // endregion
  return pulse
}
