package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BasePlayerEngineTest {
  private val engine = FakePlayerEngine(TestScope())
  private val listener = RecordingListener().also { engine.addListener(it) }

  @Test
  fun `a burst of seeks completes once per call at every burst size`() {
    for (burst in listOf(2, 3, 5, 9, 17, 50)) {
      val fresh = FakePlayerEngine(TestScope())
      val watcher = RecordingListener().also { fresh.addListener(it) }
      fresh.becomeReady(10.seconds)

      val positions = (1..burst).map { it.milliseconds }
      positions.forEach { fresh.seekTo(it, SeekAccuracy.Exact) }
      fresh.platform.drain()

      watcher.seekCompletions.size shouldBe burst
      watcher.seekCompletions.map { it.position }.sorted() shouldBe positions
      watcher.states.last().isSeeking shouldBe false
    }
  }

  // A backend that reaches Ready before the item is seekable says Ready again once it is. The
  // status has not moved, so an early return would strand the queued seek.
  @Test
  fun `a repeated ready status still releases a seek that was waiting on seekability`() {
    engine.platform.isReady = false
    engine.becomeReady(10.seconds)

    engine.seekTo(40.milliseconds, SeekAccuracy.Exact)
    listener.seekCompletions.shouldBeEmpty()

    engine.platform.isReady = true
    engine.becomeReady(10.seconds)
    engine.platform.drain()

    listener.seekCompletions.map { it.position } shouldBe listOf(40.milliseconds)
    listener.states.last().isSeeking shouldBe false
  }

  // A listener that changes an axis from inside onStateChanged used to leave every later listener
  // holding the state from before its change.
  @Test
  fun `a listener that reacts by changing state leaves no sibling behind`() {
    val reactor = FakePlayerEngine(TestScope())
    val second = RecordingListener()
    var reacted = false

    reactor.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) {
          if (!reacted && state.status == PlaybackStatus.Ready) {
            reacted = true
            reactor.stall(true)
          }
        }

        override fun onEvent(event: PlaybackEvent) = Unit

        override fun onPosition(position: Duration) = Unit

        override fun onPreviewInfo(info: PreviewInfo) = Unit
      },
    )
    reactor.addListener(second)

    reactor.becomeReady(10.seconds)

    second.states.last().isStalled shouldBe true
  }

  @Test
  fun `a seek issued before the engine is ready completes once it becomes ready`() {
    engine.platform.isReady = false

    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)
    engine.seekTo(20.milliseconds, SeekAccuracy.Exact)

    engine.platform.issued shouldBe emptyList()
    listener.seekCompletions.size shouldBe 1
    listener.states.last().isSeeking shouldBe true

    engine.platform.isReady = true
    engine.becomeReady(10.seconds)
    engine.platform.drain()

    listener.seekCompletions.size shouldBe 2
    listener.states.last().isSeeking shouldBe false
  }

  @Test
  fun `a failure resolves outstanding seeks rather than wedging the scrubber`() {
    engine.becomeReady(10.seconds)

    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)
    engine.seekTo(20.milliseconds, SeekAccuracy.Exact)
    engine.fail(PlaybackError.SourceUnreadable("gone"))

    listener.seekCompletions.size shouldBe 2
    listener.states.last().isSeeking shouldBe false
  }

  @Test
  fun `a rebuild resolves outstanding seeks against the timeline being replaced`() {
    engine.becomeReady(10.seconds)

    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)
    engine.becomePreparing()

    listener.seekCompletions.size shouldBe 1
    listener.states.last().isSeeking shouldBe false
  }

  @Test
  fun `scrubbing relaxes accuracy and endScrub settles exact`() {
    engine.becomeReady(10.seconds)

    engine.beginScrub()
    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)
    engine.platform.drain()
    engine.endScrub()
    engine.platform.drain()

    engine.scrubbing shouldBe listOf(true, false)
    engine.platform.issued.map { it.position to it.accuracy } shouldBe
      listOf(
        10.milliseconds to SeekAccuracy.Nearest,
        10.milliseconds to SeekAccuracy.Exact,
      )
    listener.seekCompletions.size shouldBe 2
  }

  // The awkward one: the finger lifts while a relaxed seek is still in flight, so the exact settle
  // queues behind it. Four requests reach the chase, three from seekTo and one from endScrub, and
  // all four have to complete.
  @Test
  fun `a scrub ending while a relaxed seek is outstanding still completes every request`() {
    engine.becomeReady(10.seconds)

    engine.beginScrub()
    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)
    engine.seekTo(20.milliseconds, SeekAccuracy.Exact)
    engine.seekTo(30.milliseconds, SeekAccuracy.Exact)
    engine.endScrub()

    engine.platform.issued.size shouldBe 1
    engine.platform.drain()

    listener.seekCompletions.size shouldBe 4
    engine.platform.issued.map { it.position to it.accuracy } shouldBe
      listOf(
        10.milliseconds to SeekAccuracy.Nearest,
        30.milliseconds to SeekAccuracy.Exact,
      )
    listener.states.last().isSeeking shouldBe false
  }

  @Test
  fun `endScrub settles nothing when the scrub moved nothing`() {
    engine.becomeReady(10.seconds)

    engine.beginScrub()
    engine.endScrub()

    engine.platform.issued shouldBe emptyList()
    engine.scrubbing shouldBe listOf(true, false)
    listener.seekCompletions shouldBe emptyList()
  }

  @Test
  fun `play flips intent before the platform is asked to do anything`() {
    engine.becomeReady(10.seconds)

    engine.play()
    listener.states.last().playWhenReady shouldBe true
    engine.plays shouldBe 1

    engine.pause()
    listener.states.last().playWhenReady shouldBe false
    engine.pauses shouldBe 1
  }

  @Test
  fun `an external change flips intent and emits exactly once`() {
    engine.becomeReady(10.seconds)
    engine.play()

    engine.reportExternal(false)

    listener.externalChanges.map { it.playWhenReady } shouldBe listOf(false)
    listener.states.last().playWhenReady shouldBe false
  }

  // Audio focus loss and the output route going away arrive together often enough that a UI would
  // otherwise show the same interruption twice.
  @Test
  fun `a second occasion reporting the same intent emits nothing`() {
    engine.becomeReady(10.seconds)
    engine.play()

    engine.reportExternal(false)
    engine.reportExternal(false)

    listener.externalChanges.size shouldBe 1
  }

  @Test
  fun `preview info reaches the listeners already registered`() {
    engine.publishPreviewInfo(PREVIEW_INFO)

    listener.previewInfo shouldBe listOf(PREVIEW_INFO)
  }

  // A surface is built after the composition is loaded as often as before it, and it sizes itself
  // off this. A listener that had to wait for the next load would draw at the wrong aspect until
  // the edit changed.
  @Test
  fun `a listener registering afterwards is replayed the preview info that already went out`() {
    engine.publishPreviewInfo(PREVIEW_INFO)

    val late = RecordingListener().also { engine.addListener(it) }

    late.previewInfo shouldBe listOf(PREVIEW_INFO)
  }

  @Test
  fun `a listener registering before anything was published is replayed nothing`() {
    val late = RecordingListener().also { engine.addListener(it) }

    late.previewInfo.shouldBeEmpty()
  }

  @Test
  fun `every axis reaches the snapshot together`() {
    engine.becomeReady(4.seconds)
    engine.play()
    engine.stall(true)

    val state = listener.states.last()
    state.status shouldBe PlaybackStatus.Ready
    state.playWhenReady shouldBe true
    state.isStalled shouldBe true
    state.isSeeking shouldBe false
    state.duration shouldBe 4.seconds
    state.isPlaying shouldBe false
    state.isBusy shouldBe true
  }

  @Test
  fun `a listener registering late is handed the current snapshot`() {
    engine.becomeReady(4.seconds)
    engine.play()

    val late = RecordingListener().also { engine.addListener(it) }

    late.states.size shouldBe 1
    late.states.single().duration shouldBe 4.seconds
    late.states.single().playWhenReady shouldBe true
  }

  @Test
  fun `a cancelled registration stops receiving`() {
    val late = RecordingListener()
    engine.addListener(late).cancel()

    engine.becomeReady(4.seconds)

    late.states.size shouldBe 1
  }

  @Test
  fun `dispose takes down what the engine launched and leaves the caller's scope alone`() =
    runTest {
      val disposing = FakePlayerEngine(backgroundScope)
      val work = disposing.launchOnEngine { awaitCancellation() }

      disposing.dispose()

      work.isCancelled shouldBe true
      backgroundScope.isActive shouldBe true
    }

  @Test
  fun `the ticker reads while playing and stops when it is not`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.play()

      advanceTimeBy(100.milliseconds)
      val whilePlaying = watcher.positions.size
      (whilePlaying > 1) shouldBe true

      ticking.pause()
      advanceTimeBy(100.milliseconds)

      watcher.positions.size shouldBe whilePlaying
    }

  @Test
  fun `a stall stops the ticker without touching intent`() =
    runTest {
      val ticking = FakePlayerEngine(backgroundScope)
      val watcher = RecordingListener().also { ticking.addListener(it) }
      ticking.becomeReady(10.seconds)
      ticking.play()

      advanceTimeBy(100.milliseconds)
      ticking.stall(true)
      val whileStalled = watcher.positions.size
      advanceTimeBy(100.milliseconds)

      watcher.positions.size shouldBe whileStalled
      watcher.states.last().playWhenReady shouldBe true
    }

  @Test
  fun `dispose delivers Released and resolves outstanding seeks once`() {
    engine.becomeReady(10.seconds)
    engine.seekTo(10.milliseconds, SeekAccuracy.Exact)

    engine.dispose()

    listener.seekCompletions.size shouldBe 1
    listener.states.last().status shouldBe PlaybackStatus.Released

    engine.dispose()
    engine.releases shouldBe 1
  }

  private companion object {
    val PREVIEW_INFO =
      PreviewInfo(
        outputSize = Size(1280, 720),
        renderScale = 0.5f,
        parity = EffectParity.Exact,
        parityNotes = emptyList(),
        fidelity = emptyList(),
      )
  }
}
