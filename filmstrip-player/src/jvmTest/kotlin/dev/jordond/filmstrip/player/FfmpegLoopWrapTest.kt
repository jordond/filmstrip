package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.PreviewPlan
import dev.jordond.filmstrip.player.internal.frameRate
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When the desktop engine wraps a loop range and where a wrap lands, with a seek or a range change in its way.
 *
 * The pumps are scripted, so a test can hold a pump's open or one of its frames and change what the engine is asked to
 * do while it waits.
 */
class FfmpegLoopWrapTest {
  // The wrap onto the first range is held until the range has moved, so it lands after the change. It moves on to the
  // new range's first slot before any frame of the old range shows, reports that as its landing and counts one lap.
  @Test
  fun `a wrap in flight when the loop range changes lands on the new range`() =
    contractTest { scope ->
      val planner = GatedPlanner()
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH)).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val oldWrap = loopWrapOf(OLD_RANGE, duration, FIXTURE_RATE) ?: fail("$OLD_RANGE has nothing to loop")
        val newWrap = loopWrapOf(NEW_RANGE, duration, FIXTURE_RATE) ?: fail("$NEW_RANGE has nothing to loop")

        engine.setLoopRange(OLD_RANGE)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(OLD_RANGE.start, SeekAccuracy.Exact)
        awaitContract("the seek to ${OLD_RANGE.start} to complete") { recorder.seekCompletions.size > seeks }
        val opens = planner.opens.size
        val release = planner.holdNextOpen()
        engine.play()
        awaitContract("the wrap onto $OLD_RANGE to be held", timeout = PLAY_BUDGET) { planner.openHeld }
        recorder.events
          .filterIsInstance<PlaybackEvent.RangeLooped>()
          .single()
          .range shouldBe OLD_RANGE

        val events = recorder.events.size
        engine.setLoopRange(NEW_RANGE)
        release.complete(Unit)
        awaitContract("the wrap to land") { recorder.events.drop(events).any { it is PlaybackEvent.SeekCompleted } }
        val landed = recorder.playheads.size
        awaitContract("playback to go on from the landing", timeout = PLAY_BUDGET) {
          recorder.playheads.drop(landed).any { outputSlotOf(it, FIXTURE_RATE) > newWrap.firstSlot + ON_FROM_LANDING }
        }
        engine.pause()

        val landing = recorder.events.drop(events).takeWhile { it !is PlaybackEvent.SeekCompleted }
        landing.filterIsInstance<PlaybackEvent.RangeLooped>().shouldBeEmpty()
        recorder.seekCompletions.last().position shouldBe outputSlotTime(newWrap.firstSlot, FIXTURE_RATE)
        planner.opens.drop(opens) shouldBe
          listOf(oldWrap.firstSlot, newWrap.firstSlot).map { outputSlotTime(it, FIXTURE_RATE) }
        recorder.playheads.drop(landed).forEach { at ->
          outputSlotOf(at, FIXTURE_RATE) shouldBeIn newWrap.firstSlot..newWrap.lastSlot
        }
      }
    }

  // Playback is held on the loop range's last slot while a scrub past the range is waiting out its debounce, and then
  // the frame past the range is let through. No wrap fires until the scrub has landed where it was sent.
  @Test
  fun `no wrap fires while a seek past the range is in flight`() =
    contractTest { scope ->
      val planner = GatedPlanner()
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH)).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val wrap = loopWrapOf(OLD_RANGE, duration, FIXTURE_RATE) ?: fail("$OLD_RANGE has nothing to loop")
        val target = outputSlotTime(outputSlotOf(PAST_THE_RANGE, FIXTURE_RATE), FIXTURE_RATE)
        withClue("the seek to $target is not past where $OLD_RANGE wraps at ${wrap.at}") {
          (target > wrap.at).shouldBeTrue()
        }

        engine.setLoopRange(OLD_RANGE)
        val release = planner.holdFrame(wrap.lastSlot + 1)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(OLD_RANGE.start, SeekAccuracy.Exact)
        awaitContract("the seek to ${OLD_RANGE.start} to complete") { recorder.seekCompletions.size > seeks }
        engine.play()
        val last = outputSlotTime(wrap.lastSlot, FIXTURE_RATE)
        awaitContract("the range's last slot to show", timeout = PLAY_BUDGET) {
          planner.frameHeld && recorder.playhead == last
        }

        val events = recorder.events.size
        engine.seekTo(PAST_THE_RANGE, SeekAccuracy.Nearest)
        release.complete(Unit)
        awaitContract("the seek past the range to land") {
          recorder.events.drop(events).any { it is PlaybackEvent.SeekCompleted }
        }
        engine.pause()

        val inFlight = recorder.events.drop(events).takeWhile { it !is PlaybackEvent.SeekCompleted }
        inFlight.filterIsInstance<PlaybackEvent.RangeLooped>().shouldBeEmpty()
        recorder.events
          .drop(events)
          .filterIsInstance<PlaybackEvent.SeekCompleted>()
          .first()
          .position shouldBe target
      }
    }

  // A seek goes out from inside a parameter change's report, before the change clears the guard, so the wrap that
  // follows waits behind that seek in the chase. It still goes out as a wrap, and a range changed while it is in flight
  // moves its landing onto the new range. The seek's debounce is held until the wrap has fired, so the pump the seek
  // replaces always reaches the wrap first.
  @Test
  fun `a wrap held back behind a seek lands on the range standing`() =
    contractTest { scope ->
      val planner = GatedPlanner()
      val clock = HeldDelayDispatcher(scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
      val engineScope = CoroutineScope(scope.coroutineContext + clock)
      val engine = FfmpegPlayerEngine(parent = engineScope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine
          .awaitComposition(jvmFixtureComposition(listOf(Brightness(DIM))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val oldWrap = loopWrapOf(OLD_RANGE, duration, FIXTURE_RATE) ?: fail("$OLD_RANGE has nothing to loop")
        val newWrap = loopWrapOf(NEW_RANGE, duration, FIXTURE_RATE) ?: fail("$NEW_RANGE has nothing to loop")
        val wrapLanding = outputSlotTime(oldWrap.firstSlot, FIXTURE_RATE)
        val midRange = outputSlotTime((oldWrap.firstSlot + oldWrap.lastSlot) / 2, FIXTURE_RATE)
        withClue("the seek to $midRange is not past where $OLD_RANGE lands at $wrapLanding") {
          (midRange > wrapLanding).shouldBeTrue()
        }

        engine.setLoopRange(OLD_RANGE)
        val last = outputSlotTime(oldWrap.lastSlot, FIXTURE_RATE)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(last, SeekAccuracy.Exact)
        awaitContract("the seek to $last to complete") { recorder.seekCompletions.size > seeks }

        val seeker = SeekOnPreviewInfo(engine, midRange)
        val registration = engine.addListener(seeker)
        seeker.armed = true
        val release = planner.holdNextOpen(at = wrapLanding)
        // The engine is paused on the last slot, so the next delay it asks for is the seek's debounce.
        val debounce = clock.holdNextDelay()
        val events = recorder.events.size
        engine
          .awaitComposition(jvmFixtureComposition(listOf(Brightness(BRIGHT))), playWhenReady = true)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the wrap onto $OLD_RANGE to fire", timeout = PLAY_BUDGET) {
          clock.delayHeld && recorder.events.drop(events).any { it is PlaybackEvent.RangeLooped }
        }
        debounce.complete(Unit)
        awaitContract("the wrap onto $OLD_RANGE to be held", timeout = PLAY_BUDGET) { planner.openHeld }
        registration.cancel()

        engine.setLoopRange(NEW_RANGE)
        release.complete(Unit)
        awaitContract("the wrap to land") {
          recorder.events.drop(events).count { it is PlaybackEvent.SeekCompleted } >= 2
        }
        engine.pause()

        val since = recorder.events.drop(events)
        val landed = since.indices.filter { since[it] is PlaybackEvent.SeekCompleted }[1]
        val upToLanding = since.take(landed + 1)
        upToLanding.filterIsInstance<PlaybackEvent.RangeLooped>().map { it.range } shouldBe listOf(OLD_RANGE)
        upToLanding.indexOfFirst { it is PlaybackEvent.RangeLooped } shouldBeLessThan
          upToLanding.indexOfFirst { it is PlaybackEvent.SeekCompleted }
        upToLanding.filterIsInstance<PlaybackEvent.SeekCompleted>().map { it.position } shouldBe
          listOf(midRange, outputSlotTime(newWrap.firstSlot, FIXTURE_RATE))
      }
    }

  private companion object {
    // Real time for a pass or two over a short range.
    val PLAY_BUDGET = 10.seconds

    // Both ranges sit in the middle of the fixture, each starting and ending partway into a frame period.
    val OLD_RANGE = TimeRange.of(210.milliseconds, 610.milliseconds)
    val NEW_RANGE = TimeRange.of(810.milliseconds, 1_210.milliseconds)

    // Past where OLD_RANGE wraps and short of the fixture's end.
    val PAST_THE_RANGE = 1_010.milliseconds

    // How many slots past its landing playback runs before the test stops it.
    const val ON_FROM_LANDING = 3

    // Two brightness amounts, so moving from one to the other is a parameter change that keeps the timeline.
    const val DIM = 0.4f
    const val BRIGHT = 1.4f
  }
}

/**
 * Seeks [engine] to [target] from inside the next preview report once [armed], and only that once.
 *
 * A load reports what it will preview before it clears the engine's wrap guard, so the seek goes out ahead of the
 * clearing.
 */
private class SeekOnPreviewInfo(
  private val engine: FfmpegPlayerEngine,
  private val target: Duration,
) : EngineListener {
  var armed: Boolean = false

  override fun onStateChanged(state: PlayerState) = Unit

  override fun onEvent(event: PlaybackEvent) = Unit

  override fun onPosition(position: Duration) = Unit

  override fun onPreviewInfo(info: PreviewInfo) {
    if (!armed) return
    armed = false
    engine.seekTo(target, SeekAccuracy.Nearest)
  }
}

/**
 * Runs everything on [inner] and, once armed, holds the next delay asked of it until the test lets it go.
 *
 * An engine built on this waits out a scrub debounce only when the test says so, which fixes the order between the
 * debounce and a pump that would otherwise race it on the clock. Every other delay runs on [inner]'s own clock.
 */
@OptIn(InternalCoroutinesApi::class)
private class HeldDelayDispatcher(
  private val inner: CoroutineDispatcher,
) : CoroutineDispatcher(),
  Delay {
  private val clock: Delay = inner as? Delay ?: error("$inner keeps no clock of its own")
  private var hold: CompletableDeferred<Unit>? = null

  @Volatile
  var delayHeld: Boolean = false
    private set

  fun holdNextDelay(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { hold = it }

  override fun isDispatchNeeded(context: CoroutineContext): Boolean = inner.isDispatchNeeded(context)

  override fun dispatch(
    context: CoroutineContext,
    block: Runnable,
  ) = inner.dispatch(context, block)

  override fun scheduleResumeAfterDelay(
    timeMillis: Long,
    continuation: CancellableContinuation<Unit>,
  ) {
    val gate = hold ?: return clock.scheduleResumeAfterDelay(timeMillis, continuation)
    hold = null
    delayHeld = true
    gate.invokeOnCompletion { continuation.resume(Unit) }
  }
}

/**
 * Lowers edits the way the real planner does, and opens a scripted stream in place of spawning ffmpeg.
 *
 * Records where each pump opens. A test can hold the next open, or the next one at a given position, until it lets it
 * go, or hold every stream opened from now on before it hands over the frame on one slot.
 */
private class GatedPlanner : FfmpegPreviewPlanner(CONTRACT_COMPONENTS) {
  private var openGate: CompletableDeferred<Unit>? = null
  private var openGateAt: Duration? = null
  private var frameHold: FrameHold? = null

  @Volatile
  var openHeld: Boolean = false
    private set

  @Volatile
  var opens: List<Duration> = emptyList()
    private set

  val frameHeld: Boolean get() = frameHold?.reached == true

  fun holdNextOpen(at: Duration? = null): CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().also {
      openGate = it
      openGateAt = at
    }

  fun holdFrame(slot: Long): CompletableDeferred<Unit> = FrameHold(slot).also { frameHold = it }.gate

  override suspend fun open(
    plan: PreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult {
    opens = opens + at
    openGate?.takeIf { openGateAt == null || openGateAt == at }?.let { gate ->
      openGate = null
      openHeld = true
      gate.await()
    }
    val end = outputSlotOf(plan.resolved.duration, plan.frameRate)
    return PreviewStreamResult.Opened(HeldStream(at, plan.frameRate, end, frameHold))
  }
}

/**
 * Where a scripted stream stops before handing over the frame on [slot], until [gate] completes.
 */
private class FrameHold(
  val slot: Long,
) {
  val gate: CompletableDeferred<Unit> = CompletableDeferred()

  @Volatile
  var reached: Boolean = false
}

/**
 * A stream opened at [startPosition] that hands over one blank frame a slot up to, and not including, [end], and then
 * exits clean.
 *
 * Slots are counted from the start position on the grid at the given frame rate, the way the engine counts them.
 */
private class HeldStream(
  override val startPosition: Duration,
  frameRate: Int,
  private val end: Long,
  private val hold: FrameHold?,
) : PreviewStream {
  private var slot = outputSlotOf(startPosition, frameRate)

  @Volatile
  private var closed = false

  override val size: Size = Size(1, 1)

  override suspend fun next(): ByteArray? {
    if (closed || slot >= end) return null
    hold?.takeIf { it.slot == slot }?.let {
      it.reached = true
      it.gate.await()
    }
    if (closed) return null
    slot++
    return ByteArray(BYTES_PER_PIXEL)
  }

  override suspend fun exitStatus(): Int? = if (closed) null else 0

  override fun errors(): String = ""

  override fun close() {
    closed = true
  }

  private companion object {
    const val BYTES_PER_PIXEL = 4
  }
}
