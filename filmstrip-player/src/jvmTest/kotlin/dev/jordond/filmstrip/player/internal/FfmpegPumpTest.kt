package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.jvmFixtureComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What one desktop pump shows, answers and reports, against scripted streams rather than a process.
 *
 * The pump runs on the test's virtual clock, so its pacing costs nothing and every frame it shows is checked by slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegPumpTest {
  private val plan = fixturePlan()
  private val rate = plan.frameRate

  @Test
  fun `a paused pump shows the frame it landed on, answers its seek and parks`() =
    runTest {
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 5)) }
      rig.wanted.value = false
      val landings = mutableListOf<Duration>()
      rig.ends.hold { landings += it }

      rig.pump.start(slotTime(START))
      runCurrent()
      val quiet = async(start = CoroutineStart.UNDISPATCHED) { rig.pump.awaitQuiet() }
      runCurrent()

      rig.shown shouldBe listOf(slotTime(START))
      landings shouldBe listOf(slotTime(START))
      quiet.isCompleted shouldBe true
      rig.pump.isRunning shouldBe true
      rig.finishes.shouldBeEmpty()
      rig.pump.stop()
    }

  @Test
  fun `a playing pump shows every frame on its own slot and then reports it ran out`() =
    runTest {
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 5)) }

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      rig.shown shouldBe (START until START + 5).map(::slotTime)
      rig.pixels shouldBe (0 until 5).map { it.toByte() }
      rig.finishes shouldBe listOf(true)
      rig.pump.isRunning shouldBe false
    }

  @Test
  fun `a stream opened at its head is read past the frames before the position`() =
    runTest {
      val rig = Rig(this) { opened(IndexedStream(Duration.ZERO, frames = START.toInt() + 3)) }

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      rig.shown shouldBe (START until START + 3).map(::slotTime)
      rig.pixels.first() shouldBe START.toByte()
    }

  @Test
  fun `a pump stops on the last slot of a playback range and finishes once`() =
    runTest {
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 20)) }
      val range = TimeRange.of(Duration.ZERO, slotTime(START + 4))
      rig.ends.playbackRange = range

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      rig.shown.last() shouldBe slotTime(lastSlotOf(range, rate) ?: error("$range has an end"))
      rig.finishes shouldBe listOf(true)
    }

  @Test
  fun `a process that exits with a status reports it as the stream classifies it and does not finish`() =
    runTest {
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 2, status = FAILED, stderr = STDERR)) }

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      rig.processFailures shouldBe listOf(FilmstripError.Underlying(FAILED, STDERR).toPlaybackError())
      rig.finishes.shouldBeEmpty()
    }

  // Against a plan that says the slot holds a frame, a clean exit with nothing written is a failure and not an end.
  @Test
  fun `a process that exits cleanly having written nothing is reported and does not finish`() =
    runTest {
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 0)) }

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      val error = rig.processFailures.single().shouldBeInstanceOf<PlaybackError.Underlying>()
      error.platformCode shouldBe 0
      error.message shouldContain "wrote no frame"
      rig.finishes.shouldBeEmpty()
    }

  @Test
  fun `a process that could not open is reported and its seek is still answered`() =
    runTest {
      val rig = Rig(this) { PreviewStreamResult.Refused(FilmstripError.SourceUnreadable("clip.mp4", WHY)) }
      val landings = mutableListOf<Duration>()
      rig.ends.hold { landings += it }

      rig.pump.start(slotTime(START))
      advanceUntilIdle()

      rig.failures
        .single()
        .shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
        .message shouldBe WHY
      landings.size shouldBe 1
      rig.shown.shouldBeEmpty()
    }

  @Test
  fun `a newer start closes the pump it replaces, which shows nothing more`() =
    runTest {
      val streams = mutableListOf<IndexedStream>()
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 5).also { streams += it }) }
      rig.wanted.value = false

      rig.pump.start(slotTime(START))
      runCurrent()
      rig.pump.start(slotTime(LATER))
      rig.pump.awaitRetired()
      runCurrent()

      streams.first().closed shouldBe true
      rig.shown shouldBe listOf(slotTime(START), slotTime(LATER))
      rig.pump.isRunning shouldBe true
      rig.pump.stop()
    }

  @Test
  fun `a stopped pump closes its stream and reports no failure`() =
    runTest {
      val streams = mutableListOf<IndexedStream>()
      val rig = Rig(this) { at -> opened(IndexedStream(at, frames = 5).also { streams += it }) }
      rig.wanted.value = false
      rig.pump.start(slotTime(START))
      runCurrent()

      rig.pump.stop()
      rig.pump.awaitIdle()

      rig.pump.isRunning shouldBe false
      streams.single().closed shouldBe true
      rig.failures.shouldBeEmpty()
      rig.processFailures.shouldBeEmpty()
      rig.finishes.shouldBeEmpty()
    }

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, rate)

  private fun opened(stream: IndexedStream): PreviewStreamResult = PreviewStreamResult.Opened(stream)

  /**
   * One pump over the fixture plan, with everything it reports recorded.
   */
  private inner class Rig(
    scope: TestScope,
    open: (at: Duration) -> PreviewStreamResult,
  ) {
    var position: Duration = Duration.ZERO
    val wanted = MutableStateFlow(true)
    val shown = mutableListOf<Duration>()
    val pixels = mutableListOf<Byte>()
    val finishes = mutableListOf<Boolean>()
    val failures = mutableListOf<PlaybackError>()
    val processFailures = mutableListOf<PlaybackError>()
    private var presented: PreviewFrame? = null

    private val planner = StreamPlanner(open)

    val ends: FfmpegRangeEnd =
      FfmpegRangeEnd(
        presented = { presented },
        position = { position },
        moveTo = { position = it },
        wanted = wanted,
        startPump = { from, onLanded ->
          ends.hold(onLanded)
          pump.start(from)
        },
        seekToWrap = { },
        looped = { },
        finish = { finishes += it },
      )

    val pump: FfmpegPump =
      FfmpegPump(
        scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler)),
        planner = planner,
        plan = { plan },
        composition = { jvmFixtureComposition() },
        wanted = wanted,
        ends = ends,
        show = { frame ->
          presented = frame
          position = frame.position
          shown += frame.position
          pixels += frame.pixels.single()
        },
        fail = { failures += it },
        failProcess = { processFailures += it },
      )
  }

  private companion object {
    // Inside the fixture and clear of either end of it.
    const val START = 10L
    const val LATER = 25L

    const val FAILED = 1
    const val STDERR = "clip.mp4: Invalid data found when processing input"
    const val WHY = "The clip could not be read."
  }
}
