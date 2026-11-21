package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Runs [DigicamExpressions.noiseBand] through ffmpeg's own `geq` and reads it back against [DigicamSignal.chromaAt].
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class DigicamExpressionMediaTest {
  @Test
  fun `the geq noise equals chromaAt on a mid-range grid`() {
    val blue = band(DigicamExpressions.BLUE, TICK)
    val red = band(DigicamExpressions.RED, TICK)
    val green = band(DigicamExpressions.GREEN, TICK)

    var spread = 0
    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val chroma = DigicamSignal.chromaAt(SEED, TICK, y * NOISE_CELLS_ACROSS + x)
        withClue("x $x y $y") {
          codeAt(blue, x, y) shouldBe codeOf(chroma[0])
          codeAt(red, x, y) shouldBe codeOf(chroma[1])
          codeAt(green, x, y) shouldBe codeOf(greenOf(chroma[0], chroma[1]))
        }
        spread = maxOf(spread, codeAt(blue, x, y) - NOISE_MID)
      }
    }
    withClue("the noise has to reach most of the way up for this to tell") { spread shouldBeGreaterThan REACHING }
  }

  @Test
  fun `a noise plane equals the geq's on every band and raster`() {
    listOf(Size(320, 180) to Size(320, 180), Size(1280, 720) to Size(640, 360), Size(1918, 1080) to Size(640, 360))
      .forEach { (frame, raster) ->
        val input =
          digicamFragment(Digicam(seed = SEED), frame)
            .live
            .shouldNotBeNull()
            .inputs
            .single()
        withClue("the raster of a $frame frame") {
          input.width shouldBe raster.width
          input.height shouldBe raster.height * BANDS
        }
        listOf(TICK, TICK + 1, 0).forEach { tick ->
          val drawn =
            (0 until BANDS)
              .map { band(it, tick, raster.width, raster.height) }
              .reduce { planes, plane -> planes + plane }
          withClue("tick $tick at $raster") { input.planeAt(tick).contentEquals(drawn) shouldBe true }
        }
      }
  }

  @Test
  fun `a look with no noise reads no clock and a noise map's lut2 ends with the frame`() {
    val quiet = digicamFragment(Digicam(noise = 0f), Size(320, 180))
    quiet.live.shouldBeNull()
    quiet.readsCompositionClock shouldBe false

    val noisy = digicamFragment(Digicam(), Size(320, 180))
    noisy.readsCompositionClock shouldBe true
    val weigh =
      noisy.live
        .shouldNotBeNull()
        .branches
        .first { it.writes == listOf("moves") }
    weigh.nodes
      .single()
      .arguments
      .first()
      .key shouldBe "shortest"
  }

  private fun codeOf(move: Double): Int = (NOISE_MID_CODE + NOISE_REACH * move).toInt()

  private fun codeAt(
    plane: ByteArray,
    x: Int,
    y: Int,
  ): Int = plane[y * WIDTH + x].toInt() and 0xFF

  private fun band(
    band: Int,
    tick: Int,
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${width}x$height:d=0.04",
        "-frames:v",
        "1",
        "-vf",
        "format=gray,geq=lum='${DigicamExpressions.noiseBand(band, SEED, "$tick")}'",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    return out.toByteArray()
  }

  private companion object {
    const val WIDTH = 96
    const val HEIGHT = 54
    const val TICK = 4495
    const val SEED = 77
    const val BANDS = 3
    const val REACHING = 100
  }
}
