package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * Runs [RgbRoundTrip]'s steps through the host's ffmpeg and reads the codes they hand over.
 */
class RgbRoundTripMediaTest {
  // Every sample lands within half a code of its own value, codes of eight and the samples between them alike. A step
  // that only scaled to swscale's width, as val*65280/65535, drops 2192 of these a code on 8.0.1.
  @Test
  fun `the way down to eight bits lands every sample on its nearest code on every plane`() {
    val down = filterOf(RgbRoundTrip.toEightBits())
    val sample = "X+$SIDE*Y"
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
        "format=gbrap16le,geq=r=$sample:g=$sample:b=$sample:a=$sample,$down",
        "-f",
        "rawvideo",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val read = process.inputStream.readBytes()
    process.waitFor() shouldBe 0

    read.size shouldBe SAMPLES * PLANES
    read.forEachIndexed { at, code ->
      val wide = at % SAMPLES
      withClue("plane ${at / SAMPLES}, sample $wide") {
        abs((code.toInt() and 0xFF) - wide / WIDE_PER_CODE) shouldBeLessThanOrEqual HALF_CODE
      }
    }
  }

  // An untagged frame is read on BT.601 and carried to BT.709, which moves no grey. Without exact rounding swscale
  // lands a grey luma of 126 on 125 on 8.0.1.
  @Test
  fun `the opening carries every grey of an untagged frame over on its own luma`() {
    val opening = filterOf(RgbRoundTrip.opening())
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${SIDE}x2:d=0.04,format=yuv420p,geq=lum='clip(X,$LUMA_BLACK,$LUMA_WHITE)':cb=128:cr=128",
        "-frames:v",
        "1",
        "-vf",
        "$opening,format=yuv420p",
        "-f",
        "rawvideo",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val read = process.inputStream.readBytes()
    process.waitFor() shouldBe 0

    (0 until SIDE).forEach { x ->
      withClue("luma at $x") { read[x].toInt() and 0xFF shouldBe x.coerceIn(LUMA_BLACK, LUMA_WHITE) }
    }
  }

  // swscale widens a code of 200 to 51202, which a read over 257 takes for 199.
  @Test
  fun `every code reads back from its widened sample on every plane`() {
    val code = { plane: String -> RgbRoundTrip.codeOfWidened("$plane(X,Y)") }
    val back = "geq=r='${code("r")}':g='${code("g")}':b='${code("b")}':a='${code("alpha")}'"
    val codes = "color=c=black:s=${SIDE}x1:d=0.04,format=gbrap,geq=r=X:g=X:b=X:a=X"
    val read = run("$codes,format=gbrap16le,$back", "gbrap16le")

    read.size shouldBe SIDE * PLANES * 2
    (0 until SIDE * PLANES).forEach { at ->
      val wide = (read[2 * at].toInt() and 0xFF) or ((read[2 * at + 1].toInt() and 0xFF) shl 8)
      withClue("plane ${at / SIDE}, code ${at % SIDE}") { wide shouldBe at % SIDE }
    }
  }

  @Test
  fun `full chroma keeps a stripe of colour one pixel wide`() {
    val toCodes = filterOf(RgbRoundTrip.toCodes(fullChroma = true))
    val stripes = "if(mod(X,2),255,0)"
    val read = run("color=c=black:s=${SIDE}x2:d=0.04,format=gbrap,geq=r='$stripes':g=0:b=0:a=255,$toCodes", "yuva444p")

    read.size shouldBe SIDE * 2 * PLANES
    val cr = read.copyOfRange(SIDE * 2 * 2, SIDE * 2 * 2 + SIDE).map { it.toInt() and 0xFF }
    cr.zipWithNext().forEach { (left, right) -> abs(left - right) shouldBeGreaterThan STRIPE_CODES }
  }

  private fun run(
    source: String,
    format: String,
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
        source,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        format,
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val read = process.inputStream.readBytes()
    process.waitFor() shouldBe 0
    return read
  }

  private fun filterOf(nodes: List<FilterNode>): String =
    nodes.joinToString(",") { node -> node.name + "=" + node.arguments.joinToString(":") { "${it.key}='${it.value}'" } }

  private companion object {
    const val LUMA_BLACK = 16
    const val LUMA_WHITE = 235
    const val SIDE = 256
    const val SAMPLES = SIDE * SIDE
    const val PLANES = 4
    const val WIDE_PER_CODE = 257.0
    const val HALF_CODE = 0.5
    const val STRIPE_CODES = 100
  }
}
