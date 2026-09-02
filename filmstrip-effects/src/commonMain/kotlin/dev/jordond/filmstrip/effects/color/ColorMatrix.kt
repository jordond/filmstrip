package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Recombine the colour channels of every pixel through a 3x3 matrix and an offset.
 *
 * Each output channel is a weighted sum of the three input channels plus a bias, so red comes out as
 * `rr * r + rg * g + rb * b + rBias` and the other two rows read the same way. Channels are the
 * encoded signal in the range zero to one, the same domain [Brightness] multiplies, and the result
 * is clamped back into that range once the whole matrix has been applied. Alpha is never touched.
 *
 * Every other colour effect in this package is one of these under the hood, and a run of them is
 * folded into a single matrix before any backend clamps, so an effect that pushes a channel past
 * white and one that pulls it back again cancel out rather than saturating in between.
 *
 * The defaults are the identity, so a caller names only the entries that change. A NaN entry is
 * read as its identity value.
 *
 * @property rr How much input red contributes to output red.
 * @property rg How much input green contributes to output red.
 * @property rb How much input blue contributes to output red.
 * @property rBias What is added to output red.
 * @property gr How much input red contributes to output green.
 * @property gg How much input green contributes to output green.
 * @property gb How much input blue contributes to output green.
 * @property gBias What is added to output green.
 * @property br How much input red contributes to output blue.
 * @property bg How much input green contributes to output blue.
 * @property bb How much input blue contributes to output blue.
 * @property bBias What is added to output blue.
 */
@Serializable
@SerialName(EffectIds.COLOR_MATRIX)
@Poko
public class ColorMatrix(
  public val rr: Float = 1f,
  public val rg: Float = 0f,
  public val rb: Float = 0f,
  public val rBias: Float = 0f,
  public val gr: Float = 0f,
  public val gg: Float = 1f,
  public val gb: Float = 0f,
  public val gBias: Float = 0f,
  public val br: Float = 0f,
  public val bg: Float = 0f,
  public val bb: Float = 1f,
  public val bBias: Float = 0f,
) : EffectSpec {
  override val id: String get() = EffectIds.COLOR_MATRIX

  override val stage: EffectStage get() = EffectStage.Color

  public companion object {
    /**
     * The matrix that leaves every pixel as it was.
     */
    public val Identity: ColorMatrix = ColorMatrix()
  }
}

/**
 * Recombine the colour channels through a matrix. See [ColorMatrix] for how the entries are read.
 */
public fun EffectsBuilder.colorMatrix(matrix: ColorMatrix): EffectsBuilder = add(matrix)

/**
 * The matrix a backend applies, with every NaN entry replaced by the identity's value there.
 *
 * A NaN reaches a backend as a shader uniform, a filter table entry or a Core Image vector, and
 * each fails a different way, so it is turned into the no-op here.
 */
internal val ColorMatrix.sanitised: ColorMatrix
  get() =
    ColorMatrix(
      rr = rr.orIdentity(1f),
      rg = rg.orIdentity(0f),
      rb = rb.orIdentity(0f),
      rBias = rBias.orIdentity(0f),
      gr = gr.orIdentity(0f),
      gg = gg.orIdentity(1f),
      gb = gb.orIdentity(0f),
      gBias = gBias.orIdentity(0f),
      br = br.orIdentity(0f),
      bg = bg.orIdentity(0f),
      bb = bb.orIdentity(1f),
      bBias = bBias.orIdentity(0f),
    )

private fun Float.orIdentity(identity: Float): Float = if (isNaN()) identity else this

/**
 * Whether this matrix changes nothing.
 */
internal val ColorMatrix.isIdentity: Boolean get() = this == ColorMatrix.Identity

/**
 * Whether every output channel reads from its own input channel alone, so the matrix is three
 * independent per-channel lines rather than a mix.
 */
internal val ColorMatrix.isDiagonal: Boolean
  get() = rg == 0f && rb == 0f && gr == 0f && gb == 0f && br == 0f && bg == 0f

