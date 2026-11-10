package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import java.awt.image.DataBufferUShort
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test

class GainMapsTest {
  @Test
  fun `the same shape at the same size reads back the map it drew`() {
    GainMaps.of(SHAPE, FRAME, ::ramp) shouldBeSameInstanceAs GainMaps.of(SHAPE, FRAME, ::ramp)
  }

  @Test
  fun `another size or shape draws its own map`() {
    val first = GainMaps.of(SHAPE, FRAME, ::ramp)

    GainMaps.of(SHAPE, Size(FRAME.width + 1, FRAME.height), ::ramp) shouldNotBeSameInstanceAs first
    GainMaps.of("another", FRAME) { x, _ -> 1.0 - x } shouldNotBeSameInstanceAs first
  }

  @Test
  fun `the map holds the gain at each pixel centre part way across`() {
    val image = ImageIO.read(ByteArrayInputStream(GainMaps.of(SHAPE, FRAME, ::ramp).bytes))
    val samples = (image.raster.dataBuffer as DataBufferUShort).data
    val (x, y) = MIDDLE

    val gain = ramp((x + 0.5) / FRAME.width, (y + 0.5) / FRAME.height)
    (samples[y * FRAME.width + x].toInt() and 0xFFFF) shouldBe (gain * GainMaps.FULL).roundToInt()
  }

  private fun ramp(
    x: Double,
    y: Double,
  ): Double = 0.25 + 0.5 * x * y

  private companion object {
    const val SHAPE = "ramp"
    val FRAME = Size(97, 41)
    val MIDDLE = 37 to 20
  }
}
