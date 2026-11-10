package dev.jordond.filmstrip.effects.filters.internal

// BT.709 luma weights.
internal const val KR = 0.2126
internal const val KB = 0.0722

// The Y' of an R'G'B' code triple.
internal fun lumaOf(
  red: Double,
  green: Double,
  blue: Double,
): Double = KR * red + (1.0 - KR - KB) * green + KB * blue

// GLSL's smoothstep, for a filter's signal to read.
internal fun smoothstep(
  from: Double,
  to: Double,
  value: Double,
): Double {
  val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
  return t * t * (3.0 - 2.0 * t)
}

// A double as a GLSL float literal. A browser prints a whole double with no point, which GLSL reads as an int.
internal fun glslFloat(value: Double): String {
  val text = value.toString()
  return if (text.contains('.') || text.contains('e') || text.contains('E')) text else "$text.0"
}

// A double as the 32 bit float a uniform holds. A Float on js keeps the whole double, a FloatArray rounds everywhere.
internal fun uniformFloat(value: Double): Float = floatArrayOf(value.toFloat())[0]
