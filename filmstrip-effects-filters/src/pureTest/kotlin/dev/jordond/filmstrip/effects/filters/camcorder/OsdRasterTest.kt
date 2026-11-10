package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where a [Camcorder] display lands in a frame and what colour its dots draw.
 */
class OsdRasterTest {
  @Test
  fun `the layout lands the same fractions at 1080 and 360`() {
    val large = osdLayout(1920, 1080, Camcorder.DEFAULT_TEXT_SIZE)
    val small = osdLayout(640, 360, Camcorder.DEFAULT_TEXT_SIZE)

    (small.dot / 360) shouldBe (large.dot / 1080 plusOrMinus EPSILON)
    (small.left / 640) shouldBe (large.left / 1920 plusOrMinus EPSILON)
    (small.right / 640) shouldBe (large.right / 1920 plusOrMinus EPSILON)
    (small.top / 360) shouldBe (large.top / 1080 plusOrMinus EPSILON)
    (small.bottom / 360) shouldBe (large.bottom / 1080 plusOrMinus EPSILON)
    (large.dot * OsdFont.HEIGHT / 1080) shouldBe (Camcorder.DEFAULT_TEXT_SIZE.toDouble() plusOrMinus EPSILON)
  }

  @Test
  fun `a glyph draws its lit dots in white with a black edge and a clear hole`() {
    val raster = labelRaster("O", OsdCorner.TopLeft, 1920, 1080)
    val origin = labelOrigin("O", OsdCorner.TopLeft, 1920, 1080)

    raster.at(origin, 0, 0) shouldBe OsdParams.TEXT_COLOUR
    raster.at(origin, -1, 3) shouldBe OsdParams.EDGE_COLOUR
    raster.at(origin, 1, 1) shouldBe OsdParams.EDGE_COLOUR
    raster.at(origin, 2, 3) shouldBe HIDDEN
    raster.at(origin, 7, 3) shouldBe HIDDEN
  }

  @Test
  fun `a right corner aligns the line to the right inset`() {
    val origin = labelOrigin("SP", OsdCorner.BottomRight, 1920, 1080)
    val layout = osdLayout(1920, 1080, Camcorder.DEFAULT_TEXT_SIZE)
    val raster = labelRaster("SP", OsdCorner.BottomRight, 1920, 1080)

    (origin.x + 11 * origin.dot) shouldBe (layout.right plusOrMinus EPSILON)
    (origin.y + OsdFont.HEIGHT * origin.dot) shouldBe (layout.bottom plusOrMinus EPSILON)
    raster.at(origin, 6, 6) shouldBe OsdParams.TEXT_COLOUR
  }

  @Test
  fun `a smaller frame is the larger one scaled down dot for dot`() {
    val large = labelRaster("8", OsdCorner.TopLeft, 1920, 1080)
    val small = labelRaster("8", OsdCorner.TopLeft, 640, 360)
    val largeOrigin = labelOrigin("8", OsdCorner.TopLeft, 1920, 1080)
    val smallOrigin = labelOrigin("8", OsdCorner.TopLeft, 640, 360)

    for (gy in -1..OsdFont.HEIGHT) {
      for (gx in -1..5) small.at(smallOrigin, gx, gy) shouldBe large.at(largeOrigin, gx, gy)
    }
  }

  @Test
  fun `the record dot draws red on the blink and nothing off it`() {
    val spec = Camcorder(listOf(OsdElement.Rec()))
    val osd = CamcorderOsd(spec, 30f)
    val line = osd.stateAt(250.milliseconds).lines.single()
    val origin = osdLayout(1280, 720, spec.textSize).originOf(OsdCorner.TopLeft, 0, 1, line)

    osd.rasterAt(250.milliseconds, 1280, 720).at(origin, 2, 3) shouldBe OsdParams.RECORD_COLOUR
    osd.rasterAt(750.milliseconds, 1280, 720).at(origin, 2, 3) shouldBe HIDDEN
    osd.rasterAt(750.milliseconds, 1280, 720).at(origin, -1, 3) shouldBe HIDDEN
  }

  @Test
  fun `every pixel of every element is fully opaque or fully clear`() {
    val osd = CamcorderOsd(EVERY_ELEMENT, 30f)

    listOf(250.milliseconds, 1_517.milliseconds, 2_750.milliseconds).forEach { time ->
      val raster = osd.rasterAt(time, 853, 480)
      var lit = 0
      for (y in 0 until raster.height) {
        for (x in 0 until raster.width) {
          val argb = raster.argbAt(x, y)
          if (argb != HIDDEN) {
            lit++
            (argb ushr 24) shouldBe OPAQUE
          }
        }
      }
      (lit > 0) shouldBe true
    }
  }

  @Test
  fun `redrawing only the changed region of each state gives the raster of that state`() {
    val counter = OsdElement.Counter(start = 9.hours + 59.minutes + 59.seconds, withFrames = true, OsdCorner.TopLeft)
    val spec =
      Camcorder(listOf(OsdElement.Rec(OsdCorner.TopRight), counter, OsdElement.Label("OVER", OsdCorner.TopRight)))
    val osd = CamcorderOsd(spec, 30f)
    val changes = osd.changesWithin(ZERO, 2.seconds)
    val bytes = osd.rasterAt(ZERO, 320, 240).rgbaBytes()

    changes.zipWithNext().forEach { (before, after) ->
      osd.changedRegion(before.state, after.state, 320, 240)?.let { osd.redrawInto(bytes, after.state, 320, 240, it) }
      bytes.contentEquals(osd.rasterAt(after.at, 320, 240).rgbaBytes()) shouldBe true
    }
    (changes.size > 60) shouldBe true
  }

  @Test
  fun `every tuned colour is fully opaque`() {
    listOf(OsdParams.TEXT_COLOUR, OsdParams.EDGE_COLOUR, OsdParams.RECORD_COLOUR).forEach { colour ->
      (colour ushr 24) shouldBe OPAQUE
    }
  }

  private fun labelRaster(
    text: String,
    corner: OsdCorner,
    width: Int,
    height: Int,
  ): OsdRaster = CamcorderOsd(Camcorder(listOf(OsdElement.Label(text, corner))), 30f).rasterAt(ZERO, width, height)

  private fun labelOrigin(
    text: String,
    corner: OsdCorner,
    width: Int,
    height: Int,
  ): DotOrigin {
    val osd = CamcorderOsd(Camcorder(listOf(OsdElement.Label(text, corner))), 30f)
    val line = osd.stateAt(ZERO).lines.single()
    return osdLayout(width, height, Camcorder.DEFAULT_TEXT_SIZE).originOf(corner, 0, 1, line)
  }

  // The pixel under the centre of a font dot.
  private fun OsdRaster.at(
    origin: DotOrigin,
    gx: Int,
    gy: Int,
  ): Int = argbAt((origin.x + (gx + 0.5) * origin.dot).toInt(), (origin.y + (gy + 0.5) * origin.dot).toInt())

  private companion object {
    const val EPSILON = 1e-9
    const val OPAQUE = 0xFF
    val EVERY_ELEMENT =
      Camcorder(
        listOf(
          OsdElement.Rec(),
          OsdElement.Transport(TransportMode.Play),
          OsdElement.Date(),
          OsdElement.Clock(),
          OsdElement.Counter(withFrames = true),
          OsdElement.Battery(0.25f),
          OsdElement.Tape(TapeSpeed.SP),
          OsdElement.Label("CAM 1", OsdCorner.BottomRight),
        ),
      )
    val ZERO = kotlin.time.Duration.ZERO
  }
}
