package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import kotlin.test.Test

/**
 * A layer handed across the seam reporting the first frame it shows.
 *
 * macOS only. An iOS layer that is not in a window never becomes ready for display, and a test process has no window
 * to put one in.
 */
class AvPreviewSurfaceMacosMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a layer attached to an AVFoundation preview reports the first frame it shows`() =
    contractTest { scope ->
      val player = Filmstrip { playerBackend() }.preview(appleFixtureComposition())
      val events = mutableListOf<PlaybackEvent>()
      val collector = scope.launch { player.events.collect { events += it } }
      try {
        val native = player.nativePlayer.shouldBeInstanceOf<AVPlayer>()
        val layer = AVPlayerLayer.playerLayerWithPlayer(native)

        val handle = player.attachPreviewLayer(layer)
        awaitContract("the layer to show the first frame") { PlaybackEvent.FirstFrameRendered in events }

        handle.cancel()
        handle.cancel()
        events.count { it == PlaybackEvent.FirstFrameRendered } shouldBe 1
      } finally {
        collector.cancel()
        player.close()
      }
    }
}
