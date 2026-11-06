package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.player.playerBackend
import dev.jordond.filmstrip.webcodecs.webCodecsBackend

internal fun setupWebCodecs(): Filmstrip {
  // region setup-webcodecs
  val filmstrip =
    Filmstrip {
      webCodecsBackend()
      playerBackend()
    }
  // endregion
  return filmstrip
}
