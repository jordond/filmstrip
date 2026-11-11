package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.camcorder.ClockStyle
import dev.jordond.filmstrip.effects.filters.camcorder.DateStyle
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt
import dev.jordond.filmstrip.effects.filters.camcorder.camcorder
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.film
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.glitch.glitch
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.nightvision.nightVision
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.pixel.pixel
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudio
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration.Companion.seconds

internal fun filtersSetup(): Filmstrip {
  // region register
  val filmstrip =
    Filmstrip {
      allBackends()
      filters()
    }
  // endregion
  return filmstrip
}

internal fun pixelLook(): Composition {
  // region pixel
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/arcade.mp4"))
      effects {
        pixel(cells = 96, dither = 0.5f, palette = PixelPalette.GameBoy)
      }
    }
  // endregion
  return composition
}

internal fun filmLook(): Composition {
  // region film
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/holiday.mp4"))
      effects {
        film(grain = 0.6f, halation = 0.4f)
      }
    }
  // endregion
  return composition
}

internal fun super8Looks(): List<Composition> {
  // region super8
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/holiday.mp4"))
      effects {
        add(Film.Super8)
      }
    }
  val wornReel =
    compositionOf {
      clip(MediaSource.of("/path/to/holiday.mp4"))
      effects {
        film(grain = 0.9f, weave = 0.8f, flicker = 0.6f, dust = 0.7f, gate = 0.5f, seed = 8)
      }
    }
  // endregion
  return listOf(composition, wornReel)
}

internal fun crtLook(): Composition {
  // region crt
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/arcade.mp4"))
      effects {
        crt(intensity = 0.75f)
      }
    }
  // endregion
  return composition
}

internal fun glitchLook(): Composition {
  // region glitch
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/teaser.mp4"))
      effects {
        glitch(intensity = 0.6f, seed = 7)
      }
    }
  // endregion
  return composition
}

internal fun vhsLook(): Composition {
  // region vhs
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/home-movie.mp4"))
      effects {
        vhs {
          intensity = 0.6f
          tape = TapeSpeed.LP
          tracking = rolling(period = 6.seconds) { snow = 0.5f }
        }
      }
    }
  // endregion
  return composition
}

internal fun tapeSound(): Composition {
  // region tape-audio
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/home-movie.mp4"))
      effects { vhs { tape = TapeSpeed.LP } }
      audioEffects {
        tapeAudio {
          tape = TapeSpeed.LP
          hiss = 0.5f
          wow = Wobble(depth = 0.8f, rateHz = 0.5f)
        }
      }
    }
  // endregion
  return composition
}

internal fun lutLook(cubeFileText: String): Composition {
  // region lut
  val table = CubeLut.parse(cubeFileText)
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/holiday.mp4"))
      effects {
        lut(table, intensity = 0.75f)
      }
    }
  // endregion
  return composition
}

internal fun camcorderLook(): Composition {
  // region camcorder
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/birthday.mp4"))
      effects {
        camcorder {
          rec()
          counter(withFrames = true)
          battery(level = 0.25f)
          date(RecordedAt(1997, 6, 14, 15, 32), style = DateStyle.DayMonthYear)
          clock(ClockStyle.TwentyFourHour)
        }
      }
    }
  // endregion
  return composition
}

internal fun camcorderOrder(): Composition {
  // region camcorder-order
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/home-movie.mp4"))
      effects {
        // Recorded on the tape, degraded with the picture.
        vhs {
          camcorder {
            rec()
            date(RecordedAt(1997, 6, 14, 15, 32))
          }
        }
        // The VCR's own display, clean over the playback.
        camcorder { play() }
      }
    }
  // endregion
  return composition
}

internal fun nightVisionLook(): Composition {
  // region night-vision
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/backyard.mp4"))
      effects {
        nightVision(intensity = 0.8f)
      }
    }
  // endregion
  return composition
}
