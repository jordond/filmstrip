package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.effect.PreviewClock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A [PreviewClock] that starts at zero when it is built and runs on [source].
 */
internal class MonotonicPreviewClock(
  source: TimeSource = TimeSource.Monotonic,
) : PreviewClock {
  private val built = source.markNow()

  override fun now(): Duration = built.elapsedNow()
}
