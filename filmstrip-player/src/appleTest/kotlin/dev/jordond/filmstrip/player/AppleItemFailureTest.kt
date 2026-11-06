package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.playbackError
import dev.jordond.filmstrip.player.internal.toPlaybackError
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOSStatusErrorDomain
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.time.Duration

/**
 * How often a preview item's failure is reported, and what a failed item does with a later play.
 *
 * A failed status comes from an item over a file that isn't there, the one way a test can make AVFoundation fail an
 * item, so everything after the status moves is the engine's real path. A stop short is posted on the item the way
 * AVFoundation posts it, which leaves a healthy item ready.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleItemFailureTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a failed item fails once and a second failed load fails once more`() =
    contractTest { scope ->
      val rig = FailingRig(scope)
      val engine = rig.engine
      val items = rig.items
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        recorder.awaitQuietFailures(1, engine)
        recorder.lastState.status shouldBe PlaybackStatus.Error(items.last().playbackError())
        recorder.failures.single() shouldBe items.last().playbackError()

        engine.awaitComposition(shorterComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        recorder.awaitQuietFailures(2, engine)
        items shouldHaveSize 2
        recorder.lastState.status shouldBe PlaybackStatus.Error(items.last().playbackError())
      }
    }

  @Test
  fun `a failed item that also says it stopped short fails once`() =
    contractTest { scope ->
      val rig = FailingRig(scope)
      val engine = rig.engine
      val items = rig.items
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        recorder.awaitQuietFailures(1, engine)

        postStoppedShort(items.last())

        recorder.awaitQuietFailures(1, engine)
      }
    }

  /**
   * The main queue is held so the notification lands while the item is still undecided, which leaves the status to
   * move on its own read afterwards.
   */
  @Test
  fun `an item that says it stopped short before it fails still fails once and moves the status`() =
    contractTest { scope ->
      val rig = FailingRig(scope)
      val engine = rig.engine
      val items = rig.items
      withEngine(engine) { recorder ->
        val mainQueue = QueueGate()
        mainQueue.hold(dispatch_get_main_queue())
        try {
          engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
          (items.last().status == AVPlayerItemStatusFailed) shouldBe false
          postStoppedShort(items.last())
          awaitContract("the stop to be reported") { recorder.failures.isNotEmpty() }
        } finally {
          mainQueue.open()
        }

        awaitContract("the item's own failure to reach the status") {
          recorder.lastState.status == PlaybackStatus.Error(items.last().playbackError())
        }
        recorder.awaitQuietFailures(1, engine)
      }
    }

  @Test
  fun `an item that stops short twice with nothing between reports once`() =
    contractTest { scope ->
      val engine = healthyEngine(scope)
      withEngine(engine) { recorder ->
        val item = engine.awaitReadyItem(recorder)

        postStoppedShort(item)
        recorder.awaitQuietFailures(1, engine)
        postStoppedShort(item)

        recorder.awaitQuietFailures(1, engine)
      }
    }

  @Test
  fun `a ready item played again after stopping short reports its next stop and halts`() =
    contractTest { scope ->
      val engine = healthyEngine(scope)
      withEngine(engine) { recorder ->
        val item = engine.awaitReadyItem(recorder)

        postStoppedShort(item)
        recorder.awaitQuietFailures(1, engine)
        engine.play()
        engine.platformPlayer.rate shouldNotBe 0f
        postStoppedShort(item)

        recorder.awaitQuietFailures(2, engine)
        engine.platformPlayer.rate shouldBe 0f
      }
    }

  @Test
  fun `a ready item sought after stopping short reports its next stop`() =
    contractTest { scope ->
      val engine = healthyEngine(scope)
      withEngine(engine) { recorder ->
        val item = engine.awaitReadyItem(recorder)

        postStoppedShort(item)
        recorder.awaitQuietFailures(1, engine)
        engine.seekTo(CLIP_LENGTH / 4, SeekAccuracy.Exact)
        postStoppedShort(item)

        recorder.awaitQuietFailures(2, engine)
      }
    }

  @Test
  fun `play on a failed item starts nothing and stays on the error`() =
    contractTest { scope ->
      val rig = FailingRig(scope)
      val engine = rig.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        recorder.awaitQuietFailures(1, engine)
        val failed = PlaybackStatus.Error(rig.items.last().playbackError())

        engine.play()

        recorder.awaitQuietFailures(1, engine)
        recorder.lastState.status shouldBe failed
        engine.platformPlayer.rate shouldBe 0f
      }
    }

  /**
   * The stop is posted from the state change that starts the rebuild, while the old item is still watched. Its hop to
   * the engine's dispatcher then lands behind the rest of the rebuild, on an engine holding a fresh item.
   */
  @Test
  fun `a stop short that lands after its item was replaced is not reported`() =
    contractTest { scope ->
      val engine = healthyEngine(scope)
      withEngine(engine) { recorder ->
        val replaced = engine.awaitReadyItem(recorder)
        val stage = StopShortOnRebuild(replaced) { postStoppedShort(it) }
        val registration = engine.addListener(stage)

        engine.awaitComposition(shorterComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        registration.cancel()
        stage.posted shouldBe true

        awaitContract("the fresh item to be ready") { recorder.lastState.status == PlaybackStatus.Ready }
        engine.platformPlayer.currentItem shouldNotBe replaced
        recorder.awaitQuietFailures(0, engine)
      }
    }

  /**
   * The main queue is held so the item names no error when its stop lands, which leaves the notification's to report.
   */
  @Test
  fun `a stop short reports the platform's error and the later status read adds no failure`() =
    contractTest { scope ->
      val rig = FailingRig(scope)
      val engine = rig.engine
      val items = rig.items
      val under = NSError.errorWithDomain(NSOSStatusErrorDomain, code = -12784, userInfo = null)
      val reason =
        NSError.errorWithDomain(AVFoundationErrorDomain, code = -11800, userInfo = mapOf(NSUnderlyingErrorKey to under))
      withEngine(engine) { recorder ->
        val mainQueue = QueueGate()
        mainQueue.hold(dispatch_get_main_queue())
        try {
          engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
          (items.last().status == AVPlayerItemStatusFailed) shouldBe false
          postStoppedShort(items.last(), reason)
          awaitContract("the stop to be reported") { recorder.failures.isNotEmpty() }
        } finally {
          mainQueue.open()
        }

        awaitContract("the item's own failure to reach the status") {
          recorder.lastState.status == PlaybackStatus.Error(items.last().playbackError())
        }
        recorder.awaitQuietFailures(1, engine)
        recorder.failures.single() shouldBe reason.toPlaybackError()
      }
    }

  private fun shorterComposition(): EditComposition =
    EditComposition(
      tracks =
        listOf(Track(listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH / 2))))),
    )

  private class FailingRig(
    scope: CoroutineScope,
  ) {
    val items = mutableListOf<AVPlayerItem>()

    val engine =
      AvPlayerEngine(
        parent = scope,
        planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
        config = PlayerConfig(),
        muted = true,
        itemOf = { missingFileItem().also { items += it } },
      )

    private fun missingFileItem(): AVPlayerItem =
      AVPlayerItem(asset = AVURLAsset(NSURL.fileURLWithPath(appleFixtureClip() + ".absent"), null))
  }

  private class StopShortOnRebuild(
    private val item: AVPlayerItem,
    private val post: (AVPlayerItem) -> Unit,
  ) : EngineListener {
    var posted = false
      private set

    override fun onStateChanged(state: PlayerState) {
      if (posted || state.status != PlaybackStatus.Preparing) return
      posted = true
      post(item)
    }

    override fun onEvent(event: PlaybackEvent) = Unit

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) = Unit
  }

  private fun healthyEngine(scope: CoroutineScope): AvPlayerEngine =
    AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)

  private suspend fun AvPlayerEngine.awaitReadyItem(recorder: ContractRecorder): AVPlayerItem {
    awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("the item to be ready") { recorder.lastState.status == PlaybackStatus.Ready }
    return platformPlayer.currentItem.shouldNotBeNull()
  }

  private fun postStoppedShort(
    item: AVPlayerItem,
    reason: NSError? = null,
  ) {
    NSNotificationCenter.defaultCenter.postNotificationName(
      AVPlayerItemFailedToPlayToEndTimeNotification ?: error("no notification name"),
      `object` = item,
      userInfo = reason?.let { mapOf(AVPlayerItemFailedToPlayToEndTimeErrorKey to it) },
    )
  }

  private val ContractRecorder.failures: List<PlaybackError>
    get() = events.filterIsInstance<PlaybackEvent.Failed>().map { it.error }

  /**
   * Waits for [count] failures, then for the callbacks the last one raised to land, and checks no more came of them.
   */
  private suspend fun ContractRecorder.awaitQuietFailures(
    count: Int,
    engine: AvPlayerEngine,
  ) {
    awaitContract("$count failures") { failures.size >= count }
    engine.drainCallbacks()
    settle()
    failures shouldHaveSize count
  }
}
