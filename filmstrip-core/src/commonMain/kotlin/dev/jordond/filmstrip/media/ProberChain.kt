package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.internal.PlatformProber
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds the prober an export backend should read its sources with.
 *
 * Registered probers answer first and core's own platform prober answers last, so a backend learns
 * exactly what [Filmstrip.probe] would. A prober that declines does not stop the next one, and the
 * reported failure is the first one, which comes from the prober registered last and therefore
 * knows the most.
 *
 * @param context The platform context to probe against.
 * @param components The components registered on the owning [Filmstrip].
 * @return A prober that walks the whole chain.
 */
@InternalFilmstripApi
public fun chainedProber(
  context: PlatformContext,
  components: ComponentRegistry,
): MediaProber {
  val platform = PlatformProber(context)
  val chain = components.mediaProberFactories.mapNotNull { it.create(context) } + MediaProber { platform.probe(it) }

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
