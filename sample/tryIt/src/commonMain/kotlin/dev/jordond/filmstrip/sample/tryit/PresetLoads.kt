package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Fetches each preset once, keeping the clip for every later request. Main thread only.
 *
 * Every request gives a running download the whole [PRESET_LOAD_LIMIT] again. A download that reaches it is cancelled
 * and forgotten, as is one that fails, so the next request starts a new one.
 *
 * @param scope Where downloads and their limits run.
 * @param fetch Fetches the clip behind a preset, stopping when cancelled.
 */
internal class PresetLoads(
  private val scope: CoroutineScope,
  private val fetch: suspend (SamplePreset) -> MediaSource,
) {
  private class Load {
    val answer = CompletableDeferred<Result<MediaSource>>()
    var download: Job? = null
    var limit: Job? = null
  }

  private val loads = mutableMapOf<SamplePreset, Load>()

  /**
   * The answer for [preset], which a download that a caller walks away from still arrives at.
   */
  fun request(preset: SamplePreset): Deferred<Result<MediaSource>> {
    val load = loads.getOrPut(preset) { start(preset) }
    load.limit?.cancel()
    if (!load.answer.isCompleted) {
      load.limit =
        scope.launch {
          delay(PRESET_LOAD_LIMIT)
          fail(preset, load, IllegalStateException("The clip took too long to load."))
        }
    }
    return load.answer
  }

  private fun start(preset: SamplePreset): Load {
    val load = Load()
    load.download =
      scope.launch {
        try {
          val clip = fetch(preset)
          load.limit?.cancel()
          load.answer.complete(Result.success(clip))
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Throwable) {
          fail(preset, load, failure)
        }
      }
    return load
  }

  private fun fail(
    preset: SamplePreset,
    load: Load,
    failure: Throwable,
  ) {
    loads.remove(preset)
    load.limit?.cancel()
    load.download?.cancel()
    load.answer.complete(Result.failure(failure))
  }
}

private val PRESET_LOAD_LIMIT: Duration = 5.minutes
