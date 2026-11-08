package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import kotlin.test.fail

/**
 * The value a read answered with, failing the test when it was stopped at its deadline instead.
 */
internal fun <T> ProbeAnswer<T>.answer(): T =
  when (this) {
    is ProbeAnswer.Answered -> value
    is ProbeAnswer.Expired -> fail("The read was stopped at its deadline: $reason")
  }
