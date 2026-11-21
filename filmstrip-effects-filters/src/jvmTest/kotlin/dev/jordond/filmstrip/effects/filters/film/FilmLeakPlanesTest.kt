package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * [LeakPlanes] holds [FilmLeak.leakAt] at the centre of each sample of the raster, a band a channel.
 */
class FilmLeakPlanesTest {
  @Test
  fun `a sample covers eight pixels from 360 on the shorter side and fewer below it`() {
    leakStep(Size(3840, 2160)) shouldBe 8
    leakStep(Size(640, 360)) shouldBe 8
    leakStep(Size(360, 640)) shouldBe 8
    leakStep(Size(480, 270)) shouldBe 6
    leakStep(Size(160, 90)) shouldBe 2
    leakStep(Size(160, 89)) shouldBe 1
    leakStep(Size(2, 2)) shouldBe 1
  }

  @Test
  fun `the raster is the frame over its step a side rounded up with a sample past each edge`() {
    leakRaster(Size(1920, 1080)) shouldBe Size(242, 137)
    leakRaster(Size(1366, 768)) shouldBe Size(173, 98)
    leakRaster(Size(480, 270)) shouldBe Size(82, 47)
    leakRaster(Size(5, 3)) shouldBe Size(7, 5)
  }

  @Test
  fun `the first sample sits half a step off the frame and the next half a step in`() {
    leakSampleAt(0, 640, 8) shouldBe -4.0 / 640
    leakSampleAt(1, 640, 8) shouldBe 4.0 / 640
    leakSampleAt(81, 640, 8) shouldBe 644.0 / 640
    leakSampleAt(0, 90, 2) shouldBe -1.0 / 90
    leakSampleAt(1, 2, 1) shouldBe 0.5 / 2
  }

  @Test
  fun `every sample is the signal's light at its centre`() {
    val frame = Size(640, 360)
    val raster = leakRaster(frame)
    val band = raster.width * raster.height
    for (tick in listOf(0, 30, 75, 603)) {
      val plane = LeakPlanes(frame, SEED, LEAK).at(tick)
      plane.size shouldBe band * 3
      var lit = 0
      for (y in 0 until raster.height) {
        for (x in 0 until raster.width) {
          val light =
            FilmLeak.leakAt(leakSampleAt(x, 640, 8), leakSampleAt(y, 360, 8), 640.0 / 360.0, tick, SEED, LEAK)
          for (channel in 0 until 3) {
            val code = plane[channel * band + y * raster.width + x].toInt() and 0xFF
            if (code > 0) lit++
            withClue("tick $tick sample $x, $y channel $channel") {
              code shouldBe (0.5 + 255 * light[channel]).toInt()
            }
          }
        }
      }
      withClue("the leak has to be lit at tick $tick for this to tell") { lit shouldBeGreaterThan band / 8 }
    }
  }

  private companion object {
    const val SEED = 7
    const val LEAK = 0.5
  }
}
