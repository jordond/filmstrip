package dev.jordond.filmstrip.still

import dev.jordond.filmstrip.media.ImageData

internal actual fun ImageData.channel(index: Int): Int {
  val pixels = this
  return js("pixels.data[index]").unsafeCast<Int>()
}
