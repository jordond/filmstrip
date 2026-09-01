package dev.jordond.filmstrip.media

internal actual fun ImageData.channel(index: Int): Int {
  val pixels = this
  return js("pixels.data[index]").unsafeCast<Int>()
}
