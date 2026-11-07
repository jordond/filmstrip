@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.audio

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The buffers the passes of one window were decoded into, keyed on the stretch of source each asked
 * for.
 *
 * A looping track lays the same clip down over and over, and every pass a window holds whole asks
 * for that clip's own trim, so a run of them costs one decode between them. A buffer belongs to the
 * context that made it, so [clear] drops the lot at each window boundary. [decodes] keeps counting
 * across them, which is what says a repeated pass added none.
 */
internal class DecodeCache {
  private val buffers = mutableMapOf<Slice, AudioBuffer?>()

  var decodes: Int = 0
    private set

  /**
   * The buffer [slice] decodes to, running [decode] only the first time this window is asked for
   * it. A slice that carries no audio is remembered as nothing rather than decoded again.
   */
  suspend fun of(
    slice: Slice,
    decode: suspend () -> AudioBuffer?,
  ): AudioBuffer? {
    if (slice !in buffers) {
      decodes++
      buffers[slice] = decode()
    }
    return buffers[slice]
  }

  fun clear(): Unit = buffers.clear()

  /**
   * One stretch of one source, in the source's own seconds, as a decode was asked for it, and the
   * speed it plays at, since that changes the samples it comes to.
   */
  internal data class Slice(
    val source: MediaSource,
    val fromSeconds: Double,
    val toSeconds: Double,
    val speed: Double = 1.0,
  )
}
