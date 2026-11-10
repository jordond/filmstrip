package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds the prober an export backend should read its sources with.
 *
 * Registered probers answer first and [last] answers after them. By default that is core's own platform prober, so a
 * backend learns exactly what [dev.jordond.filmstrip.Filmstrip.probe] would. A prober that declines does not stop
 * the next one, and the reported failure is the first one, which comes from the prober registered last and therefore
 * knows the most. A prober that turns up twice in the chain is only asked once, and one a registered
 * [MediaProberDecorator] decorates is not asked at all, since the decorator has already asked it.
 *
 * @param components The components registered on the owning [dev.jordond.filmstrip.Filmstrip].
 * @param last The prober asked after every registered one, for a backend that reads its sources its own way.
 * @return A prober that walks the whole chain.
 */
@InternalFilmstripApi
public fun chainedProber(
  components: EngineComponents,
  last: MediaProber = platformProber(),
): MediaProber {
  val registered = components.mediaProberFactories.mapNotNull { it.create() }
  val decorated = registered.filterIsInstance<MediaProberDecorator>().map { it.decorated }.toSet()
  val chain = (registered + last).distinct().filterNot { it in decorated }

  return MediaProber { source -> chain.firstAnswerFor(source) }
}

private suspend fun List<MediaProber>.firstAnswerFor(source: MediaSource): ProbeResult {
  var firstFailure: ProbeResult.Failure? = null
  for (prober in this) {
    currentCoroutineContext().ensureActive()

    when (val result = prober.probe(source)) {
      is ProbeResult.Success -> return result
      is ProbeResult.Failure -> if (firstFailure == null) firstFailure = result
    }
  }
  return checkNotNull(firstFailure) { "core's own prober is always last in the chain" }
}

/**
 * A [MediaProber] that asks [decorated] first and builds its answer on top of what that one read.
 *
 * Its answer already holds [decorated]'s, failure included, so [chainedProber] does not ask [decorated] again.
 */
@InternalFilmstripApi
public interface MediaProberDecorator : MediaProber {
  /**
   * The prober this one asks before answering.
   */
  public val decorated: MediaProber
}

/**
 * Core's own prober for this platform, the one [chainedProber] asks last.
 *
 * A backend that reads part of a source better than the platform can decorates this one rather than probing the whole
 * source again. The JVM and the browser have none, so there it reports that no backend is registered. Every call
 * returns a prober equal to the others, so the chain can tell a decorator wraps the one it would ask last.
 *
 * @return A prober over this platform's own media framework.
 */
@InternalFilmstripApi
public fun platformProber(): MediaProber = PlatformMediaProber()

private class PlatformMediaProber : MediaProber {
  private val platform = PlatformProber()

  override suspend fun probe(source: MediaSource): ProbeResult = platform.probe(source)

  override fun equals(other: Any?): Boolean = other is PlatformMediaProber

  override fun hashCode(): Int = PlatformMediaProber::class.hashCode()
}
