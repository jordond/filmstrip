package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource

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

internal fun vhsLook(): Composition {
  // region vhs
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/home-movie.mp4"))
      effects {
        vhs(intensity = 0.6f, tape = TapeSpeed.LP)
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
