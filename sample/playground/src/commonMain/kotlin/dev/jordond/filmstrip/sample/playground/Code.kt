package dev.jordond.filmstrip.sample.playground

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * What a piece of the code listing is, which sets its colour.
 */
internal enum class CodeKind { Frame, Call, Value, Comment }

/**
 * One run of text in the code listing.
 */
internal data class CodePart(
  val text: String,
  val kind: CodeKind,
)

/**
 * [lines] as plain text, for copying.
 */
internal fun codeText(lines: List<List<CodePart>>): String =
  lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

/**
 * [value] as a Kotlin float literal with at most two decimals, like `1.25f` or `-90f`.
 */
internal fun floatLiteral(value: Float): String {
  val hundredths = (value * 100).roundToInt()
  val whole = abs(hundredths) / 100
  val fraction = abs(hundredths) % 100
  val sign = if (hundredths < 0) "-" else ""
  val decimals =
    when {
      fraction == 0 -> ""
      fraction % 10 == 0 -> ".${fraction / 10}"
      fraction < 10 -> ".0$fraction"
      else -> ".$fraction"
    }
  return "$sign$whole${decimals}f"
}

/**
 * [value] rounded to two decimals, the precision the code listing shows.
 */
internal fun roundToHundredths(value: Float): Float = round(value * 100) / 100
