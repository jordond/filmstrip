package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
  fun `a surface that changed before a graph is up is handed back, playing or not`() {
    for (playing in listOf(false, true)) {
      surfaceChangeAction(hasGraph = false, SurfaceChange.Shape, playing) shouldBe SurfaceResize.Reapply
      surfaceChangeAction(hasGraph = false, SurfaceChange.Handed, playing) shouldBe SurfaceResize.Reapply
    }
  }

  @Test
  fun `a buffer that changed shape under a graph is redrawn, playing or not`() {
    surfaceChangeAction(hasGraph = true, SurfaceChange.Shape, playing = false) shouldBe SurfaceResize.Redraw
    surfaceChangeAction(hasGraph = true, SurfaceChange.Shape, playing = true) shouldBe SurfaceResize.Redraw
  }

  /**
   * media3 draws the frame queued behind the one on screen onto an output it is handed, so a player that is not
   * playing is seeked back to the slot it holds.
   */
  @Test
  fun `an output handed to a player that is not playing is seeked back to the held slot`() {
    surfaceChangeAction(hasGraph = true, SurfaceChange.Handed, playing = false) shouldBe SurfaceResize.Represent
  }

  @Test
  fun `an output handed to a playing player is only redrawn`() {
    surfaceChangeAction(hasGraph = true, SurfaceChange.Handed, playing = true) shouldBe SurfaceResize.Redraw
  }

  @Test
  fun `a surface lost under a player that is not playing is seeked back so the held slot waits for the next`() {
    surfaceChangeAction(hasGraph = true, SurfaceChange.Lost, playing = false) shouldBe SurfaceResize.Represent
  }

  @Test
  fun `a surface lost while playing or before a graph is up is left alone`() {
    surfaceChangeAction(hasGraph = true, SurfaceChange.Lost, playing = true) shouldBe SurfaceResize.Leave
    surfaceChangeAction(hasGraph = false, SurfaceChange.Lost, playing = false) shouldBe SurfaceResize.Leave
    surfaceChangeAction(hasGraph = false, SurfaceChange.Lost, playing = true) shouldBe SurfaceResize.Leave
  }

  /**
   * media3 skips a seek to the millisecond it rests on, so the way back goes through the one beside it.
   */
  @Test
  fun `the nudge is the millisecond before, or the one after at zero`() {
    seekNudgeMs(0) shouldBe 1
    seekNudgeMs(1) shouldBe 0
    seekNudgeMs(4_171) shouldBe 4_170
  }

  /**
   * A paused player can rest on a millisecond other than the one it reports, and media3 skips by the one it rests on.
   */
  @Test
  fun `a paused seek outside a scrub goes through the nudge unless the player reports resting on its target`() {
    seekGoesThroughNudge(fresh = false, skipped = false, playing = false, scrubbing = false) shouldBe true
    seekGoesThroughNudge(fresh = false, skipped = true, playing = false, scrubbing = false) shouldBe false
  }

  @Test
  fun `a playing seek and a scrub are handed over as they are`() {
    seekGoesThroughNudge(fresh = false, skipped = false, playing = true, scrubbing = false) shouldBe false
    seekGoesThroughNudge(fresh = false, skipped = false, playing = false, scrubbing = true) shouldBe false
  }

  @Test
  fun `a seek that has to be carried out always goes through the nudge`() {
    for (skipped in listOf(false, true)) {
      for (playing in listOf(false, true)) {
        for (scrubbing in listOf(false, true)) {
          seekGoesThroughNudge(fresh = true, skipped, playing, scrubbing) shouldBe true
        }
      }
    }
  }

  @Test
  fun `the nudge is never the position itself and never before zero`() {
    for (positionMs in listOf(0L, 1L, 2L, 33L, 1_000L, 86_400_000L)) {
      val nudge = seekNudgeMs(positionMs)
      nudge shouldNotBe positionMs
      nudge shouldBeGreaterThanOrEqual 0
    }
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
