package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.InternalFilmstripApi

// hash24's constants. A multiplier below 2^24 keeps its product inside a double's 53 bits.
internal const val HASH_M1 = 0x9E2D51
internal const val HASH_M2 = 0x873593
internal const val HASH_M3 = 0xEBCA6B
internal const val HASH_M4 = 0xB2AE35
internal const val HASH_FIVE = 5
internal const val HASH_ADD = 0x546B64
internal const val HASH_MASK = 0xFFFFFF
internal const val HASH_RANGE = 16_777_216.0
internal const val HASH_ABSORB_SHIFT = 11
internal const val HASH_MIX_SHIFT = 13
internal const val HASH_END_SHIFT = 12

/**
 * The random draw every filter is made of, whose steps stay inside what GLSL, MSL, Kotlin and an ffmpeg expression can
 * all compute exactly.
 */
@InternalFilmstripApi
public object FilterNoise {
  /**
   * Draws 24 random bits from [seed], the stream [passId], [tick] and [cell].
   *
   * A product keeps its low 24 bits, which a wrapping 32 bit multiply and a double's 53 bits both get exactly. Xor and
   * shift can be written as `bitor(a,b)-bitand(a,b)` and `floor` where an expression has neither.
   */
  public fun hash24(
    seed: Int,
    passId: Int,
    tick: Int,
    cell: Int,
  ): Int {
    var h = seed and HASH_MASK
    h = absorb(h, passId)
    h = absorb(h, tick)
    h = absorb(h, cell)
    h = h xor (h ushr HASH_END_SHIFT)
    h = multiply(h, HASH_M3)
    h = h xor (h ushr HASH_ABSORB_SHIFT)
    h = multiply(h, HASH_M4)
    return h xor (h ushr HASH_END_SHIFT)
  }

  /**
   * A [hash24] as a value from `0.0` up to but not including `1.0`.
   */
  public fun unorm(hash: Int): Double = hash / HASH_RANGE

  /**
   * A [hash24] as a value from `-1.0` up to but not including `1.0`.
   */
  public fun snorm(hash: Int): Double = unorm(hash) * 2.0 - 1.0

  private fun absorb(
    h: Int,
    input: Int,
  ): Int {
    var k = multiply(input and HASH_MASK, HASH_M1)
    k = k xor (k ushr HASH_ABSORB_SHIFT)
    k = multiply(k, HASH_M2)
    val mixed = (((h xor k).toLong() * HASH_FIVE + HASH_ADD) and HASH_MASK.toLong()).toInt()
    return mixed xor (mixed ushr HASH_MIX_SHIFT)
  }

  private fun multiply(
    a: Int,
    b: Int,
  ): Int = ((a.toLong() * b.toLong()) and HASH_MASK.toLong()).toInt()
}

// hash24 in GLSL ES 3.00 as draw(pass, tick, cell), a unorm of the draw under the uSeed uniform. Each line carries the
// fragment sources' own two spaces, so trimIndent still lands #version first.
internal val HASH_GLSL: String =
  """
  uint absorb(uint h, uint k) {
    k = (k * ${HASH_M1}u) & ${HASH_MASK}u;
    k ^= k >> ${HASH_ABSORB_SHIFT}u;
    k = (k * ${HASH_M2}u) & ${HASH_MASK}u;
    h = ((h ^ k) * ${HASH_FIVE}u + ${HASH_ADD}u) & ${HASH_MASK}u;
    return h ^ (h >> ${HASH_MIX_SHIFT}u);
  }

  float draw(uint pass, uint tick, uint cell) {
    uint h = absorb(absorb(absorb(uint(uSeed + 0.5), pass), tick & ${HASH_MASK}u), cell & ${HASH_MASK}u);
    h ^= h >> ${HASH_END_SHIFT}u;
    h = (h * ${HASH_M3}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_ABSORB_SHIFT}u;
    h = (h * ${HASH_M4}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_END_SHIFT}u;
    return float(h) / ${glslFloat(HASH_RANGE)};
  }
  """

// hash24 in Metal as filterDraw(seed, pass, tick, cell), a unorm of the draw. Each line carries the kernel sources' own
// two spaces, so trimIndent still lands the includes first.
internal val HASH_MSL: String =
  """
  uint filterAbsorb(uint h, uint k) {
    k = (k * ${HASH_M1}u) & ${HASH_MASK}u;
    k ^= k >> ${HASH_ABSORB_SHIFT}u;
    k = (k * ${HASH_M2}u) & ${HASH_MASK}u;
    h = ((h ^ k) * ${HASH_FIVE}u + ${HASH_ADD}u) & ${HASH_MASK}u;
    return h ^ (h >> ${HASH_MIX_SHIFT}u);
  }

  float filterDraw(uint seed, uint pass, uint tick, uint cell) {
    uint h = filterAbsorb(filterAbsorb(filterAbsorb(seed, pass), tick & ${HASH_MASK}u), cell & ${HASH_MASK}u);
    h ^= h >> ${HASH_END_SHIFT}u;
    h = (h * ${HASH_M3}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_ABSORB_SHIFT}u;
    h = (h * ${HASH_M4}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_END_SHIFT}u;
    return float(h) / ${HASH_RANGE}f;
  }
  """
