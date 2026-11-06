package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.filters
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

internal fun vhsLook(): EditComposition {
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
