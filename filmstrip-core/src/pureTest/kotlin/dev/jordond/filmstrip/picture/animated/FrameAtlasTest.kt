package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FrameAtlasTest {
  private val canvas = Size(32, 24)

  @Test
  fun `sixty frames put frame 37 where the grid says`() {
    val grid = atlasGrid(60, canvas, maxSide = null)!!

    grid.columns shouldBe 7
    grid.rows shouldBe 9
    grid.cells.size shouldBe 60
    grid.cells[37] shouldBe PictureCrop(2 * 32, 5 * 24, canvas)
    grid.cells[0] shouldBe PictureCrop(0, 0, canvas)
    grid.cells[59] shouldBe PictureCrop(3 * 32, 8 * 24, canvas)
    grid.size shouldBe Size(224, 216)
  }

  @Test
  fun `a side too small answers null and the side it takes fits`() {
    atlasGrid(60, canvas, maxSide = 224)!!.columns shouldBe 7
    atlasGrid(60, canvas, maxSide = 223).shouldBeNull()
    atlasGrid(60, canvas, maxSide = 0).shouldBeNull()
  }

  @Test
  fun `no cell overlaps another or leaves the atlas`() {
    for (frames in listOf(1, 2, 7, 37, 60, 100)) {
      val grid = atlasGrid(frames, canvas, maxSide = null)!!
      val seen = mutableSetOf<Pair<Int, Int>>()
      for (cell in grid.cells) {
        (cell.left + cell.size.width) shouldBeLessThanOrEqual grid.size.width
        (cell.top + cell.size.height) shouldBeLessThanOrEqual grid.size.height
        seen += cell.left to cell.top
      }
      seen.size shouldBe frames
    }
  }

  @Test
  fun `no other column count has a shorter longer side`() {
    for (size in listOf(Size(32, 24), Size(480, 270), Size(10, 400), Size(400, 10))) {
      for (frames in listOf(1, 5, 37, 60, 100)) {
        val grid = atlasGrid(frames, size, maxSide = null)!!
        val longer = maxOf(grid.size.width, grid.size.height)
        for (columns in 1..frames) {
          val rows = (frames + columns - 1) / columns
          maxOf(columns * size.width, rows * size.height) shouldBeGreaterThanOrEqual longer
        }
      }
    }
  }

  @Test
  fun `a tall canvas lays its frames in one row and a wide one in one column`() {
    atlasGrid(6, Size(10, 400), null)!!.columns shouldBe 6
    atlasGrid(6, Size(400, 10), null)!!.columns shouldBe 1
  }

  @Test
  fun `no frames or no canvas has no grid`() {
    atlasGrid(0, canvas, null).shouldBeNull()
    atlasGrid(3, Size(0, 24), null).shouldBeNull()
  }
}
