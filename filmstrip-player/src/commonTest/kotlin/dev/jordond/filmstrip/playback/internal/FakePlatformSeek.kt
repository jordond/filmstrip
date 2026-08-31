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
    onComplete: () -> Unit,
  ) {
    issued += IssuedSeek(position, accuracy, onComplete)
  }

  // Completes the seek still waiting, and repeats until the chase stops dispatching new ones.
  fun drain() {
    var next = issued.firstOrNull { !it.isComplete }
    while (next != null) {
      next.complete()
      next = issued.firstOrNull { !it.isComplete }
    }
  }
}

internal class IssuedSeek(
  val position: Duration,
  val accuracy: SeekAccuracy,
  private val onComplete: () -> Unit,
) {
  var isComplete: Boolean = false
    private set

  fun complete() {
    isComplete = true
    onComplete()
  }

  // Calls back a second time, or calls back for a seek the platform already cancelled. Both are
  // things a real player does and neither may produce a second resolution.
  fun completeAgain() {
    onComplete()
  }
}
