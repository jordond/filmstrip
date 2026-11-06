package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngineVideoPlayerTest {
  @Test
  fun theInitialCompositionIsLoadedHoldingThePlayheadAndPaused() {
    val engine = FakePlayerEngine()

    EngineVideoPlayer(engine, FIRST)

    val request = engine.requests.single()
    request.composition shouldBeSameInstanceAs FIRST
    request.startAt.shouldBeNull()
    request.playWhenReady shouldBe false
  }

  @Test
  fun whatTheEnginePushesReachesStateAndPreviewInfo() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)
    val ready = PlayerState(PlaybackStatus.Ready, true, false, false, 4.seconds)
    val info = PreviewInfo(Size(1280, 720), 0.5f, EffectParity.Exact, emptyList(), emptyList())

    player.state.value shouldBe PlayerState.Initial

    engine.push { onStateChanged(ready) }
    engine.push { onPreviewInfo(info) }

    player.state.value shouldBeSameInstanceAs ready
    player.previewInfo.value shouldBeSameInstanceAs info
  }

  @Test
  fun anEngineEventReachesACollectorOnce() =
    runTest {
      val engine = FakePlayerEngine()
      val player = EngineVideoPlayer(engine, FIRST)
      val heard = mutableListOf<PlaybackEvent>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { heard += it } }

      engine.push { onEvent(PlaybackEvent.FirstFrameRendered) }

      heard shouldBe listOf<PlaybackEvent>(PlaybackEvent.FirstFrameRendered)
    }

  // A scrubber reads the position straight after it seeks, before the engine has caught up.
  @Test
  fun aSeekMovesThePositionBeforeTheEngineReportsIt() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)

    player.seekTo(3.seconds, SeekAccuracy.Nearest)

    player.currentPosition() shouldBe 3.seconds
    engine.seeks shouldBe listOf(3.seconds to SeekAccuracy.Nearest)

    engine.push { onPosition(2_900.milliseconds) }

    player.currentPosition() shouldBe 2_900.milliseconds
  }

  @Test
  fun aTickedPositionFlowWakesOnlyWhenTheSnappedValueChanges() =
    runTest {
      val engine = FakePlayerEngine()
      val player = EngineVideoPlayer(engine, FIRST)
      val seen = mutableListOf<Duration>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        player.positionFlow(100.milliseconds).toList(seen)
      }

      for (millis in listOf(40, 90, 120, 199, 260)) engine.push { onPosition(millis.milliseconds) }

      seen shouldBe listOf(Duration.ZERO, 100.milliseconds, 200.milliseconds)
    }

  @Test
  fun anUntickedPositionFlowCarriesEveryPositionUnsnapped() =
    runTest {
      val engine = FakePlayerEngine()
      val player = EngineVideoPlayer(engine, FIRST)
      val seen = mutableListOf<Duration>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        player.positionFlow(Duration.ZERO).toList(seen)
      }

      for (millis in listOf(40, 90, 120)) engine.push { onPosition(millis.milliseconds) }

      seen shouldBe listOf(Duration.ZERO, 40.milliseconds, 90.milliseconds, 120.milliseconds)
    }

  @Test
  fun aPositionListenerHearsEachSnappedValueOnceUntilItIsCancelled() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)
    val heard = mutableListOf<Duration>()

    val handle = player.addPositionListener(250.milliseconds) { heard += it }
    for (millis in listOf(10, 240, 260, 499, 760)) engine.push { onPosition(millis.milliseconds) }
    engine.push { onStateChanged(PlayerState.Initial) }
    handle.cancel()
    engine.push { onPosition(2.seconds) }

    heard shouldBe listOf(Duration.ZERO, 250.milliseconds, 750.milliseconds)
  }

  // A scrubber driven by the listener form has to hold the seek target the same way one driven by
  // the flow does, rather than snapping back until the engine lands.
  @Test
  fun aPositionListenerHearsASeekBeforeTheEngineLandsOnIt() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)
    val heard = mutableListOf<Duration>()

    player.addPositionListener(Duration.ZERO) { heard += it }
    player.seekTo(1_500.milliseconds, SeekAccuracy.Exact)

    heard shouldBe listOf(Duration.ZERO, 1_500.milliseconds)
    heard.last() shouldBe player.currentPosition()
  }

  @Test
  fun aStateListenerHearsStatesAndNothingElse() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)
    val heard = mutableListOf<PlayerState>()
    val ready = PlayerState(PlaybackStatus.Ready, false, false, false, 4.seconds)

    player.addStateListener { heard += it }
    engine.push { onPosition(1.seconds) }
    engine.push { onEvent(PlaybackEvent.FirstFrameRendered) }
    engine.push { onStateChanged(ready) }

    heard shouldBe listOf(ready)
  }

  @Test
  fun settingACompositionSuspendsUntilTheEngineAnswers() =
    runTest {
      val engine = FakePlayerEngine()
      val player = EngineVideoPlayer(engine, FIRST)

      val result = async { player.setComposition(SECOND, startAt = 1.seconds, playWhenReady = true) }
      runCurrent()
      val request = engine.requests.last()
      request.composition shouldBeSameInstanceAs SECOND
      request.startAt shouldBe 1.seconds
      request.playWhenReady shouldBe true
      result.isActive.shouldBeTrue()

      engine.answerLast(SetCompositionResult.Success(4.seconds))

      result.await() shouldBe SetCompositionResult.Success(4.seconds)
    }

  @Test
  fun cancellingASuspendedLoadCancelsTheEngineRequest() =
    runTest {
      val engine = FakePlayerEngine()
      val player = EngineVideoPlayer(engine, FIRST)

      val result = async { player.setComposition(SECOND, startAt = null, playWhenReady = false) }
      runCurrent()
      result.cancel()
      runCurrent()

      engine.cancelledRequests shouldBe 1
    }

  @Test
  fun closingDetachesFromTheEngineAndDisposesIt() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)

    player.close()

    engine.listeners.size shouldBe 0
    engine.disposed.shouldBeTrue()
  }

  @Test
  fun transportAndRangeCallsReachTheEngine() {
    val engine = FakePlayerEngine()
    val player = EngineVideoPlayer(engine, FIRST)
    val range = TimeRange.of(1.seconds, 3.seconds)

    player.play()
    player.pause()
    player.stepFrames(-2)
    player.setVolume(0.25f)
    player.setLoopRange(range)
    player.setPlaybackRange(null)
    player.setQualityPolicy(PreviewQualityPolicy.Full)
    player.setExportSpec(ExportSpec(hdr = HdrMode.ToneMapToSdr))

    engine.calls shouldBe
      listOf(
        "play",
        "pause",
        "step -2",
        "volume 0.25",
        "loop $range",
        "range null",
        "quality Full",
        "export ToneMapToSdr",
      )
  }

  private companion object {
    val FIRST: EditComposition = compositionOf { clip(MediaSource.of("/fixtures/first.mp4")) }

    val SECOND: EditComposition = compositionOf { clip(MediaSource.of("/fixtures/second.mp4")) }
  }
}
