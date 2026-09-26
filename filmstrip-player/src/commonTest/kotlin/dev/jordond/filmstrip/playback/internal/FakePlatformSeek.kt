package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import kotlin.time.Duration

// A platform seek that never completes on its own, so a test decides when, in what order, and how
// many times each issued seek calls back.
internal class FakePlatformSeek : PlatformSeek {
  val issued: MutableList<IssuedSeek> = mutableListOf()

  var isReady: Boolean = true

  override fun seek(
    position: Duration,
    accuracy: SeekAccuracy,
    wrap: Boolean,
    onComplete: (landed: Duration) -> Unit,
  ) {
    issued += IssuedSeek(position, accuracy, wrap, onComplete)
  }

  // Completes the seek still waiting, and repeats until the chase stops dispatching new ones. Each one lands
  // where landing maps its position, which by default is the position itself.
  fun drain(landing: (Duration) -> Duration = { it }) {
    var next = issued.firstOrNull { !it.isComplete }
    while (next != null) {
      next.complete(landed = landing(next.position))
      next = issued.firstOrNull { !it.isComplete }
    }
  }
}

internal class IssuedSeek(
  val position: Duration,
  val accuracy: SeekAccuracy,
  val wrap: Boolean,
  private val onComplete: (landed: Duration) -> Unit,
) {
  var isComplete: Boolean = false
    private set

  fun complete(landed: Duration = position) {
    isComplete = true
    onComplete(landed)
  }

  // Calls back a second time, or calls back for a seek the platform already cancelled. Both are
  // things a real player does and neither may produce a second resolution.
  fun completeAgain(landed: Duration = position) {
    onComplete(landed)
  }
}
