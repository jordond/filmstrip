package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A [BasePlayerEngine] that lowers each edit it is handed into a preview plan against a [PreviewQualityPolicy].
 *
 * [setComposition] answers an edit equal to the loaded one at once, with no platform call, while a graph is standing.
 * Any other edit is planned by [planner] against [policy] and handed to [apply], and its caller hears the plan's
 * duration once [apply] has put it in place, or the error that stopped it. A new policy moves the rendered frame, which
 * moves the output format, and no backend can swap that inside a running graph. So [setQualityPolicy] forgets the
 * loaded edit and sets it again, which plans it from nothing against the new policy.
 *
 * @param P The plan this backend builds its graph from.
 * @param parent The scope this engine's own is a child of.
 * @param qualityPolicy The policy the first plan is lowered against.
 * @param planner Lowers an edit the way an export of it would be lowered.
 */
internal abstract class PlannedPlayerEngine<P : PlannedPreview>(
  parent: CoroutineScope,
  qualityPolicy: PreviewQualityPolicy,
  private val planner: PreviewPlanner<P>,
) : BasePlayerEngine(parent) {
  /**
   * The edit the plan in place was lowered from, or null when nothing is loaded.
   */
  protected var loaded: EditComposition? = null

  /**
   * The plan in place, which [apply] records once its graph is built, or null before one is.
   */
  protected var plan: P? = null

  /**
   * The policy every plan is lowered against.
   */
  protected var policy: PreviewQualityPolicy = qualityPolicy
    private set

  /**
   * The load outstanding now, which [launchLoad] records and [setComposition] settles once its plan is in place.
   */
  protected val pending: PendingLoad = PendingLoad()

  /**
   * Whether a graph built from [plan] is standing, so an edit equal to [loaded] needs nothing from the platform.
   */
  protected abstract val isGraphStanding: Boolean

  final override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending.supersede()

    val change = diff(loaded, request.composition)
    if (change == CompositionDiff.Equal && isGraphStanding) {
      // Nothing at the platform level, not even a status move. A listener must not see this load.
      loaded = request.composition
      callback.onResult(SetCompositionResult.Success(plan?.resolved?.duration ?: Duration.ZERO))
      return Cancellable { }
    }

    return launchLoad(callback) {
      when (val result = planner.plan(request.composition, policy)) {
        is PlanResult.Refused -> {
          refuseLoad(result.error)
        }
        is PlanResult.Ready -> {
          when (val error = apply(change, result.plan, request)) {
            null -> pending.settle(SetCompositionResult.Success(result.plan.resolved.duration))
            else -> refuseLoad(error)
          }
        }
      }
    }
  }

  /**
   * Applies one lowered plan, rebuilding only what [change] actually costs, and records [loaded] and [plan].
   *
   * Never handed an equal edit while a graph is standing, since [setComposition] answers that itself. The transport is
   * left as it was, so playback that was wanted keeps playing on the new graph and a paused one stays paused. A
   * rebuild with no start puts the playhead where [heldPosition] places it in [next] and reports that reading. A
   * parameters-only edit with no start leaves the playhead where it stands.
   *
   * @return null when the plan is loaded, or why it could not be.
   */
  protected abstract suspend fun apply(
    change: CompositionDiff,
    next: P,
    request: SetCompositionRequest,
  ): PlaybackError?

  final override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    if (this.policy == policy) return
    this.policy = policy

    val composition = loaded ?: return
    loaded = null
    setComposition(SetCompositionRequest(composition), { })
  }

  /**
   * Runs [load] as the load [callback] waits on, and makes sure the caller hears back.
   *
   * [load] plans and applies the edit and settles [pending], calling [refuseLoad] for a refusal. Planning
   * reaches a prober, a resolver, a device query and a platform lowering, any of which may throw rather than refuse. A
   * throw is refused here as [PlaybackError.Underlying], so no caller waits on a load nothing will settle. A cancelled
   * load is not a failed one and is let through, since the request that replaced it has already answered its caller.
   *
   * @return A handle that cancels the load without answering its caller.
   */
  private fun launchLoad(
    callback: SetCompositionCallback,
    load: suspend () -> Unit,
  ): Cancellable {
    // Lazy, so the load is recorded before it can run. A body that reached settle() first would find nothing to
    // settle and leave the caller waiting on a result that had already been decided.
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          load()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Exception,
        ) {
          refuseLoad(
            PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, broken.message ?: broken.toString()),
          )
        }
      }

    val handle = pending.track(job, callback)
    job.start()
    return handle
  }

  /**
   * Reports a load that could not be made, to every listener through [fail] and then to the caller waiting on it.
   *
   * A load already answered or cancelled is left as it was, and only the listeners hear about it.
   */
  private fun refuseLoad(error: PlaybackError) {
    fail(error)
    pending.settle(SetCompositionResult.Failure(error))
  }

  /**
   * Reports [error] to every listener as the reason playback stopped.
   *
   * Leaves the caller of a load alone. [refuseLoad] settles that once this returns.
   */
  protected abstract fun fail(error: PlaybackError)
}
