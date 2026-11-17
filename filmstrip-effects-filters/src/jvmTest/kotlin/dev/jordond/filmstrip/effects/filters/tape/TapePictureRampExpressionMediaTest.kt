package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the expressions of a settle through ffmpeg's own `geq` on a 16 bit plane, a tick to a column, against the
 * functions the other backends read: [TapePictureRamp.levelAt] and the colour a band takes.
 *
 * The expression is [kotlin.math] in double and [TapePictureRamp.levelAt] paces through [Easing.paced], which is in
 * float, so the two can land one code of the 16 bit plane apart at a rounding edge and no more.
 */
class TapePictureRampExpressionMediaTest {
  private val slot = TimeRange(10.seconds)

  @Test
  fun `the level expression equals the ramp's level on every tick of every easing`() {
    Easing.entries.forEach { easing ->
      RAMPS.forEach { (from, to) ->
        val look = Vhs(intensity = to, tracking = Tracking.Rolling(), settle = TapeSettle(from, 4.seconds, easing))
        val ramp = TapePictureRamp.of(look, slot)!!
        val figures = figuresOf(look, ramp)
        val first = ramp.startTick - MARGIN
        val read = plane("clip(${figures.levelAt("(X+$first)")},0,1)*$FULL+0.5")

        withClue("$easing from $from to $to") {
          for (column in 0 until WIDTH) {
            val want = (ramp.levelAt(first + column) * FULL).roundToInt()
            val got = code(read, column)
            withClue("tick ${first + column}") { (abs(got - want) <= 1) shouldBe true }
          }
        }
      }
    }
  }

  @Test
  fun `the colour a band takes equals the signal's on every tick`() {
    Easing.entries.forEach { easing ->
      RAMPS.forEach { (from, to) ->
        val look = Vhs(intensity = to, tracking = Tracking.Rolling(), settle = TapeSettle(from, 4.seconds, easing))
        val ramp = TapePictureRamp.of(look, slot)!!
        val figures = figuresOf(look, ramp)
        val first = ramp.startTick - MARGIN
        val level = figures.levelAt("(X+$first)")
        val read = plane("${figures.chromaLoss(level)}*$FULL+0.5")

        withClue("$easing from $from to $to") {
          for (column in 0 until WIDTH) {
            val params = look.pictureParams(ramp.levelAt(first + column), slot.start)
            val want = (params.trackingChromaLoss * FULL).roundToInt()
            withClue("tick ${first + column}") { (abs(code(read, column) - want) <= 1) shouldBe true }
          }
        }
      }
    }
  }

  private fun figuresOf(
    look: Vhs,
    ramp: TapePictureRamp,
  ) = TapePictureFigures(look.pictureParams(ramp.peak, slot.start), look.pictureParams(1.0, slot.start), ramp)

  private fun code(
    plane: ByteArray,
    column: Int,
  ) = (plane[2 * column].toInt() and 0xFF) or ((plane[2 * column + 1].toInt() and 0xFF) shl 8)

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
        "color=c=black:s=${WIDTH}x1:d=0.04",
        "-frames:v",
        "1",
        "-vf",
        "format=gray16le,geq=lum='$expression'",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray16le",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    return out.toByteArray()
  }

  private companion object {
    const val WIDTH = 512
    const val MARGIN = 10
    const val FULL = 65535
    val RAMPS = listOf(0f to 0.8f, 0.8f to 0f, 1f to 0.5f, 0.35f to 1f, 1f to 0.35f)
  }
}
