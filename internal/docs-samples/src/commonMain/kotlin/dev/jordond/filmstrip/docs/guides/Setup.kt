package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.player.playerBackend
import dev.jordond.filmstrip.transformBackend

internal fun setupAll(): Filmstrip {
  // region setup-all
  val filmstrip = Filmstrip()
  // endregion
  return filmstrip
}

internal fun setupExplicit(): Filmstrip {
  // region setup-explicit
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      allBackends()
    }
  // endregion
  return filmstrip
}

internal fun setupExportOnly(): Filmstrip {
  // region setup-export-only
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      transformBackend()
    }
  // endregion
  return filmstrip
}

internal fun setupPlayerOnly(): Filmstrip {
  // region setup-player
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      playerBackend()
    }
  // endregion
  return filmstrip
}
