package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.math.abs
import kotlin.test.Test

class YcbcrCodesTest {
  @Test
  fun `the codes a reading gives decode back to the color within one code`() {
    val readings =
      mapOf(
        "BT.709" to ycbcrReading(1, 1, 1),
        "identity" to ycbcrReading(0, 9, 16),
        "YCgCo" to ycbcrReading(8, 1, 13),
        "BT.2020 constant luminance" to ycbcrReading(10, 9, 14),
      )
    for ((name, reading) in readings) {
      for (fullRange in listOf(true, false)) {
        for (bitDepth in listOf(8, 10)) {
          withClue("$name, $bitDepth bits, full range $fullRange") {
            val codes = reading.ycbcrCodes(FILL[0], FILL[1], FILL[2], bitDepth, fullRange)
            val evaluator = reading.evaluator(bitDepth, fullRange)
            val back = evaluator.rgb(codes[0], codes[1], codes[2]).copyOf()
            val step =
              (0 until 3).maxOf { channel ->
                val moved = codes.copyOf().also { it[channel] += 1 }
                val there = evaluator.rgb(moved[0], moved[1], moved[2])
                (0 until 3).maxOf { abs(there[it] - back[it]) }
              }
            val miss = (0 until 3).maxOf { abs(back[it] - FILL[it] / 65535.0) }
            withClue("${codes.toList()} decode $miss off, one code moves $step") { (miss <= step).shouldBeTrue() }
          }
        }
      }
    }
  }

  private companion object {
    // Mid saturation, neither a primary nor a grey.
    val FILL = intArrayOf(0xC000, 0x7000, 0x5000)
  }
}
