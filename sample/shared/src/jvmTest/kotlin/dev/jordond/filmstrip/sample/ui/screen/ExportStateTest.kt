package dev.jordond.filmstrip.sample.ui.screen

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExportStateTest {
  @Test
  fun `no progress lights no cells`() {
    litCells(0f) shouldBe 0
  }

  @Test
  fun `mid run lights the nearest cell`() {
    litCells(0.5f) shouldBe 8
    litCells(0.62f) shouldBe 10
  }

  @Test
  fun `a finished run lights all of them`() {
    litCells(1f) shouldBe EXPORT_CELLS
  }

  @Test
  fun `a figure out of range stays on the bar`() {
    litCells(1.4f) shouldBe EXPORT_CELLS
    litCells(-0.2f) shouldBe 0
    litCells(Float.NaN) shouldBe 0
  }
}
