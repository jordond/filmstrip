package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.avFoundationExportEngine
import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
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
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal class AvThumbnailSource(
  private val scope: CoroutineScope,
  private val planner: AvThumbnailPlanner,
) : ThumbnailSource {
  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    val delivery = ThumbnailDelivery(callback)

    val job =
      scope.launch {
        when (val plan = planner.lower(request)) {
          is AvThumbnailPlan.Refused -> delivery.deliver(ThumbnailResult.Failure(plan.error))
          is AvThumbnailPlan.Ready -> generate(plan.lowered, request.position, request.precise, delivery)
        }
      }

    return Cancellable {
      delivery.cancel()
      job.cancel()
    }
  }

  /**
   * Asks [av]'s generator for the frame at [position].
   *
   * A [precise] request pins both tolerances to zero, which is what stops a generator answering
   * from the nearest sync sample. The video composition attached here renders on the output's own
   * frame grid and already lands on the frame covering [position] whatever the tolerances say, so
   * pinning them makes that a property of the request rather than of what happens to be attached.
   * Which frame came back is reported as [ThumbnailResult.Success.presentationTime] either way.
   */
  @Suppress("DEPRECATION")
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

    generator.generateCGImagesAsynchronouslyForTimes(
      listOf(NSValue.valueWithCMTime(position.toCMTime())),
    ) { _, image, actualTime, result, error ->
      // AVFoundation's own queue. Nothing past this point may throw back into it.
      val outcome =
        try {
          toResult(image, actualTime, result, error)
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Exception,
        ) {
          ThumbnailResult.Failure(
            ExportError.Underlying(
              ExportError.Underlying.NO_PLATFORM_CODE,
              broken.message ?: broken.toString(),
            ),
          )
        }
      delivery.deliver(outcome)
    }
  }

  /**
   * The generator's answer as a result the caller owns.
   *
   * The `CGImage` the handler is given lives only for the length of the callback, so it is retained
   * on its way into the [PlatformImage] the caller closes.
   */
  private fun toResult(
    image: CGImageRef?,
    actualTime: CValue<CMTime>,
    result: AVAssetImageGeneratorResult,
    error: NSError?,
  ): ThumbnailResult {
    if (result != AVAssetImageGeneratorSucceeded || image == null) {
      return ThumbnailResult.Failure(
        ExportError.Underlying(
          platformCode = error?.code?.toInt() ?: ExportError.Underlying.NO_PLATFORM_CODE,
          message = error?.localizedDescription ?: NO_FRAME,
        ),
      )
    }

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
  @Suppress("DEPRECATION")
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
  @Suppress("DEPRECATION")
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

/**
 * What lowering one thumbnail request settled on.
 */
internal sealed interface AvThumbnailPlan {
  /**
   * The edit lowered.
   */
  class Ready(
    val lowered: AvComposition,
  ) : AvThumbnailPlan

  /**
   * The edit cannot be rendered here.
   */
  class Refused(
    val error: ExportError,
  ) : AvThumbnailPlan
}

/**
 * Lowers a thumbnail request through the same AVFoundation engine an export of it runs on.
 *
 * The natural output frame is settled first and the request's height applied against it, so a
 * thumbnail smaller than the export renders at the height that was asked for. That lowering is
 * handed the natural frame as the one text lays out against, which keeps a caption breaking on the
 * same words in a strip frame as in the exported file.
 *
 * A height at or above the natural one lowers once and renders at the export's own frame, since
 * there is nothing to gain from rendering a strip larger than the file it came from.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
@OptIn(InternalFilmstripApi::class)
internal class AvThumbnailPlanner(
  components: ComponentRegistry,
) {
  private val engine =
    avFoundationExportEngine(
      prober = chainedProber(components),
      resolvers = components.effectResolvers,
    )

  suspend fun lower(request: ThumbnailRequest): AvThumbnailPlan {
    val natural =
      when (val result = engine.resolve(request.composition, ExportSpec())) {
        is ResolveResult.Refused -> return AvThumbnailPlan.Refused(result.error)
        is ResolveResult.Resolved -> result.composition
      }

    val naturalSize = natural.output.size
    val cap = request.heightPx.takeIf { it in 1..<naturalSize.height }
    val resolved =
      when (cap) {
        null -> {
          natural
        }
        else -> {
          when (
            val result = engine.resolve(request.composition, ExportSpec(targetHeight = cap), naturalSize)
          ) {
            is ResolveResult.Refused -> return AvThumbnailPlan.Refused(result.error)
            is ResolveResult.Resolved -> result.composition
          }
        }
      }

    return try {
      AvThumbnailPlan.Ready(resolved.toAvComposition())
    } catch (
      @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
      AvThumbnailPlan.Refused(ExportError.InvalidComposition(failure.message ?: failure.toString()))
    }
  }
}
