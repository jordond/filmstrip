package dev.jordond.filmstrip.effects.filters.internal

/**
 * Writes [FilterNoise.hash24] for one seed as an ffmpeg expression, working through registers 0 and 1.
 *
 * The state after the pass and the tick holds for a whole frame, so an expression that hashes every pixel can work
 * [state] out once a row and run only [hashFrom] per pixel.
 */
internal class HashExpression(
  seed: Int,
) {
  private val seedBits = seed and HASH_MASK

  /**
   * [FilterNoise.hash24] of [pass], [tick] and [cell], leaving it in register 0.
   */
  fun hash(
    pass: Int,
    tick: String,
    cell: String,
  ): String = "${absorbed(pass, tick)};${finished(cell)}"

  /**
   * The state after [pass] and [tick], as the value of the expression, for [hashFrom].
   */
  fun state(
    pass: Int,
    tick: String,
  ): String = "${absorbed(pass, tick)};ld(0)"

  /**
   * The hash of [cell] from a [state] held in [state], leaving it in register 0.
   */
  fun hashFrom(
    state: String,
    cell: String,
  ): String = "st(0,$state);${finished(cell)}"

  private fun absorbed(
    pass: Int,
    tick: String,
  ): String = listOf("st(0,$seedBits)", absorb("$pass"), absorb(tick)).joinToString(";")

  private fun finished(cell: String): String =
    listOf(
      absorb(cell),
      "st(0,${xorShift("ld(0)", HASH_END_SHIFT)})",
      "st(0,${multiply("ld(0)", HASH_M3)})",
      "st(0,${xorShift("ld(0)", HASH_ABSORB_SHIFT)})",
      "st(0,${multiply("ld(0)", HASH_M4)})",
      "st(0,${xorShift("ld(0)", HASH_END_SHIFT)})",
      "ld(0)",
    ).joinToString(";")

  private fun absorb(input: String): String =
    listOf(
      "st(1,${multiply("mod($input,$HASH_RANGE_INT)", HASH_M1)})",
      "st(1,${xorShift("ld(1)", HASH_ABSORB_SHIFT)})",
      "st(1,${multiply("ld(1)", HASH_M2)})",
      "st(0,mod(${xor("ld(0)", "ld(1)")}*$HASH_FIVE+$HASH_ADD,$HASH_RANGE_INT))",
      "st(0,${xorShift("ld(0)", HASH_MIX_SHIFT)})",
    ).joinToString(";")

  private fun multiply(
    value: String,
    by: Int,
  ) = "mod($value*$by,$HASH_RANGE_INT)"

  private fun xorShift(
    value: String,
    bits: Int,
  ) = xor(value, "floor($value/${1 shl bits})")

  private fun xor(
    a: String,
    b: String,
  ) = "(bitor($a,$b)-bitand($a,$b))"

  private companion object {
    const val HASH_RANGE_INT = 16777216
  }
}
