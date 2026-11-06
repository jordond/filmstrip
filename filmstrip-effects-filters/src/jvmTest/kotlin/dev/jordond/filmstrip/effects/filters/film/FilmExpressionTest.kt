package dev.jordond.filmstrip.effects.filters.film

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Runs [FilmExpressions.grainPlane] through ffmpeg's own `geq` and reads it back against [FilmSignal.grainAt].
 *
 * Skipped when the host has no ffmpeg, since nothing here could run the expression.
 */
class FilmExpressionTest {
  @Test
  fun `the geq grain equals grainAt on a mid-range grid`() {
    assumeTrue("this host has no ffmpeg", hasFfmpeg())
    val span = FilmSignal.grainCellSpan(WIDTH, HEIGHT)

    val plane = plane(FilmExpressions.grainPlane(span, "$TICK"))

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val expected = MID + REACH * FilmSignal.grainAt(FilmSignal.grainCell(x, y, WIDTH, HEIGHT), TICK)
        withClue("x $x y $y") {
          (plane[y * WIDTH + x].toInt() and 0xFF) shouldBe expected.roundToInt()
        }
      }
    }
  }

  private fun plane(expression: String): ByteArray {
    val process =
      ProcessBuilder(
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${WIDTH}x$HEIGHT:d=0.04",
        "-frames:v",
        "1",
        "-vf",
        "format=gray,geq=lum='$expression'",
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

  private fun hasFfmpeg(): Boolean =
    runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

  private companion object {
    const val WIDTH = 96
    const val HEIGHT = 54
    const val TICK = 3596
    const val MID = 128.0
    const val REACH = 127.0
  }
}
