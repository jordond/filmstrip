package dev.jordond.filmstrip.media3.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi
import java.util.Collections
import java.util.IdentityHashMap

/**
 * This throwable's own message followed by the message of every cause under it, outermost first.
 *
 * A wrapper built from a bare cause copies that cause's `toString()` as its own message, so a cause whose `toString()`
 * is the message above it has nothing to add and is left out. So is a blank one, and a chain that loops back on itself
 * is walked once.
 */
@InternalFilmstripApi
public fun Throwable.causeMessages(): List<String> {
  val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
  val chain = generateSequence(this) { it.cause }.takeWhile(seen::add)

  return buildList {
    for (throwable in chain) {
      val message = throwable.message
      val above = lastOrNull()
      if (message.isNullOrBlank() || message == above || throwable.toString() == above) continue
      add(message)
    }
  }
}
