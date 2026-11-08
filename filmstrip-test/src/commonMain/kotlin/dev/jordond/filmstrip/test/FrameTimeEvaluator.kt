package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.floor

// A recursive descent over the expression, one character at a time, holding the ten variables st and ld reach.
private class FrameTimeEvaluator(
  private val text: String,
  private val t: Double,
) {
  private var at = 0
  private val variables = DoubleArray(VARIABLES)

  fun evaluate(): Double {
    val value = sequence()
    check(at == text.length) { "Unread text at $at of $text" }
    return value
  }

  private fun sequence(): Double {
    var value = sum()
    while (peek(';')) {
      at++
      value = sum()
    }
    return value
  }

  private fun sum(): Double {
    var value = product()
    while (true) {
      value =
        when {
          peek('+') -> {
            at++
            value + product()
          }
          peek('-') -> {
            at++
            value - product()
          }
          else -> {
            return value
          }
        }
    }
  }

  private fun product(): Double {
    var value = unary()
    while (true) {
      value =
        when {
          peek('*') -> {
            at++
            value * unary()
          }
          peek('/') -> {
            at++
            value / unary()
          }
          else -> {
            return value
          }
        }
    }
  }

  private fun unary(): Double =
    when {
      peek('-') -> {
        at++
        -unary()
      }
      peek('+') -> {
        at++
        unary()
      }
      else -> {
        primary()
      }
    }

  private fun primary(): Double {
    if (peek('(')) {
      at++
      return sequence().also { expect(')') }
    }
    if (text[at].isDigit() || text[at] == '.') return number()
    val name = buildString { while (at < text.length && text[at].isLetter()) append(text[at++]) }
    if (!peek('(')) {
      return when (name) {
        "t", "T" -> t
        else -> error("Unknown constant $name in $text")
      }
    }
    at++
    val arguments = mutableListOf(sequence())
    while (peek(',')) {
      at++
      arguments += sequence()
    }
    expect(')')
    return call(name, arguments)
  }

  private fun call(
    name: String,
    arguments: List<Double>,
  ): Double =
    when (name) {
      "st" -> arguments[1].also { variables[arguments[0].toInt()] = it }
      "ld" -> variables[arguments[0].toInt()]
      "if" -> if (arguments[0] != 0.0) arguments[1] else arguments.getOrElse(2) { 0.0 }
      "lt" -> if (arguments[0] < arguments[1]) 1.0 else 0.0
      "round" -> roundHalfAway(arguments[0])
      "floor" -> floor(arguments[0])
      else -> error("Unknown function $name in $text")
    }

  private fun number(): Double {
    val start = at
    while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
    return text.substring(start, at).toDouble()
  }

  private fun peek(character: Char): Boolean = at < text.length && text[at] == character

  private fun expect(character: Char) {
    check(peek(character)) { "Expected $character at $at of $text" }
    at++
  }

  private fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + HALF) else floor(value + HALF)

  private companion object {
    const val VARIABLES = 10
    const val HALF = 0.5
  }
}

/**
 * What [expression] evaluates to on a frame whose own time is [t] seconds, read the way libavutil's eval reads it.
 *
 * It reads numbers, `t` and `T`, `+`, `-`, `*`, `/`, `;`, parentheses, and the functions `st`, `ld`, `if`, `lt`,
 * `round` and `floor`. `round` rounds half away from zero, as C's `round` does, and `;` evaluates both sides and
 * answers the right one. Anything else fails, so an expression reaching for more than this fails the test that reads
 * it.
 */
@InternalFilmstripApi
public fun evaluateFrameTime(
  expression: String,
  t: Double,
): Double = FrameTimeEvaluator(expression, t).evaluate()
