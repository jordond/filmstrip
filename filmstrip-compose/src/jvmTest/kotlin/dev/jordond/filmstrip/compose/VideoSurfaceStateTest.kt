package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * When the surface state lets a new output size through.
 *
 * The engine reports the frame it has been asked to deliver as soon as it starts building the graph
 * for it, which is a few hundred milliseconds before anything draws at that size. A host sizing its
 * preview from the eager figure moves the box first and the picture second, so the size is held here
 * until the frame that fills it arrives.
 */
class VideoSurfaceStateTest {
  @Test
  fun `the first size is taken at once, since nothing is on screen to protect`() {
    val state = VideoSurfaceState()

    state.onOutputSize(WIDE)

    state.outputSize shouldBe WIDE
  }

  @Test
  fun `a size reported for a composition that is still loading waits for its first frame`() {
    val state = presenting(WIDE)

    state.onStatus(PlaybackStatus.Preparing)
    state.onOutputSize(TALL)

    state.outputSize shouldBe WIDE
    state.coverSurface shouldBe true
  }

  @Test
  fun `the size and the cover move together, so the box and the picture change on one frame`() {
    val state = presenting(WIDE)
    state.onStatus(PlaybackStatus.Preparing)
    state.onOutputSize(TALL)

    state.onEvent(PlaybackEvent.FirstFrameRendered)

    state.outputSize shouldBe TALL
    state.coverSurface shouldBe false
  }

  @Test
  fun `a size that arrives with the surface uncovered is taken at once`() {
    val state = presenting(WIDE)

    // What a parameter-only change looks like: a live graph keeps drawing and no shutter closes.
    state.onOutputSize(TALL)

    state.outputSize shouldBe TALL
  }

  @Test
  fun `a load that failed stops covering rather than waiting on a frame that is not coming`() {
    val state = presenting(WIDE)
    state.onStatus(PlaybackStatus.Preparing)
    state.onOutputSize(TALL)

    state.onStatus(PlaybackStatus.Error(PlaybackError.SourceUnreadable("nothing to read")))

    state.coverSurface shouldBe false
    state.outputSize shouldBe TALL
  }

  @Test
  fun `a second swap replaces the size the first one was waiting to show`() {
    val state = presenting(WIDE)
    state.onStatus(PlaybackStatus.Preparing)
    state.onOutputSize(TALL)
    state.onOutputSize(SQUARE)

    state.onEvent(PlaybackEvent.FirstFrameRendered)

    state.outputSize shouldBe SQUARE
  }

  /**
   * A state showing [size], the way it stands once a first composition has drawn.
   */
  private fun presenting(size: Size): VideoSurfaceState =
    VideoSurfaceState().apply {
      onOutputSize(size)
      onEvent(PlaybackEvent.FirstFrameRendered)
    }

  private companion object {
    val WIDE = Size(1920, 1080)
    val TALL = Size(1080, 1920)
    val SQUARE = Size(1080, 1080)
  }
}
