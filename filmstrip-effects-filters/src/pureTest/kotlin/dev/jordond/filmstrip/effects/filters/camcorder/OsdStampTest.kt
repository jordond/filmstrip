package dev.jordond.filmstrip.effects.filters.camcorder

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days

/**
 * What an [OsdElement.Stamp] reads and how it draws.
 */
class OsdStampTest {
  @Test
  fun `the stamp reads a two digit year behind an apostrophe and nothing padded`() {
    stampText(RecordedAt(1997, 6, 14)) shouldBe "'97 6 14"
    stampText(RecordedAt(2003, 11, 2)) shouldBe "'03 11 2"
    stampText(RecordedAt(2000, 1, 1)) shouldBe "'00 1 1"
  }

  @Test
  fun `the stamp is one orange run with no edge`() {
    val run =
      STAMP_OSD
        .stateAt(ZERO)
        .lines
        .single()
        .runs
        .single()

    run shouldBe OsdRun("'97 6 14", OsdParams.STAMP_COLOUR, edge = false)
  }

  @Test
  fun `the stamp follows the composition time across midnight`() {
    val osd = CamcorderOsd(Camcorder(listOf(OsdElement.Stamp()), RecordedAt(1999, 12, 31, 23, 59, 59)), 30f)

    osd
      .stateAt(1.days)
      .lines
      .single()
      .runs
      .single()
      .text shouldBe "'00 1 1"
  }

  @Test
  fun `the stamp draws only orange dots and lands in the bottom right`() {
    val raster = STAMP_OSD.rasterAt(ZERO, WIDTH, HEIGHT)
    val line = STAMP_OSD.stateAt(ZERO).lines.single()
    val layout = osdLayout(WIDTH, HEIGHT, Camcorder.DEFAULT_TEXT_SIZE)
    val origin = layout.originOf(OsdCorner.BottomRight, 0, 1, line)
    val colours = (0 until HEIGHT).flatMap { y -> (0 until WIDTH).map { x -> raster.argbAt(x, y) } }.toSet()

    colours shouldBe setOf(HIDDEN, OsdParams.STAMP_COLOUR)
    colours shouldNotContain OsdParams.EDGE_COLOUR
    (origin.x + lineDots(line) * origin.dot) shouldBe (layout.right plusOrMinus EPSILON)
    (origin.y + OsdFont.HEIGHT * origin.dot) shouldBe (layout.bottom plusOrMinus EPSILON)
    raster.at(origin, 0, 0) shouldBe OsdParams.STAMP_COLOUR
    raster.at(origin, -1, 0) shouldBe HIDDEN
    raster.at(origin, 0, 2) shouldBe HIDDEN
  }

  @Test
  fun `a stamp beside a label keeps the label's edge and has none of its own`() {
    val osd =
      CamcorderOsd(
        Camcorder(listOf(OsdElement.Label("O", OsdCorner.TopLeft), OsdElement.Stamp(OsdCorner.BottomRight))),
        30f,
      )
    val raster = osd.rasterAt(ZERO, WIDTH, HEIGHT)
    val state = osd.stateAt(ZERO)
    val layout = osdLayout(WIDTH, HEIGHT, Camcorder.DEFAULT_TEXT_SIZE)
    val label = layout.originOf(OsdCorner.TopLeft, 0, 1, state.lines[0])
    val stamp = layout.originOf(OsdCorner.BottomRight, 0, 1, state.lines[1])

    raster.at(label, -1, 3) shouldBe OsdParams.EDGE_COLOUR
    raster.at(stamp, -1, 0) shouldBe HIDDEN
    raster.at(stamp, 0, 0) shouldBe OsdParams.STAMP_COLOUR
  }

  @Test
  fun `the camcorder builder adds a stamp`() {
    CamcorderBuilder().apply { stamp(OsdCorner.TopRight) }.build().elements shouldBe
      listOf(OsdElement.Stamp(OsdCorner.TopRight))
  }

  // The pixel under the centre of a font dot.
  private fun OsdRaster.at(
    origin: DotOrigin,
    gx: Int,
    gy: Int,
  ): Int = argbAt((origin.x + (gx + 0.5) * origin.dot).toInt(), (origin.y + (gy + 0.5) * origin.dot).toInt())

  private companion object {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val EPSILON = 1e-9
    val STAMP_OSD = CamcorderOsd(Camcorder(listOf(OsdElement.Stamp())), 30f)
  }
}
