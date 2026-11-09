package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.seekLandingOf
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where a seek into the held tail on the AVFoundation engine reports it landed.
 *
 * The primary is a short cut of the fixture under an audio track that runs well past it, so the picture holds the
 * primary's last frame from [heldTail]'s first slot to the end. On this backend that tail is one frame stretched over
 * the whole run, and every slot in it shows the same picture, so a seek anywhere inside it lands on the slot it asked
 * for rather than on the slot the stretch opens on.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHeldTailSeekMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a relaxed seek into the middle of the tail lands on its own slot`() =
    withHeldTail { engine, recorder, resolved ->
      val at = midTail(resolved)
      engine.seekAndAwait(recorder, at, SeekAccuracy.Nearest)

      val reported = recorder.seekCompletions.last().position
      reported shouldBe resolved.seekLandingOf(at)
      reported shouldBe slotTime(outputSlotOf(at, FIXTURE_FRAME_RATE))
      resolved.heldTail shouldContain outputSlotOf(reported, FIXTURE_FRAME_RATE)

      engine.drainCallbacks()
      recorder.playhead shouldBe reported
    }

  @Test
  fun `an exact seek into the middle of the tail lands on its own slot`() =
    withHeldTail { engine, recorder, resolved ->
      val at = midTail(resolved)
      engine.seekAndAwait(recorder, at, SeekAccuracy.Exact)

      val reported = recorder.seekCompletions.last().position
      reported shouldBe resolved.seekLandingOf(at)
      reported shouldBe slotTime(outputSlotOf(at, FIXTURE_FRAME_RATE))

      engine.drainCallbacks()
      recorder.playhead shouldBe reported
    }

  @Test
  fun `a seek to either end of the tail lands on that end's slot`() =
    withHeldTail { engine, recorder, resolved ->
      val tail = resolved.heldTail
      val ends = listOf(slotTime(tail.first) to slotTime(tail.first), resolved.duration to slotTime(tail.last))
      listOf(SeekAccuracy.Nearest, SeekAccuracy.Exact).forEach { accuracy ->
        ends.forEach { (at, slot) ->
          engine.seekAndAwait(recorder, at, accuracy)

          withClue("a ${accuracy.name} seek to $at") {
            val reported = recorder.seekCompletions.last().position
            reported shouldBe resolved.seekLandingOf(at)
            reported shouldBe slot
            engine.drainCallbacks()
            recorder.playhead shouldBe slot
          }
        }
      }
    }

  /**
   * A time a third of a frame into the slot halfway along the tail, so it sits well away from the slot the stretch
   * opens on and from the end, and off the slot's own start.
   */
  private fun midTail(resolved: ResolvedComposition): Duration {
    val tail = resolved.heldTail
    val middle = tail.first + (tail.last - tail.first) / 2
    return slotTime(middle) + FIXTURE_FRAME_STEP / 3
  }

  private fun withHeldTail(body: suspend (AvPlayerEngine, ContractRecorder, ResolvedComposition) -> Unit) =
    contractTest { scope ->
      val composition = heldTailComposition()
      val resolved = appleExportLowering(composition)
      withClue("the tail holds only ${resolved.heldTail}") {
        (resolved.heldTail.count() > FIXTURE_FRAME_RATE).shouldBeTrue()
      }

      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        body(engine, recorder, resolved)
      }
    }

  private fun heldTailComposition(): Composition {
    val source = MediaSource.of(appleFixtureClip())
    return Composition(
      tracks =
        listOf(
          Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, PRIMARY_LENGTH)))),
          Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, BED_LENGTH))), content = TrackContent.Audio),
        ),
    )
  }

  private suspend fun AvPlayerEngine.seekAndAwait(
    recorder: ContractRecorder,
    at: Duration,
    accuracy: SeekAccuracy,
  ) {
    val before = recorder.seekCompletions.size
    seekTo(at, accuracy)
    awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }
  }

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, FIXTURE_FRAME_RATE)

  private companion object {
    val PRIMARY_LENGTH: Duration = 500.milliseconds
    val BED_LENGTH: Duration = 1800.milliseconds
  }
}