/**
 * The matrix that applies this one and then [next], as a single matrix.
 *
 * Running `next` on the output of `this` is `next.M * (this.M * v + this.b) + next.b`, so the
 * combined matrix is the product and the combined bias is `this.b` carried through `next.M` with
 * `next.b` added.
 */
internal fun ColorMatrix.then(next: ColorMatrix): ColorMatrix =
  ColorMatrix(
    rr = next.rr * rr + next.rg * gr + next.rb * br,
    rg = next.rr * rg + next.rg * gg + next.rb * bg,
    rb = next.rr * rb + next.rg * gb + next.rb * bb,
    rBias = next.rr * rBias + next.rg * gBias + next.rb * bBias + next.rBias,
    gr = next.gr * rr + next.gg * gr + next.gb * br,
    gg = next.gr * rg + next.gg * gg + next.gb * bg,
    gb = next.gr * rb + next.gg * gb + next.gb * bb,
    gBias = next.gr * rBias + next.gg * gBias + next.gb * bBias + next.gBias,
    br = next.br * rr + next.bg * gr + next.bb * br,
    bg = next.br * rg + next.bg * gg + next.bb * bg,
    bb = next.br * rb + next.bg * gb + next.bb * bb,
    bBias = next.br * rBias + next.bg * gBias + next.bb * bBias + next.bBias,
  )

/**
 * What this matrix writes for an encoded pixel, clamped to the range a frame can hold.
 *
 * This is the reference every backend is measured against, so a backend test computes its expected
 * pixel here rather than carrying a number of its own.
 *
 * @return red, green and blue in the range zero to one.
 */
internal fun ColorMatrix.transform(
  red: Float,
  green: Float,
  blue: Float,
): FloatArray =
  floatArrayOf(
    (rr * red + rg * green + rb * blue + rBias).coerceIn(0f, 1f),
    (gr * red + gg * green + gb * blue + gBias).coerceIn(0f, 1f),
    (br * red + bg * green + bb * blue + bBias).coerceIn(0f, 1f),
  )

/**
 * This matrix as the sixteen floats a GL `mat4` uniform takes, applied to `vec4(rgb, 1)`.
 *
 * Column-major, so the three rows land at a stride of four and the bias is the fourth column. The
 * fourth row passes the homogeneous one through, and a shader that keeps its own alpha never reads
 * it.
 */
internal fun ColorMatrix.toColumnMajor4x4(): FloatArray =
  floatArrayOf(
    rr, gr, br, 0f,
    rg, gg, bg, 0f,
    rb, gb, bb, 0f,
    rBias, gBias, bBias, 1f,
  )

/**
 * A matrix that scales each channel on its own, with no bias and no mixing.
 */
internal fun scaleMatrix(
  red: Float,
  green: Float,
  blue: Float,
): ColorMatrix = ColorMatrix(rr = red, gg = green, bb = blue)

/**
 * The matrix behind [spec], or null for an effect that is not a colour matrix.
 *
 * This is the one place the catalogue's colour effects are turned into numbers, so every backend
 * lowers the same matrix and the fold that merges a run of them sees the same one too.
 */
internal fun colorMatrixOf(spec: EffectSpec): ColorMatrix? =
  when (spec) {
    is Brightness -> spec.matrix
    is RgbAdjustment -> spec.matrix
    is Contrast -> spec.matrix
    is Saturation -> spec.matrix
    is HueRotate -> spec.matrix
    is Sepia -> spec.matrix
    is Invert -> spec.matrix
    is ColorMatrix -> spec.sanitised
    else -> null
  }

/**
 * Why a colour matrix is refused on an export that keeps an HDR grade.
 *
 * The same text on every backend, so a plan reads the same whichever one refused.
 */
internal const val COLOR_MATRIX_ON_HDR_GRADE: String =
  "This effect adjusts an encoded SDR signal, and an export that keeps an HDR grade holds linear " +
    "light, where the same numbers mean something else. Export with HdrMode.ToneMapToSdr, or leave " +
    "the effect off."
