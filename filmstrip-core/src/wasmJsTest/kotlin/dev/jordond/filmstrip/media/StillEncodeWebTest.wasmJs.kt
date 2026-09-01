@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

internal actual fun ImageData.channel(index: Int): Int = channelOf(this, index)

@JsFun("(image, index) => image.data[index]")
private external fun channelOf(
  image: ImageData,
  index: Int,
): Int
