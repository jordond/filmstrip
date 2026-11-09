package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.grayscale
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.sepia
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.kenBurns
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource

internal fun effectScopes(): Composition {
  // region scopes
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/intro.mp4")) {
        effects { brightness(1.2f) }
      }
      clip(MediaSource.of("/path/to/main.mp4")) {
        effects { grayscale() }
      }
      effects { crop(AspectRatio.Square) }
    }
  // endregion
  return composition
}

internal fun geometryEffects(): Composition {
  // region geometry
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        effects {
          rotate(90)
          flip(FlipAxis.Horizontal)
          crop(AspectRatio.Portrait, anchor = Anchor.TopCenter)
          scale(720)
        }
      }
    }
  // endregion
  return composition
}

internal fun cropRect(): Composition {
  // region crop-rect
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        effects { crop(NormalizedRect(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f)) }
      }
    }
  // endregion
  return composition
}

internal fun mixedFit(): Composition {
  // region fit
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/landscape.mp4"))
      clip(MediaSource.of("/path/to/portrait.mp4")) {
        effects { scale(1080, Fit.Crop) }
      }
      fill(Fill.Blur)
    }
  // endregion
  return composition
}

internal fun colorEffects(): Composition {
  // region color
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        effects {
          contrast(1.1f)
          saturation(0.8f)
          sepia(0.25f)
        }
      }
    }
  // endregion
  return composition
}

@OptIn(ExperimentalFilmstripApi::class)
internal fun panAndZoom(): Composition {
  // region ken-burns
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        effects {
          kenBurns(
            from = NormalizedRect.Full,
            to = NormalizedRect(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f),
          )
        }
      }
    }
  // endregion
  return composition
}
