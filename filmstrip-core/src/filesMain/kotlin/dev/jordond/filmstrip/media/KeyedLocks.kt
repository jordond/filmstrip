package dev.jordond.filmstrip.media

/**
 * One lock for each key in use, gone again once nobody holds or waits on it.
 */
internal class KeyedLocks {
  private class Held {
    val lock = MediaBytesLock()
    var users = 0
  }

  private val guard = MediaBytesLock()
  private val held = HashMap<String, Held>()

  /**
   * How many keys have a lock right now.
   */
  val size: Int get() = guarded { held.size }

  /**
   * Runs [block] with no other caller inside this for [key].
   */
  fun <T> withLock(
    key: String,
    block: () -> T,
  ): T {
    val entry = guarded { held.getOrPut(key, ::Held).also { it.users++ } }
    entry.lock.lock()
    try {
      return block()
    } finally {
      entry.lock.unlock()
      guarded { if (--entry.users == 0) held.remove(key) }
    }
  }

  private inline fun <T> guarded(block: () -> T): T {
    guard.lock()
    try {
      return block()
    } finally {
      guard.unlock()
    }
  }
}
