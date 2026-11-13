package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.avFoundationBackend
import dev.jordond.filmstrip.player.playerBackend

internal fun setupAvFoundation(): Filmstrip {
  // region setup-avfoundation
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      avFoundationBackend()
      playerBackend()
    }
  // endregion
  return filmstrip
}
