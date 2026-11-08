package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Whether a contained clip with no geometry of its own leaves bars, which the planner and every backend that skips a
 * fill read off [ClipPlacement.leavesBars].
 */
class LeavesBarsTest {
  @Test
  fun `a clip with the output aspect fills it`() {
    leavesBars(Size(1280, 720)) shouldBe false
  }

  @Test
  fun `four by three in sixteen by nine leaves bars`() {
    leavesBars(Size(1440, 1080)) shouldBe true
  }

  @Test
  fun `a portrait clip in a landscape frame leaves bars`() {
    leavesBars(Size(1080, 1920)) shouldBe true
  }

  @Test
  fun `a bar just under one pixel is no bar`() {
    // 19200 * 1080 / 10805 is 1919.11 wide, so the bar comes to 0.89 of a pixel.
    leavesBars(Size(19200, 10805)) shouldBe false
  }

  @Test
  fun `a bar just over one pixel is a bar`() {
    // 19200 * 1080 / 10806 is 1918.97 wide, so the bar comes to 1.03 pixels.
    leavesBars(Size(19200, 10806)) shouldBe true
  }

  private fun leavesBars(clip: Size): Boolean = placementOf(clip, emptyList(), HD).leavesBars

  private companion object {
    val HD = Size(1920, 1080)
  }
}
