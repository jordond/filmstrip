package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.nitsFromPqSignal
import dev.jordond.filmstrip.media.sceneFromHlgSignal
import dev.jordond.filmstrip.media.signalFromNits
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The transfer expressions an HDR overlay's blend runs on, evaluated here the way ffmpeg reads them and held against
 * core's own figures.
 *
 * Every level is read in the middle of its range, where a transfer read per channel and one read on luminance, or a
 * gain read as additive and one read as multiplicative, part ways.
 */
class HdrExpressionsTest {
  @Test
  fun `a grey PQ signal decodes to the light core puts it at`() {
    val light = decode(HdrTransfer.Pq, floatArrayOf(MID_SIGNAL, MID_SIGNAL, MID_SIGNAL))

    assertNear(light, FloatArray(3) { nitsFromPqSignal(MID_SIGNAL) / PQ_PEAK_NITS }, "PQ $MID_SIGNAL")
  }

  @Test
  fun `a grey HLG signal decodes through the system gamma core applies`() {
    val light = decode(HdrTransfer.Hlg, floatArrayOf(MID_SIGNAL, MID_SIGNAL, MID_SIGNAL))
    val expected = hlgDisplayNitsFromScene(sceneFromHlgSignal(MID_SIGNAL)) / HLG_NOMINAL_PEAK_NITS

    assertNear(light, FloatArray(3) { expected }, "HLG $MID_SIGNAL")
  }

  @Test
  fun `a colour's light encodes onto the signal core writes it at`() {
    for (transfer in HdrTransfer.entries) {
      for (colour in COLOURS) {
        val nits = authoredHdrNits(colour)
        val peak = peakOf(transfer)
        val signal = encode(transfer, FloatArray(3) { nits[it] / peak })

        assertNear(signal, transfer.signalFromNits(nits), "$transfer ${colour.toUInt().toString(16)}")
      }
    }
  }

  // A saturated colour is where HLG's luminance-weighted system gamma parts from one applied to each channel alone.
  @Test
  fun `a colour's signal decodes back to the light core put it at`() {
    for (transfer in HdrTransfer.entries) {
      for (colour in COLOURS) {
        val nits = authoredHdrNits(colour)
        val peak = peakOf(transfer)
        val light = decode(transfer, transfer.signalFromNits(nits))

        assertNear(light, FloatArray(3) { nits[it] / peak }, "$transfer ${colour.toUInt().toString(16)}")
      }
    }
  }

  private fun decode(
    transfer: HdrTransfer,
    signal: FloatArray,
  ): FloatArray = evaluate(displayLightFromSignal(transfer, PIXEL), signal)

  private fun encode(
    transfer: HdrTransfer,
    light: FloatArray,
  ): FloatArray = evaluate(signalFromDisplayLight(transfer, PIXEL), light)

  private fun evaluate(
    expressions: List<String>,
    pixel: FloatArray,
  ): FloatArray = FloatArray(3) { Expression(expressions[it], pixel).value().toFloat() }

  private fun peakOf(transfer: HdrTransfer): Float =
    when (transfer) {
      HdrTransfer.Pq -> PQ_PEAK_NITS
      HdrTransfer.Hlg -> HLG_NOMINAL_PEAK_NITS
    }

