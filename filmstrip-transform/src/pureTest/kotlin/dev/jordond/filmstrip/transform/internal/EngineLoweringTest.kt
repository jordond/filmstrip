package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private val NO_GRADE: suspend (
  Composition,
  ExportSpec,
) -> ExportGrade = { _, _ -> ExportGrade.Settled(ResolvedHdr.Keep) }

/**
 * Lowerings of one engine run one at a time, so a resolver's prepared reads belong to the lowering drawing them.
 */
class EngineLoweringTest {
  private val composition = Composition(emptyList())
  private val spec = ExportSpec()

  @Test
  fun aLoweringWaitsForTheOneAheadOfItsPrepareAndLower() =
    runTest {
      val steps = mutableListOf<String>()
      val gate = CompletableDeferred<Unit>()
      val resolver = PreparingResolver { steps += "prepare" }
      var lowerings = 0
      val lowering =
        EngineLowering<Any>(listOf(resolver), NO_GRADE, { _, _, _, _ ->
          val index = lowerings++
          steps += "lower $index"
          if (index == 0) gate.await()
          steps += "lowered $index"
          refused()
        }, { _, _, _ -> emptyFlow<ExportStatus>() })

      val first = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      val second = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      runCurrent()
      steps shouldContainExactly listOf("prepare", "lower 0")

      gate.complete(Unit)
      first.await()
      second.await()

      steps shouldContainExactly listOf("prepare", "lower 0", "lowered 0", "prepare", "lower 1", "lowered 1")
    }

  @Test
  fun aCancelledLoweringLetsTheNextOneIn() =
    runTest {
      val never = CompletableDeferred<Unit>()
      var lowerings = 0
      val lowering =
        EngineLowering<Any>(emptyList(), NO_GRADE, { _, _, _, _ ->
          if (lowerings++ == 0) never.await()
          refused()
        }, { _, _, _ -> emptyFlow<ExportStatus>() })

      val stuck = launch(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      stuck.cancelAndJoin()

      lowering.lowered(composition, spec)
      lowerings shouldBe 2
    }

  @Test
  fun aWaitingLoweringCanBeCancelledWithoutEverRunning() =
    runTest {
      val gate = CompletableDeferred<Unit>()
      var lowerings = 0
      val lowering =
        EngineLowering<Any>(emptyList(), NO_GRADE, { _, _, _, _ ->
          lowerings++
          gate.await()
          refused()
        }, { _, _, _ -> emptyFlow<ExportStatus>() })

      val first = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      val waiting = launch(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      waiting.cancelAndJoin()
      gate.complete(Unit)
      first.await()

      lowerings shouldBe 1
    }

  @Test
  fun aPrepareThatFailsLetsTheQueuedLoweringRun() =
    runTest {
      val failure = IllegalStateException("prepare failed")
      val gate = CompletableDeferred<Unit>()
      val steps = mutableListOf<String>()
      var prepares = 0
      val resolver =
        PreparingResolver {
          val index = prepares++
          steps += "prepare $index"
          if (index == 0) {
            gate.await()
            throw failure
          }
        }
      val lowering =
        EngineLowering<Any>(listOf(resolver), NO_GRADE, { _, _, _, _ ->
          steps += "lower"
          refused()
        }, { _, _, _ -> emptyFlow<ExportStatus>() })

      val failing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { lowering.lowered(composition, spec) } }
      val queued = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      runCurrent()
      steps shouldContainExactly listOf("prepare 0")

      gate.complete(Unit)

      failing.await().exceptionOrNull() shouldBe failure
      queued.await()
      steps shouldContainExactly listOf("prepare 0", "prepare 1", "lower")
    }

  @Test
  fun aPrepareThatRefusesLetsTheQueuedLoweringRun() =
    runTest {
      val gate = CompletableDeferred<Unit>()
      val steps = mutableListOf<String>()
      val refusals = ArrayDeque(listOf("the picture never answered", "the next picture is refused too"))
      var refusal = ""
      val resolver =
        PreparingResolver {
          refusal = refusals.removeFirst()
          steps += "prepare"
          if (refusals.size == 1) gate.await()
        }
      val lowering =
        EngineLowering<Any>(listOf(resolver), NO_GRADE, { _, _, _, _ ->
          steps += "lower"
          refusedLowering(FilmstripError.InvalidComposition(refusal))
        }, { _, _, _ -> emptyFlow<ExportStatus>() })

      val refusing = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      val queued = async(start = CoroutineStart.UNDISPATCHED) { lowering.lowered(composition, spec) }
      runCurrent()
      steps shouldContainExactly listOf("prepare")

      gate.complete(Unit)

      refusing
        .await()
        .export
        .refusal()
        .message shouldBe "the picture never answered"
      queued
        .await()
        .export
        .refusal()
        .message shouldBe "the next picture is refused too"
      steps shouldContainExactly listOf("prepare", "lower", "prepare", "lower")
    }

  private fun refused(): LoweredExport<Nothing> = refusedLowering(FilmstripError.InvalidComposition("refused"))

  private class PreparingResolver(
    private val onPrepare: suspend () -> Unit,
  ) : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? = null

    override suspend fun prepare(composition: Composition) {
      onPrepare()
    }
  }
}
