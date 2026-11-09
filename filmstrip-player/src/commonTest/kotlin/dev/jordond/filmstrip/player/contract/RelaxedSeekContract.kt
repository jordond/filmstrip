package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.seekLandingOf
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the playhead reads while a relaxed seek is in flight and after it lands, run by [PlayerEngineContractTest] on
 * every backend.
 *
 * A relaxed seek reads the slot an exact seek to its target lands on until it completes, then moves once to the slot
 * it landed on, which can be before the target.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case seeks inside.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class RelaxedSeekContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: Composition,
  private val rate: Int,
) {
  /**
   * Seeks relaxed from the start to each of [TARGETS] and to a target [offGridAt] of the way in, with the player
   * paused.
   *
   * Every reading between a seek and its completion is the requested slot or the landing, the last of them is the
   * landing, and every reading after it until the engine goes quiet is the landing too.
   */
  fun holdsThenStaysWhereItLanded(offGridAt: Double): TestResult =
    eachTarget(offGridAt, playing = false) { _, readings, landed ->
      readings.forEach { it shouldBe landed }
    }

  /**
   * Seeks relaxed from the start to each of [TARGETS] and to a target [offGridAt] of the way in, playing while each
   * seek is in flight so the ticker reads the playhead.
   *
   * Every reading between a seek and its completion is the requested slot or the landing, the last of them is the
   * landing, and from there playback only moves forward from the landing until it is paused.
   */
  fun holdsThenPlaysOnFromWhereItLanded(offGridAt: Double): TestResult =
    eachTarget(offGridAt, playing = true) { duration, readings, landed ->
      // Compared on slots, so a platform clock still reading a fraction of a frame short of the landing is not a move.
      val slots = readings.map { seekLandingOf(it, duration, rate) }
      slots.forEach { it shouldBeGreaterThanOrEqualTo landed }
      slots.zipWithNext().forEach { (earlier, later) -> later shouldBeGreaterThanOrEqualTo earlier }
    }

  private fun eachTarget(
    offGridAt: Double,
    playing: Boolean,
    afterLanding: (duration: Duration, readings: List<Duration>, landed: Duration) -> Unit,
  ): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to seek within.")

        for (at in TARGETS + offGrid(duration, offGridAt, rate, thirds = 1)) {
          val parked = recorder.seekCompletions.size
          subject.engine.seekTo(Duration.ZERO, SeekAccuracy.Exact)
          awaitContract("the seek back to the start to complete") { recorder.seekCompletions.size > parked }
          awaitStep("the engine to go quiet") { subject.awaitQuiet() }

          val requested = seekLandingOf(at, duration, rate)
          val from = recorder.playheads.size
          val seeks = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Nearest)
          if (playing) subject.engine.play()
          awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > seeks }
          if (playing) subject.engine.pause()
          awaitStep("the engine to go quiet") { subject.awaitQuiet() }

          val completed =
            recorder.events
              .withIndex()
              .filter { it.value is PlaybackEvent.SeekCompleted }[seeks]
              .index
          val landed = recorder.seekCompletions[seeks].position
          val atCompletion = recorder.playheadsAtEvents[completed]
          val held = recorder.playheads.subList(from, atCompletion)
          val after = recorder.playheads.drop(atCompletion)
          withClue("Readings $held then $after for the seek to $at, asking for $requested and landing on $landed.") {
            held.shouldNotBeEmpty()
            held.forEach { it shouldBeIn listOf(requested, landed) }
            held.last() shouldBe landed
            held.dropWhile { it == requested }.forEach { it shouldBe landed }
            afterLanding(duration, after, landed)
          }
        }
      }
    }

  private companion object {
    // Well inside every fixture. On a fixture with a sync sample every 500 ms the first sits mid group and the second
    // just past a sync sample, so a backend landing on the sample before lands short of each.
    val TARGETS: List<Duration> = listOf(700.milliseconds, 1050.milliseconds)
  }
}
