package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads what every clip of a composition is, holding each answer for the next caller.
 *
 * An engine negotiates the same edit more than once: to plan it, to resolve it for a preview, and
 * again to export it. A source named by two of those is read once.
 *
 * One read runs at a time per source. A probe suspends, so two reads left to interleave would each
 * miss the cache on the same source and spawn a probe for it, and a caller that waits its turn
 * instead finds the answer already there. The lock is per source, so a slow source holds up only
 * the callers that name it.
 */
@InternalFilmstripApi
public class ProbeCache(
  private val prober: MediaProber,
) {
  private val gate = Mutex()
  private val probed = mutableMapOf<MediaSource, MediaInfo>()
  private val reads = mutableMapOf<MediaSource, Mutex>()

  /**
   * Probes every source [composition] reads, answering from the cache where it can.
   *
   * @return What each source is, or the first failure a probe reported.
   */
  public suspend fun read(composition: EditComposition): ProbeCacheResult {
    val infos = mutableMapOf<MediaSource, MediaInfo>()
    for (clip in composition.tracks.flatMap { it.clips }) {
      if (clip.source in infos) continue
      when (val result = readOne(clip.source)) {
        is ProbeResult.Success -> infos[clip.source] = result.info
        is ProbeResult.Failure -> return ProbeCacheResult.Failed(result.error)
      }
    }
    return ProbeCacheResult.Read(infos)
  }

  private suspend fun readOne(source: MediaSource): ProbeResult {
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

/**
 * What [ProbeCache.read] came back with.
 */
@InternalFilmstripApi
public sealed interface ProbeCacheResult {
  /**
   * Every source read.
   *
   * @property infos What each source the composition names turned out to be.
   */
  @InternalFilmstripApi
  public class Read(
    public val infos: Map<MediaSource, MediaInfo>,
  ) : ProbeCacheResult

  /**
   * A source could not be read.
   *
   * @property error Why it could not.
   */
  @InternalFilmstripApi
  public class Failed(
    public val error: ExportError,
  ) : ProbeCacheResult
}
