package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.player.playerBackend
import dev.jordond.filmstrip.transformBackend

internal fun setupAll(): Filmstrip {
  // region setup-all
  val filmstrip =
    Filmstrip {
      allBackends()
    }
  // endregion
  return filmstrip
}

internal fun setupExportOnly(): Filmstrip {
  // region setup-export-only
  val filmstrip =
    Filmstrip {
      transformBackend()
    }
  // endregion
  return filmstrip
}

internal fun setupPlayerOnly(): Filmstrip {
  // region setup-player
  val filmstrip =
    Filmstrip {
      playerBackend()
    }
  // endregion
  return filmstrip
}
