package dev.jordond.filmstrip.docs.guides

import android.content.Context
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media3.media3Backend
import dev.jordond.filmstrip.player.playerBackend

internal fun setupAndroid(context: Context): Filmstrip {
  // region setup-android-context
  val filmstrip = Filmstrip(context)
  // endregion
  return filmstrip
}

internal fun setupMedia3(): Filmstrip {
  // region setup-media3
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      media3Backend()
      playerBackend()
    }
  // endregion
  return filmstrip
}
