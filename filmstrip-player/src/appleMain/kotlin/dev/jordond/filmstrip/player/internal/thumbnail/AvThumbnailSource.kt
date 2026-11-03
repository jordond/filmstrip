package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetImageGeneratorResult
import platform.AVFoundation.AVAssetImageGeneratorSucceeded
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRetain
import platform.CoreMedia.CMTime
import platform.Foundation.NSError
import platform.Foundation.NSValue
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration

/**
 * Renders strip frames through the graph an export of the same edit would run.
 *
 * The lowering is the one the preview uses: the request's composition goes through the AVFoundation
 * export engine and [toAvComposition], so an `AVAssetImageGenerator` over the resulting
 * `AVMutableComposition` renders each frame through the same `CoreImageChain` an export writes
 * from. Nothing here builds a second graph.
 *
 * One generator per request, cancelled by the returned handle. Requests arrive serialised from the
 * dispatcher and nothing here parallelises them, since extraction running alongside a preview
 * contends with it for the device's decoders.
 *
 * @param scope Where the lowering runs. Generator callbacks arrive on AVFoundation's own queue and
 *   are passed straight through.
 * @param planner Lowers an edit the way an export of it would be lowered.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvThumbnailSource(
  private val scope: CoroutineScope,
  private val planner: AvThumbnailPlanner,
) : ThumbnailSource {
  private val generating = MutableStateFlow(0)

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    val delivery = ThumbnailDelivery(callback)

    val job =
      scope.launch {
        when (val plan = lowerThumbnail { planner.lower(request) }) {
          is PlanResult.Refused -> delivery.deliver(ThumbnailResult.Failure(plan.error))
          is PlanResult.Ready -> generate(plan.plan, request.position, request.precise, delivery)
        }
      }

    return Cancellable {
      delivery.cancel()
      job.cancel()
    }
  }

  /**
   * Suspends until every request already made has finished lowering and every frame a generator was asked for has
   * been answered.
   *
   * A request cancelled before its generator was asked never asks, and a generator cancelled after it was asked still
   * answers, with a cancellation. Never returns while a generator holds a frame it does not answer, so a caller bounds
   * the wait.
   */
  suspend fun awaitRequestsSettled() {
    scope.coroutineContext.job.children
      .forEach { it.join() }
    generating.first { it == 0 }
  }

  /**
   * Asks [av]'s generator for the frame at [position].
   *
   * A [precise] request pins both tolerances to zero, which is what stops a generator answering
   * from the nearest sync sample. The video composition attached here renders on the output's own
   * frame grid and already lands on the frame covering [position] whatever the tolerances say, so
   * pinning them makes that a property of the request.
   * Which frame came back is reported as [ThumbnailResult.Success.presentationTime] either way.
   */
  private fun generate(
    av: AvComposition,
    position: Duration,
    precise: Boolean,
    delivery: ThumbnailDelivery,
  ) {
    val generator =
      AVAssetImageGenerator(asset = av.composition).apply {
        videoComposition = av.videoComposition
        if (precise) {
          requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
          requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
        }
      }
    if (!delivery.attach(generator)) return

    generating.update { it + 1 }
    generator.generateCGImagesAsynchronouslyForTimes(
      listOf(NSValue.valueWithCMTime(position.toCMTime())),
    ) { _, image, actualTime, result, error ->
      // AVFoundation's own queue. Nothing past this point may throw back into it.
      val outcome =
        try {
          toResult(av, image, actualTime, result, error)
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Exception,
        ) {
          ThumbnailResult.Failure(
            FilmstripError.Underlying(
              FilmstripError.Underlying.NO_PLATFORM_CODE,
              broken.message ?: broken.toString(),
            ),
          )
        }
      try {
        delivery.deliver(outcome)
      } finally {
        generating.update { it - 1 }
      }
    }
  }

  /**
   * The generator's answer as a result the caller owns.
   *
   * A step [av]'s chain could not run while drawing the frame refuses the tile with the step's reason, since the frame
   * the generator hands back is drawn without that step's effects. The `CGImage` the handler is given lives only for
   * the length of the callback, so it is retained on its way into the [PlatformImage] the caller closes.
   */
  private fun toResult(
    av: AvComposition,
    image: CGImageRef?,
    actualTime: CValue<CMTime>,
    result: AVAssetImageGeneratorResult,
    error: NSError?,
  ): ThumbnailResult {
    if (result != AVAssetImageGeneratorSucceeded || image == null) {
      return ThumbnailResult.Failure(
        FilmstripError.Underlying(
          platformCode = error?.code?.toInt() ?: FilmstripError.Underlying.NO_PLATFORM_CODE,
          message = error?.localizedDescription ?: NO_FRAME,
        ),
      )
    }
    av.chainFailure()?.let { return ThumbnailResult.Failure(it) }

    return ThumbnailResult.Success(
      image = PlatformImage(CGImageRetain(image)),
      presentationTime = actualTime.toDuration(),
    )
  }

  private companion object {
    const val NO_FRAME = "The image generator returned no frame."
  }
}

/**
 * One request's callback, delivered at most once, and the generator serving it.
 *
 * Cancellation and delivery race by construction: the handle is cancelled from whichever thread the
 * consumer scrolled on while the generator's queue is answering. Whichever gets there first wins,
 * and the loser does nothing.
 */
@OptIn(ExperimentalForeignApi::class)
private class ThumbnailDelivery(
  private val callback: ThumbnailCallback,
) {
  private val settled = AtomicInt(OPEN)
  private val generator = AtomicReference<AVAssetImageGenerator?>(null)

  /**
   * Hands the result to the callback, unless this request already settled.
   */
  fun deliver(result: ThumbnailResult) {
    if (!settled.compareAndSet(OPEN, SETTLED)) return
    generator.value = null
    callback.onThumbnail(result)
  }

  /**
   * Takes ownership of [running], or refuses because the request was already cancelled.
   *
   * @return whether the generator should go on to be asked for a frame.
   */
  fun attach(running: AVAssetImageGenerator): Boolean {
    if (settled.value != OPEN) return false
    generator.value = running
    // Cancelled between the check and the store, which leaves nobody else holding this generator.
    if (settled.value != OPEN) {
      running.cancelAllCGImageGeneration()
      return false
    }
    return true
  }

  /**
   * Stops the decode and gives up on delivering anything.
   */
  fun cancel() {
    settled.value = SETTLED
    generator.value?.cancelAllCGImageGeneration()
    generator.value = null
  }

  private companion object {
    const val OPEN = 0
    const val SETTLED = 1
  }
}
