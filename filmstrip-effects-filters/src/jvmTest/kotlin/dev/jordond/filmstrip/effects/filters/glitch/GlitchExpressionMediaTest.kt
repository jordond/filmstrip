package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.test.Test

/**
 * Runs [GlitchExpressions.rowShift] and [GlitchExpressions.split] through ffmpeg's own `geq` and reads them back a byte
 * at a time, against [GlitchSignal.rowShift] and [GlitchSignal.channelSplit].
 *
 * Each column is a tick across a burst at half intensity and each row a virtual row down the frame. The ffmpeg is
 * [hostFfmpeg], the one the build declares as this suite's input.
 */
class GlitchExpressionMediaTest {
  @Test
  fun `the geq row shift equals the signal's on a mid grid across a burst`() {
    val params = GlitchParams.at(HALF)
    val expressions = GlitchExpressions(SEED, params)
    val first = (0 until SEARCH).first { GlitchSignal.burstAt(SEED, it, params) != null } - LEAD
    val shift =
      "st(2,$first+X);st(3,Y*$ROW_STRIDE+$ROW_BASE);st(4,${expressions.strength()});" +
        "st(9,${expressions.rowShift()});floor((ld(9)+1)*$SCALE+0.5)"
    var moved = 0
    var torn = 0

    BYTE_SHIFTS.forEach { byteShift ->
      val plane = plane("mod(floor(($shift)/$byteShift),256)")

      for (y in 0 until SIDE) {
        for (x in 0 until SIDE) {
          val expected = GlitchSignal.rowShift(SEED, first + x, y * ROW_STRIDE + ROW_BASE, params)
          if (expected != 0.0 && byteShift == 1L) moved++
          if (byteShift == 1L && tornAt(params, first + x, y * ROW_STRIDE + ROW_BASE)) torn++
          val code = floor((expected + 1) * SCALE + 0.5).toLong()
          withClue("tick ${first + x} row ${y * ROW_STRIDE + ROW_BASE} byte $byteShift") {
            plane[y * SIDE + x].toInt() and 0xFF shouldBe ((code / byteShift) and 0xFF).toInt()
          }
        }
      }
    }
    withClue("the grid crosses a burst") { (moved > SIDE) shouldBe true }
    withClue("the grid holds a tear") { (torn > 0) shouldBe true }
  }

  @Test
  fun `the geq split equals the signal's across a burst`() {
    val params = GlitchParams.at(HALF)
    val expressions = GlitchExpressions(SEED, params)
    val first = (0 until SEARCH).first { GlitchSignal.burstAt(SEED, it, params) != null } - LEAD
    val split = "st(2,$first+X+Y*$SIDE);st(4,${expressions.strength()});floor(${expressions.split()}*$SCALE+0.5)"
    val splits = (0 until SIDE * SIDE).map { GlitchSignal.channelSplit(SEED, first + it, params) }

    BYTE_SHIFTS.forEach { byteShift ->
      val plane = plane("mod(floor(($split)/$byteShift),256)")

      splits.forEachIndexed { index, expected ->
        val code = floor(expected * SCALE + 0.5).toLong()
        withClue("tick ${first + index} byte $byteShift") {
          plane[index].toInt() and 0xFF shouldBe ((code / byteShift) and 0xFF).toInt()
        }
      }
    }
    withClue("the grid holds a steady split and a wider one") { (splits.distinct().size > 1) shouldBe true }
  }

  private fun tornAt(
    params: GlitchParams,
    tick: Int,
    row: Int,
  ): Boolean = GlitchSignal.tearAt(SEED, tick, row, GlitchSignal.burstAt(SEED, tick, params), params) != 0.0

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
    const val SEED = Glitch.DEFAULT_SEED
    const val HALF = 0.5
    const val SEARCH = 100_000
    const val LEAD = 2
    const val SIDE = 32
    const val ROW_STRIDE = 33
    const val ROW_BASE = 7
    const val SCALE = 1_048_576.0
    val BYTE_SHIFTS = listOf(1L, 256L, 65536L)
  }
}
