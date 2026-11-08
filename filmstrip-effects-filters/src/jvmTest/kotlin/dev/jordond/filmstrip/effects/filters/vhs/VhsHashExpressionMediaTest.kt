package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Runs [VhsExpressions.hash] through ffmpeg's own `geq` and reads it back a byte at a time, against
 * [FilterNoise.hash24].
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class VhsHashExpressionMediaTest {
  @Test
  fun `the geq hash equals hash24 on a mid-range grid`() {
    val expressions = VhsExpressions(SEED, VhsParams.at(0.5, TapeSpeed.EP))
    val hash = expressions.hash(PASS_LUMA_NOISE, "$TICK", "$CELL_BASE+Y*16+X")

    SHIFTS.forEach { shift ->
      val plane = plane("mod(floor(($hash)/$shift),256)")

      for (y in 0 until SIDE) {
        for (x in 0 until SIDE) {
          val expected = FilterNoise.hash24(SEED, PASS_LUMA_NOISE, TICK, CELL_BASE + y * 16 + x)
          withClue("x $x y $y shift $shift") {
            plane[y * SIDE + x].toInt() and 0xFF shouldBe ((expected / shift) and 0xFF)
          }
        }
      }
    }
  }

  private fun plane(expression: String): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${SIDE}x$SIDE:d=0.04",
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

  private companion object {
    const val SEED = 1984
    const val TICK = 3596
    const val CELL_BASE = 9000
    const val SIDE = 16
    val SHIFTS = listOf(1, 256, 65536)
  }
}
