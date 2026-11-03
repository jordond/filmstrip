package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The decisions the media3 engine makes where media3 says less than filmstrip's contract does, taken apart from the
 * player they are made for.
 *
 * The decision is the whole of the behaviour, so none of it needs a decoder to check.
 */
class Media3PlayerLimitsTest {
  @Test
  fun `a relaxed seek is clamped up to exact, which is all media3 offers`() {
    clampedAccuracy(SeekAccuracy.Nearest) shouldBe SeekAccuracy.Exact
    clampedAccuracy(SeekAccuracy.Exact) shouldBe SeekAccuracy.Exact
  }

  /**
   * Which half of the resize path a change of shape takes.
   *
   * media3 applies the size it reads off the holder itself, and drops it only while it has no graph
   * to hand it to. Handing the output back tears the graph's surface down and blocks the player's
   * thread until it is gone, so it is spent on the window that needs it and nowhere else.
   */
  @Test
  fun `a surface resized before a graph is up is handed back, and one resized after is redrawn`() {
    surfaceResizeAction(hasGraph = false) shouldBe SurfaceResize.Reapply
    surfaceResizeAction(hasGraph = true) shouldBe SurfaceResize.Redraw
  }

  /**
   * The buffer follows the frame the graph renders, and nothing else.
   *
   * A buffer that tracked the view would reallocate as the view moved, and the reallocation a swap
   * causes would land at the moment the swap is revealed, which is the one place it is visible.
   */
  @Test
  fun `the buffer is fixed to a rendered frame that changed, and left alone otherwise`() {
    previewBufferChange(current = null, rendered = Size(1920, 1080)) shouldBe Size(1920, 1080)
    previewBufferChange(current = Size(1920, 1080), rendered = Size(1080, 1920)) shouldBe Size(1080, 1920)
    previewBufferChange(current = Size(1920, 1080), rendered = Size(1920, 1080)) shouldBe null
    previewBufferChange(current = Size(1920, 1080), rendered = Size(0, 0)) shouldBe null
  }

  @Test
  fun `an equal edit costs the platform nothing at all`() {
    loadCostFor(CompositionDiff.Equal, hasGraph = true) { false } shouldBe LoadCost.Nothing
  }

  @Test
  fun `a parameter change the standing graph took costs a redraw, and one it refused costs a rebuild`() {
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = true) { true } shouldBe LoadCost.Parameters
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = true) { false } shouldBe LoadCost.Rebuild
  }

  @Test
  fun `a structural change is never offered to the standing graph`() {
    var offered = false
    loadCostFor(CompositionDiff.Structural, hasGraph = true) {
      offered = true
      true
    } shouldBe LoadCost.Rebuild
    offered shouldBe false
  }

  @Test
  fun `the first edit rebuilds whatever the diff says, there being no graph to reuse`() {
    loadCostFor(CompositionDiff.Equal, hasGraph = false) { true } shouldBe LoadCost.Rebuild
    loadCostFor(CompositionDiff.ParametersOnly, hasGraph = false) { true } shouldBe LoadCost.Rebuild
  }
}
