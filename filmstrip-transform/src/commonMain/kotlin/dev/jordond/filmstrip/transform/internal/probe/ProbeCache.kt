package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.internal.SourceProber
import dev.jordond.filmstrip.internal.SourceRead
import dev.jordond.filmstrip.internal.readSource
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [SourceProber] that holds each answer [prober] gives for the next caller.
 *
 * An engine negotiates the same edit more than once: to plan it, to resolve it for a preview, and
 * again to export it. A source named by two of those is read once. A source with no video decoder is held the same
 * way, since asking again finds no decoder either. Any other failed read is not held, so the next caller asks again.
 *
 * One read runs at a time per source. A probe suspends, so two reads left to interleave would each
 * miss the cache on the same source and spawn a probe for it, and a caller that waits its turn
 * instead finds the answer already there. The lock is per source, so a slow source holds up only
 * the callers that name it.
 */
internal class ProbeCache(
  private val prober: MediaProber,
) : SourceProber {
  private val gate = Mutex()
  private val held = mutableMapOf<MediaSource, SourceRead>()
  private val reads = mutableMapOf<MediaSource, Mutex>()

  override suspend fun read(source: MediaSource): SourceRead {
    cached(source)?.let { return it }

    return lockFor(source).withLock {
      // Read again under the source's own lock. Whoever held it before this caller has already
      // stored their answer, which is what holds a burst on one source to a single probe.
      cached(source)?.let { return@withLock it }

      val result = prober.readSource(source)
      if (result !is SourceRead.Failed) gate.withLock { held[source] = result }
      result
    }
  }

  private suspend fun cached(source: MediaSource): SourceRead? = gate.withLock { held[source] }

  private suspend fun lockFor(source: MediaSource): Mutex = gate.withLock { reads.getOrPut(source) { Mutex() } }
}
