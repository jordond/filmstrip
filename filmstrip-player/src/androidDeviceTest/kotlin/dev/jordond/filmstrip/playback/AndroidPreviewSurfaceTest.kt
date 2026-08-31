package dev.jordond.filmstrip.playback

import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.playback.internal.engineFor
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The seam a Compose surface hands its `SurfaceView` across, against a real window.
 *
 * `SurfaceView` is the only output `CompositionPlayer` takes, and it only produces a surface once
 * it is attached to a window, so these run against [SurfaceHostActivity] rather than a detached
 * view.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidPreviewSurfaceTest {
  private val context = contractContext()

  /**
   * Why the surface posts through the engine instead of calling the player itself.
   *
   * `CompositionPlayer` confines its command set to the looper it was built on, and the engine
   * builds it on a thread of its own. A surface that called the player from the composition would
   * throw on the first thing it asked.
   */
  @Test
  fun thePlatformPlayerRefusesEveryThreadButTheEnginesOwn() =
    runTest(timeout = TIMEOUT) {
      realTime {
        val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
        try {
          val native = player.nativePlayer.shouldBeInstanceOf<Player>()

          assertFailsWith<IllegalStateException> { native.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE) }
          assertFailsWith<IllegalStateException> { native.setVideoSurfaceView(null) }
        } finally {
          player.close()
        }
      }
    }

  @Test
  fun attachingTheSurfaceRendersAFirstFrameAndDetachingLeavesThePlayerReleasable() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            val handle = player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("a first frame to reach the surface") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }

            // The engine keeps its own record of which view it is drawing into, so cancelling twice
            // has to be the same as cancelling once.
            handle.cancel()
            handle.cancel()
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * One first frame per loaded composition, rather than one per player.
   *
   * A structural change rebuilds the graph, which is a new set of pixels arriving at a surface that
   * is already showing something, and the shutter over it has to close again.
   */
  @Test
  fun rebuildingTheGraphReportsAnotherFirstFrame() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("the first composition to render") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }

            // A shorter trim, which changes the duration and so rebuilds rather than updating
            // parameters in place.
            val trimmed =
              EditComposition(listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, SHORTER))))))
            val reloaded =
              withTimeoutOrNull(BUDGET) { player.setComposition(trimmed, playWhenReady = true) }
                ?: fail("The rebuild never settled within $BUDGET.")
            reloaded.shouldBeInstanceOf<SetCompositionResult.Success>()

            awaitOrFail("the rebuilt composition to render") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 2
            }
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * A surface let go of and taken up again has to render, and to say so.
   *
   * What the system does to a preview it puts in the background: the transport stops and the
   * surface goes away, then both come back. A host holds its shutter closed until the engine says
   * a frame reached the surface, so a signal that only ever fires once leaves the preview blank on
   * the way back with nothing wrong underneath it.
   */
  @Test
  fun aSurfaceTakenUpAgainRendersAndSaysSo() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            val handle = player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("the first frame to reach the surface") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }

            player.pause()
            handle.cancel()

            player.attachPreviewSurface(activity.surfaceView)
            player.play()
            awaitOrFail("the surface taken up again to render") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 2
            }
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * The buffer the graph draws into is fixed to the frame it renders, before a graph is up.
   *
   * media3 reads the buffer size off the holder and drops it while `compositionPlayerInternal` is
   * null, which lasts until a composition set on the player is prepared. Fixing the buffer as the
   * graph is built lands squarely in that window, so the output has to be handed back for the size
   * to take.
   */
  @Test
  fun aBufferFixedBeforeAGraphIsUpIsHandedToThePlayerAgain() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")
          activity.resizeSurface(WIDE.width, WIDE.height)
          awaitOrFail("the view to take its first shape") { activity.surfaceView.width == WIDE.width }

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            val engine = assertNotNull(engineFor(player.nativePlayer as CompositionPlayer), "no engine")
            player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("the first frame to reach the surface") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }

            // One for the attachment, and one for the buffer the graph was built against. Pinned
            // to that exactly, so a surface handed over on every resize rather than only before a
            // graph is up fails here as well as below.
            awaitOrFail("the fixed buffer to reach the player") {
              engine.surfaceApplications >= APPLIED_BEFORE_A_GRAPH
            }
            delay(SETTLE)
            engine.surfaceApplications shouldBe APPLIED_BEFORE_A_GRAPH
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * A view that moves under a graph leaves the buffer, and the player, alone.
   *
   * The buffer holds the rendered frame rather than the view, so a fold, a window resize and the
   * moment a swap is revealed all scale a frame that is already correct instead of reallocating a
   * buffer that comes up empty.
   */
  @Test
  fun aViewResizedUnderAGraphLeavesTheBufferAlone() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")
          activity.resizeSurface(WIDE.width, WIDE.height)
          awaitOrFail("the view to take its first shape") { activity.surfaceView.width == WIDE.width }

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            val engine = assertNotNull(engineFor(player.nativePlayer as CompositionPlayer), "no engine")
            player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("the first frame to reach the surface") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }
            player.pause()

            val rendered = player.previewInfo.value.outputSize
            awaitOrFail("the buffer to take the rendered frame") {
              activity.surfaceView.holder.surfaceFrame
                .width() == rendered.width
            }
            activity.surfaceView.holder.surfaceFrame
              .height() shouldBe rendered.height
            val applied = engine.surfaceApplications
            val redrawn = engine.surfaceRedraws

            activity.resizeSurface(TALL.width, TALL.height)
            awaitOrFail("the view to take its second shape") { activity.surfaceView.width == TALL.width }
            delay(SETTLE)

            activity.surfaceView.holder.surfaceFrame
              .width() shouldBe rendered.width
            activity.surfaceView.holder.surfaceFrame
              .height() shouldBe rendered.height
            engine.surfaceApplications shouldBe applied
            engine.surfaceRedraws shouldBe redrawn
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * A surface that does change shape under a live graph is redrawn, not handed over again.
   *
   * media3 reads a new buffer size off the holder itself once it has a graph, so all that is
   * missing is a frame drawn at the new shape. Clearing and setting the output there would cost a
   * rebuild for pixels the player already has.
   */
  @Test
  fun aSurfaceChangedUnderALiveGraphIsRedrawnRatherThanHandedOverAgain() =
    runTest(timeout = TIMEOUT) {
      realTime {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = assertNotNull(host, "the host activity never started")
          assertTrue(activity.awaitSurface(), "the host window produced no surface")
          activity.resizeSurface(WIDE.width, WIDE.height)
          awaitOrFail("the view to take its first shape") { activity.surfaceView.width == WIDE.width }

          val player = Filmstrip(context) { playerBackend() }.preview(androidFixtureComposition())
          val events = mutableListOf<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }

          try {
            val engine = assertNotNull(engineFor(player.nativePlayer as CompositionPlayer), "no engine")
            player.attachPreviewSurface(activity.surfaceView)
            player.awaitReady()
            player.play()
            awaitOrFail("the first frame to reach the surface") {
              events.count { it is PlaybackEvent.FirstFrameRendered } == 1
            }
            player.pause()

            val rendered = player.previewInfo.value.outputSize
            awaitOrFail("the buffer to take the rendered frame") {
              activity.surfaceView.holder.surfaceFrame
                .width() == rendered.width
            }
            delay(SETTLE)
            val applied = engine.surfaceApplications
            val redrawn = engine.surfaceRedraws

            activity.resizeBuffer(rendered.width / HALVED, rendered.height / HALVED)

            awaitOrFail("the resized surface to be drawn again") { engine.surfaceRedraws == redrawn + 1 }
            engine.surfaceApplications shouldBe applied
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  /**
   * Runs [body] off the test scheduler, so a wait spends real time rather than virtual time.
   *
   * The engine settles on the platform's clock, which no test scheduler can advance.
   */
  private suspend fun realTime(body: suspend () -> Unit) = withContext(Dispatchers.Default) { body() }

  private suspend fun VideoPlayer.awaitReady() =
    awaitOrFail("the preview to become ready, it sat on ${state.value.status}") {
      state.value.status == PlaybackStatus.Ready
    }

  private suspend fun awaitOrFail(
    description: String,
    condition: () -> Boolean,
  ) {
    val met =
      withTimeoutOrNull(BUDGET) {
        while (!condition()) delay(POLL)
        true
      }
    if (met != true) fail("Timed out after $BUDGET waiting for $description.")
  }

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val BUDGET: Duration = 30.seconds
    val POLL: Duration = 50.milliseconds

    val SHORTER: Duration = 900.milliseconds
    val SETTLE: Duration = 500.milliseconds

    // One for the attachment, one for the buffer fixed under a graph that was not up yet.
    const val APPLIED_BEFORE_A_GRAPH = 2
    const val HALVED = 2

    // A 16:9 view and the 9:16 one a quarter turn of the same edit asks for.
    val WIDE = Size(960, 540)
    val TALL = Size(540, 960)
  }
}