  private fun assertNear(
    read: FloatArray,
    expected: FloatArray,
    what: String,
  ) {
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= TOLERANCE * maxOf(1f, abs(expected[it])) },
      "$what read ${read.toList()}, expected ${expected.toList()}",
    )
  }

  /**
   * Evaluates the subset of ffmpeg's expression language these builders write, with `r(X,Y)`, `g(X,Y)` and `b(X,Y)`
   * reading [pixel].
   *
   * Precedence follows ffmpeg's: a sign binds tighter than `^`, which binds tighter than `*` and `/`, which bind
   * tighter than `+` and `-`, and `;` runs the expressions either side of it in turn and answers the last. `st` and
   * `ld` store and load ffmpeg's ten expression variables.
   */
  private class Expression(
    private val text: String,
    private val pixel: FloatArray,
  ) {
    private var at = 0
    private val variables = DoubleArray(VARIABLES)

    fun value(): Double = sequence().also { check(at == text.length) { "Unread text at $at of $text" } }

    private fun sequence(): Double {
      var value = additive()
      while (peek() == ';') value = next { additive() }
      return value
    }

    private fun additive(): Double {
      var value = product()
      while (true) {
        value =
          when (peek()) {
            '+' -> value + next { product() }
            '-' -> value - next { product() }
            else -> return value
          }
      }
    }

    private fun product(): Double {
      var value = power()
      while (true) {
        value =
          when (peek()) {
            '*' -> value * next { power() }
            '/' -> value / next { power() }
            else -> return value
          }
      }
    }

    private fun power(): Double {
      var value = factor()
      while (peek() == '^') value = value.pow(next { factor() })
      return value
    }

    private fun factor(): Double =
      when (peek()) {
        '-' -> -next { factor() }
        '+' -> next { factor() }
        else -> primary()
      }

    private fun primary(): Double {
      if (peek() == '(') return next { additive() }.also { expect(')') }
      if (peek()?.isDigit() == true || peek() == '.') return number()

      val start = at
      while (peek()?.isLetterOrDigit() == true) at++
      val name = text.substring(start, at)
      // A bare name is a pixel's X or Y, which every read here ignores.
      if (peek() != '(') return 0.0
      expect('(')
      val arguments =
        buildList {
          add(additive())
          while (peek() == ',') add(next { additive() })
        }
      expect(')')
      return call(name, arguments)
    }

    private fun call(
      name: String,
      arguments: List<Double>,
    ): Double =
      when (name) {
        "r" -> pixel[0].toDouble()
        "g" -> pixel[1].toDouble()
        "b" -> pixel[2].toDouble()
        "pow" -> arguments[0].pow(arguments[1])
        "exp" -> exp(arguments[0])
        "log" -> ln(arguments[0])
        "sqrt" -> sqrt(arguments[0])
        "max" -> maxOf(arguments[0], arguments[1])
        "clip" -> arguments[0].coerceIn(arguments[1], arguments[2])
        "if" -> if (arguments[0] != 0.0) arguments[1] else arguments[2]
        "lte" -> if (arguments[0] <= arguments[1]) 1.0 else 0.0
        "gt" -> if (arguments[0] > arguments[1]) 1.0 else 0.0
        "st" -> arguments[1].also { variables[arguments[0].toInt()] = it }
        "ld" -> variables[arguments[0].toInt()]
        else -> error("No function $name in $text")
      }

    private fun number(): Double {
      val start = at
      while (peek()?.let { it.isDigit() || it == '.' } == true) at++
      if (peek() == 'E' || peek() == 'e') {
        at++
        if (peek() == '-' || peek() == '+') at++
        while (peek()?.isDigit() == true) at++
      }
      return text.substring(start, at).toDouble()
    }

    private fun <T> next(read: () -> T): T {
      at++
      return read()
    }

    private fun expect(char: Char) {
      check(peek() == char) { "Expected $char at $at of $text" }
      at++
    }

    private fun peek(): Char? = text.getOrNull(at)
  }

  private companion object {
    val PIXEL = listOf("r(X,Y)", "g(X,Y)", "b(X,Y)")
    const val MID_SIGNAL = 0.6f

    // How many variables st and ld reach, which is ffmpeg's own count.
    const val VARIABLES = 10

    // White, a mid grey and a saturated green, so a figure read per channel and one read on luminance part ways.
    val COLOURS = listOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF308020.toInt())

    // Relative, and well under a ten-bit code, which is a thousandth of the signal.
    const val TOLERANCE = 1e-4f
  }
}
