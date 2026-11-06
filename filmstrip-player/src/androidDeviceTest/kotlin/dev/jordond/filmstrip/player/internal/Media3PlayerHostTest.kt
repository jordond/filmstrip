package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contractContext
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Which player the media3 host keeps standing on its own thread, built and released there.
 */
class Media3PlayerHostTest {
  @Test
  fun startingBuildsThePlayerOnItsOwnThreadAndAnswersFromThere() =
    contractTest { scope ->
      val rig = Rig(scope)

      val described = rig.host.start { Looper.myLooper() }

      described shouldBe rig.thread.looper
      rig.built.single() shouldBeSameInstanceAs rig.host.player
      rig.onThread { rig.host.release() }
    }

  @Test
  fun aPlayerThatTakesTheInputsAskedForIsKept() =
    contractTest { scope ->
      val rig = Rig(scope)
      rig.host.start { }
      val standing = rig.host.player

      val kept = rig.onThread { rig.host.playerFor(false, Composition.HDR_MODE_KEEP_HDR) }

      kept shouldBeSameInstanceAs standing
      rig.built.size shouldBe 1
      rig.released.size shouldBe 0
      rig.onThread { rig.host.release() }
    }

  @Test
  fun aPlayerThatCannotTakeTheInputsIsReleasedAndRebuilt() =
    contractTest { scope ->
      val rig = Rig(scope)
      rig.host.start { }
      val standing = rig.host.player

      val rebuilt = rig.onThread { rig.host.playerFor(true, Composition.HDR_MODE_KEEP_HDR) }

      rig.released.single() shouldBeSameInstanceAs standing
      rig.built.last() shouldBeSameInstanceAs rebuilt
      rig.host.player shouldBeSameInstanceAs rebuilt
      rig.onThread { rig.host.playerFor(true, Composition.HDR_MODE_KEEP_HDR) } shouldBeSameInstanceAs rebuilt
      rig.onThread { rig.host.release() }
    }

  @Test
  fun aPlayerAtAnotherHdrModeIsReleasedAndRebuiltAndAFreshOneTakesAnyMode() =
    contractTest { scope ->
      val rig = Rig(scope)
      rig.host.start { }
      val standing = rig.host.player

      val first =
        rig.onThread { rig.host.playerFor(false, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
      first shouldBeSameInstanceAs standing

      val kept =
        rig.onThread { rig.host.playerFor(false, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
      kept shouldBeSameInstanceAs standing

      val rebuilt = rig.onThread { rig.host.playerFor(false, Composition.HDR_MODE_KEEP_HDR) }
      rig.released.single() shouldBeSameInstanceAs standing
      rig.host.player shouldBeSameInstanceAs rebuilt
      rig.onThread { rig.host.release() }
    }

  @Test
  fun releasingLetsThePlayerGoAndStopsItsThread() =
    contractTest { scope ->
      val rig = Rig(scope)
      rig.host.start { }
      val standing = rig.host.player

      rig.onThread { rig.host.release() }

      rig.released.single() shouldBeSameInstanceAs standing
      rig.host.player shouldBe null
      awaitContract("the player's thread to stop") { !rig.thread.isAlive }
    }

  /**
   * A host over a fresh thread, with collaborators that only record what reaches them.
   */
  private class Rig(
    scope: CoroutineScope,
  ) {
    val thread = HandlerThread("filmstrip-host-test").apply { start() }
    private val handler = Handler(thread.looper)
    private val guard = WrapGuard()
    private val loads = Media3GraphLoads(scope)
    val built = mutableListOf<CompositionPlayer>()
    val released = mutableListOf<CompositionPlayer>()

    private val status =
      Media3StatusTracker(
        shutter = PreviewShutter(scope),
        hasPreview = { false },
        wrapsAtEnd = { false },
        position = { Duration.ZERO },
        duration = { Duration.ZERO },
        stopPlayer = { },
        setStatus = { },
        setStalled = { },
        setPlayWhenReady = { },
        reportExternalPlayWhenReady = { },
        emitEvent = { },
      )

    val host: Media3PlayerHost =
      Media3PlayerHost(
        context = contractContext(),
        scope = scope,
        thread = thread,
        loads = loads,
        status = status,
        seeks =
          Media3SeekCoordinator(
            scope = scope,
            handler = handler,
            player = { host.player },
            resolved = { null },
            guard = guard,
            platformPositionMs = { 0L },
            loopWrap = { null },
            onIssued = { },
          ),
        loops =
          Media3LoopRangeController(
            loads = loads,
            handler = handler,
            player = { host.player },
            resolved = { null },
            guard = guard,
            playhead = { Duration.ZERO },
            recordPosition = { },
            seekTo = { },
            seekToWrap = { },
            pause = { },
            onLooped = { _, _ -> },
          ),
        surfaces =
          Media3SurfaceManager(
            scope = scope,
            handler = handler,
            player = { host.player },
            rendered = { null },
            graphUp = { false },
            onDetached = { },
          ),
        onBuilt = { built += it },
        onReleased = { released += it },
      )

    /**
     * Runs [block] on the player's thread and waits for its answer.
     */
    fun <T> onThread(block: () -> T): T {
      var outcome: Result<T>? = null
      val done = CountDownLatch(1)
      handler.post {
        outcome = runCatching(block)
        done.countDown()
      }
      withClue("the player's thread never ran the block") { done.await(WAIT_SECONDS, TimeUnit.SECONDS).shouldBeTrue() }
      return checkNotNull(outcome).getOrThrow()
    }
  }

  private companion object {
    const val WAIT_SECONDS = 10L
  }
}
