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
 * knows the most. A prober that turns up twice in the chain is only asked once.
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
  val chain = (components.mediaProberFactories.mapNotNull { it.create() } + last).distinct()

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

private fun platformProber(): MediaProber {
  val platform = PlatformProber()
  return MediaProber { platform.probe(it) }
}
