package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [MediaProber] that holds each answer [prober] gives for the next caller.
 *
 * An engine negotiates the same edit more than once: to plan it, to resolve it for a preview, and
 * again to export it. A source named by two of those is read once. A failed probe is not held, so
 * the next caller asks again.
 *
 * One read runs at a time per source. A probe suspends, so two reads left to interleave would each
 * miss the cache on the same source and spawn a probe for it, and a caller that waits its turn
 * instead finds the answer already there. The lock is per source, so a slow source holds up only
 * the callers that name it.
 */
internal class ProbeCache(
  private val prober: MediaProber,
) : MediaProber {
  private val gate = Mutex()
  private val probed = mutableMapOf<MediaSource, MediaInfo>()
  private val reads = mutableMapOf<MediaSource, Mutex>()

  override suspend fun probe(source: MediaSource): ProbeResult {
    cached(source)?.let { return ProbeResult.Success(it) }

    return lockFor(source).withLock {
      // Read again under the source's own lock. Whoever held it before this caller has already
      // stored their answer, which is what holds a burst on one source to a single probe.
      cached(source)?.let { return@withLock ProbeResult.Success(it) }

      val result = prober.probe(source)
      if (result is ProbeResult.Success) gate.withLock { probed[source] = result.info }
      result
    }
  }

  private suspend fun cached(source: MediaSource): MediaInfo? = gate.withLock { probed[source] }

  private suspend fun lockFor(source: MediaSource): Mutex = gate.withLock { reads.getOrPut(source) { Mutex() } }
}
