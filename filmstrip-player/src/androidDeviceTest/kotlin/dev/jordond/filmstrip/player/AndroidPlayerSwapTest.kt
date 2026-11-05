package dev.jordond.filmstrip.player

import android.os.Handler
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.cutInTwo
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What the media3 engine carries onto the player it builds for a different video graph.
 *
 * Only the primary track of a planned edit carries video today, so the second video sequence is laid in by the planner
 * here, on the plan the engine is handed.
 */
@ExperimentalApi
class AndroidPlayerSwapTest {
  @Test
  fun aPlayerBuiltForASecondVideoSequenceTakesTheTransportAndTheVolume() =
    contractTest { scope ->
      val plain = androidFixtureComposition()
      val layered = plain.cutInTwo(FIXTURE_FRAME_RATE)
      val subject = AndroidEngineUnderTest(scope, SecondVideoSequencePlanner(layered))
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(plain).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val standing = subject.engine.nativePlayer

        subject.engine.setVolume(VOLUME)
        subject.engine.play()
        awaitContract("playback to move the playhead") {
          (recorder.playheads.lastOrNull() ?: Duration.ZERO) >
            Duration.ZERO
        }

        val before = recorder.playheads.size
        subject.engine.awaitComposition(layered).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to build a new player") { subject.engine.nativePlayer !== standing }
        val rebuilt = subject.engine.nativePlayer.shouldBeInstanceOf<CompositionPlayer>()

        awaitContract("the playhead to advance on the new player") {
          val since = recorder.playheads.drop(before)
          since.size >= 2 && since.last() > since.first()
        }

        recorder.lastState.playWhenReady shouldBe true
        rebuilt.onItsThread { playWhenReady } shouldBe true
        rebuilt.onItsThread { volume } shouldBe VOLUME
        subject.engine.pause()
      }
    }

  private suspend fun <T> CompositionPlayer.onItsThread(read: CompositionPlayer.() -> T): T =
    suspendCancellableCoroutine { waiting ->
      Handler(applicationLooper).post { waiting.resume(read()) }
    }

  /**
   * Plans every edit as usual, and gives the plan of [layered] its primary track a second time as another track.
   */
  private class SecondVideoSequencePlanner(
    private val layered: EditComposition,
  ) : Media3PreviewPlanner(CONTRACT_COMPONENTS) {
    override suspend fun plan(
      composition: EditComposition,
      policy: PreviewQualityPolicy,
    ): PlanResult<Media3PreviewPlan, PlaybackError> {
      val result = super.plan(composition, policy)
      if (composition != layered || result !is PlanResult.Ready) return result
      val plan = result.plan
      return PlanResult.Ready(Media3PreviewPlan(plan.resolved.withPrimaryTwice(), plan.info, plan.bounds))
    }

    private fun ResolvedComposition.withPrimaryTwice(): ResolvedComposition =
      ResolvedComposition(
        tracks = tracks + tracks.first(),
        compositionEffects = compositionEffects,
        output = output,
        layoutSize = layoutSize,
        fill = fill,
        duration = duration,
        hdr = hdr,
        hdrTransfer = hdrTransfer,
        path = path,
        audio = audio,
        adjustments = adjustments,
        encoderName = encoderName,
        naturalSize = naturalSize,
      )
  }

  private companion object {
    const val VOLUME = 0.2f
  }
}
