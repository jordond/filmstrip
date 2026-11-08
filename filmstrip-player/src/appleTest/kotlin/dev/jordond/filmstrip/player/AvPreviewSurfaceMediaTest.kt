package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayerLayer
import kotlin.test.Test

/**
 * The seam a surface hands its layer across, against a player the preview factory built.
 */
class AvPreviewSurfaceMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a preview no AVFoundation engine backs is left alone`() =
    contractTest { scope ->
      val player = Filmstrip { }.preview(appleFixtureComposition())
      val events = mutableListOf<PlaybackEvent>()
      val collector = scope.launch { player.events.collect { events += it } }
      try {
        player.nativePlayer.shouldBeNull()

        player.attachPreviewLayer(AVPlayerLayer()).cancel()
        settle()

        events.filterIsInstance<PlaybackEvent.FirstFrameRendered>().shouldBeEmpty()
      } finally {
        collector.cancel()
        player.close()
      }
    }
}
