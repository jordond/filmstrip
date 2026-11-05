package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The one test for whether a contained clip leaves bars, which the planner and every backend that skips a fill read.
 */
class ClipFillsFrameTest {
  @Test
  fun `a clip with the output aspect fills it`() {
    clipFillsFrame(Size(1280, 720), HD) shouldBe true
  }

  @Test
  fun `four by three in sixteen by nine leaves bars`() {
    clipFillsFrame(Size(1440, 1080), HD) shouldBe false
  }

  @Test
  fun `a portrait clip in a landscape frame leaves bars`() {
    clipFillsFrame(Size(1080, 1920), HD) shouldBe false
  }

  @Test
  fun `a bar just under one pixel is no bar`() {
    // 19200 * 1080 / 10805 is 1919.11 wide, so the bar comes to 0.89 of a pixel.
    clipFillsFrame(Size(19200, 10805), HD) shouldBe true
  }

  @Test
  fun `a bar just over one pixel is a bar`() {
    // 19200 * 1080 / 10806 is 1918.97 wide, so the bar comes to 1.03 pixels.
    clipFillsFrame(Size(19200, 10806), HD) shouldBe false
  }

  @Test
  fun `an empty frame fills nothing`() {
    clipFillsFrame(Size(0, 0), HD) shouldBe false
  }

  private companion object {
    val HD = Size(1920, 1080)
  }
}
