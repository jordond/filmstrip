package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVPlayerLayer
import platform.Foundation.NSOSStatusErrorDomain
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow
import kotlin.test.Test

/**
 * A preview item shown through a layer in a window, which is how an app shows one and the only way some iOS simulator
 * runtimes fail it.
 *
 * The test settles once the item is ready and the layer has a frame to display, or once the item fails. The item's
 * status alone isn't enough, since it can read ready for a moment before that failure.
 *
 * A simulator may either get that frame or report the failure the limits page names, so a runtime that fails it today
 * can start working without this going red. Any other failure is red, and so is that one anywhere but a simulator.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPreviewLayerTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a preview in a window gets a frame to display or reports the named simulator failure once`() =
    contractTest { scope ->
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        val layer = withContext(Dispatchers.Main) { AVPlayerLayer.playerLayerWithPlayer(engine.platformPlayer) }
        val window = withContext(Dispatchers.Main) { shownWindow(engine, layer) }
        try {
          engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the preview to settle") {
            val status = recorder.lastState.status
            status is PlaybackStatus.Error || (status == PlaybackStatus.Ready && layer.readyForDisplay)
          }
          engine.drainCallbacks()

          val failures = recorder.events.filterIsInstance<PlaybackEvent.Failed>()
          when (val status = recorder.lastState.status) {
            is PlaybackStatus.Error -> {
              runsOnSimulator() shouldBe true
              val error = status.error.shouldBeInstanceOf<PlaybackError.Underlying>()
              error.platformCode shouldBe ITEM_FAILED
              error.message shouldEndWith "($NSOSStatusErrorDomain $RENDER_PIPELINE_FAILED)"
              failures shouldHaveSize 1
            }
            else -> {
              status shouldBe PlaybackStatus.Ready
              failures shouldHaveSize 0
            }
          }
        } finally {
          withContext(Dispatchers.Main) { window.hidden = true }
        }
      }
    }

  private fun shownWindow(
    engine: AvPlayerEngine,
    layer: AVPlayerLayer,
  ): UIWindow {
    val window = UIWindow(frame = UIScreen.mainScreen.bounds)
    layer.frame = window.bounds
    window.layer.addSublayer(layer)
    window.hidden = false
    engine.attachSurfaceLayer(layer)
    return window
  }

  private companion object {
    const val ITEM_FAILED = -11800
    const val RENDER_PIPELINE_FAILED = -12784
  }
}
