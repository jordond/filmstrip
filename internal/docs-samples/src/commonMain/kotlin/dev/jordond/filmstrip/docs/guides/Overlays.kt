package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextStyle
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun imageWatermark(): Composition {
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

internal fun placedSticker(): Composition {
  // region at
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/sticker.png"),
          at = Anchor(0.3f, 0.7f),
          scale = 0.25f,
        )
      }
    }
  // endregion
  return composition
}

internal fun caption(): Composition {
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

internal fun fadedOverlay(): Composition {
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

internal fun slidingOverlay(): Composition {
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

internal fun spinner(): Composition {
  // region sheet
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/spinner.png"),
          corner = Corner.TopEnd,
          scale = 0.1f,
          sheet = SpriteSheet(columns = 4, rows = 3, frameDuration = 80.milliseconds),
        )
      }
    }
  // endregion
  return composition
}

internal fun burst(): Composition {
  // region sheetOnce
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/burst.png"),
          corner = Corner.BottomStart,
          at = 2.seconds..5.seconds,
          sheet =
            SpriteSheet(
              columns = 4,
              rows = 3,
              frameDuration = 60.milliseconds,
              frameCount = 10,
              plays = FramePlays.Once,
            ),
        )
      }
    }
  // endregion
  return composition
}

internal fun timedSheet(): Composition {
  // region sheetDurations
  val sheet =
    SpriteSheet(
      columns = 4,
      rows = 3,
      frameDurations = listOf(100.milliseconds, 30.milliseconds, 250.milliseconds, 40.milliseconds, 70.milliseconds),
      plays = FramePlays.Times(3),
    )

  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(image = ImageSource.of("/path/to/blink.png"), corner = Corner.TopStart, sheet = sheet)
      }
    }
  // endregion
  return composition
}

internal fun sticker(): Composition {
  // region sticker
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      effects {
        imageOverlay(
          image = ImageSource.of("/path/to/sticker.gif"),
          corner = Corner.BottomEnd,
          scale = 0.25f,
          at = 2.seconds..6.seconds,
        )
      }
    }
  // endregion
  return composition
}
