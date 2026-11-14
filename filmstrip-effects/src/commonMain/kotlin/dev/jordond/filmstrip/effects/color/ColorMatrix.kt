package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.hlgSignalFromSdrSignal
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.peakNits
import dev.jordond.filmstrip.color.sdrSignalCeilingDouble
import dev.jordond.filmstrip.color.sdrSignalFromHlgSignal
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.abs

/**
 * Recombine the color channels of every pixel through a 3x3 matrix and an offset.
 *
 * Each output channel is a weighted sum of the three input channels plus a bias, so red comes out as
 * `rr * r + rg * g + rb * b + rBias` and the other two rows read the same way. Channels are the
 * encoded signal in the range zero to one, the same domain [Brightness] multiplies, and the result
 * is clamped back into that range once the whole matrix has been applied. Alpha is never touched.
 *
 * Every other color effect in this package is one of these under the hood, and a run of them is
 * folded into a single matrix before any backend clamps, so an effect that pushes a channel past
 * white and one that pulls it back again cancel out rather than saturating in between.
 *
 * On an export that keeps an HDR grade the frame holds light rather than a signal, so the light is
 * read as the signal an SDR display at reference white would have been fed, the matrix runs on that
 * as written, and the result goes back to light. The ceiling is the format's peak rather than white,
 * so a channel pushed past `1f` keeps going where an SDR export saturates, and a channel pulled
 * below zero is black on both. Only a bias depends on where white sits.
 *
 * The defaults are the identity, so a caller names only the entries that change.
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
 * @throws IllegalArgumentException when any entry is not a finite number.
 */
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
  init {
    require(entries().all { it.isFinite() }) { "Every color matrix entry has to be a finite number." }
  }

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
 * Recombine the color channels through a matrix. See [ColorMatrix] for how the entries are read.
 */
public fun EffectsBuilder.colorMatrix(matrix: ColorMatrix): EffectsBuilder = add(matrix)

private fun ColorMatrix.entries(): List<Float> = listOf(rr, rg, rb, rBias, gr, gg, gb, gBias, br, bg, bb, bBias)

/**
 * Whether this matrix changes nothing.
 *
 * Each entry is compared to the identity's within a tolerance, since a matrix built from trig, like a whole turn of
 * hue, carries cross terms around `1e-16` where it means zero.
 */
@InternalFilmstripApi
public val ColorMatrix.isIdentity: Boolean
  get() =
    isDiagonal &&
      rr.isNear(1f) &&
      gg.isNear(1f) &&
      bb.isNear(1f) &&
      rBias.isNear(0f) &&
      gBias.isNear(0f) &&
      bBias.isNear(0f)

/**
 * The color effects in this chain that change nothing, in chain order.
 *
 * The chain is sorted into canonical order and folded the way a backend lowers it, so a run whose matrices multiply
 * out to the identity counts as a whole, like a brightness of two followed by one of a half. An effect that is not a
 * color matrix is never in the answer.
 *
 * @return every authored effect whose folded run leaves the frame as it was.
 */
@InternalFilmstripApi
public fun List<EffectSpec>.identityColorRuns(): List<EffectSpec> =
  canonicalOrder(this)
    .fusedColorMatrices()
    .filter { colorMatrixOf(it.spec)?.isIdentity == true }
    .flatMap { it.sources }

/**
 * Whether every output channel reads from its own input channel alone, so the matrix is three
 * independent per-channel lines rather than a mix.
 */
internal val ColorMatrix.isDiagonal: Boolean
  get() = rg.isNear(0f) && rb.isNear(0f) && gr.isNear(0f) && gb.isNear(0f) && br.isNear(0f) && bg.isNear(0f)

/**
 * The factor this matrix scales all three channels by, or null when it is not that shape.
 *
 * A uniform scale with no bias and no mixing commutes with a change of primaries and with the
 * transfer function either side of a matrix, so a backend holding linear light multiplies the light
 * by a power of this rather than moving the frame into the encoded signal and back. Which lowering a
 * color effect takes is this shape rather than which type wrote it, so a run that folded to a
 * [ColorMatrix] takes the same one a [Brightness] does.
 */
internal val ColorMatrix.uniformScale: Float?
  get() =
    rr.takeIf {
      isDiagonal &&
        rr.isNear(gg) &&
        rr.isNear(bb) &&
        rBias.isNear(0f) &&
        gBias.isNear(0f) &&
        bBias.isNear(0f)
    }

// Both predicates decide which lowering a backend takes, and a matrix that came out of a trig table
// carries cross terms around 1e-16 where it means zero. Reading those as a mix sends a whole turn of
// hue, which is the identity, through a lookup table that shifts code values.
private fun Float.isNear(value: Float): Boolean = abs(this - value) <= ENTRY_EPSILON

private const val ENTRY_EPSILON = 1e-6f

/**
 * The matrix that applies this one and then [next], as a single matrix.
 *
 * Running `next` on the output of `this` is `next.M * (this.M * v + this.b) + next.b`, so the
 * combined matrix is the product and the combined bias is `this.b` carried through `next.M` with
 * `next.b` added.
 *
 * Every backend holds a matrix as 32-bit floats, so an entry whose product runs past the largest one is held at it
 * rather than becoming an infinity. Each multiply and each sum is held that way, so a long run of large factors
 * folds to a matrix every backend can take and never to a NaN.
 */
@InternalFilmstripApi
public fun ColorMatrix.then(next: ColorMatrix): ColorMatrix =
  ColorMatrix(
    rr = dot(next.rr, rr, next.rg, gr, next.rb, br),
    rg = dot(next.rr, rg, next.rg, gg, next.rb, bg),
    rb = dot(next.rr, rb, next.rg, gb, next.rb, bb),
    rBias = dot(next.rr, rBias, next.rg, gBias, next.rb, bBias) heldPlus next.rBias,
    gr = dot(next.gr, rr, next.gg, gr, next.gb, br),
    gg = dot(next.gr, rg, next.gg, gg, next.gb, bg),
    gb = dot(next.gr, rb, next.gg, gb, next.gb, bb),
    gBias = dot(next.gr, rBias, next.gg, gBias, next.gb, bBias) heldPlus next.gBias,
    br = dot(next.br, rr, next.bg, gr, next.bb, br),
    bg = dot(next.br, rg, next.bg, gg, next.bb, bg),
    bb = dot(next.br, rb, next.bg, gb, next.bb, bb),
    bBias = dot(next.br, rBias, next.bg, gBias, next.bb, bBias) heldPlus next.bBias,
  )

private fun dot(
  a1: Float,
  b1: Float,
  a2: Float,
  b2: Float,
  a3: Float,
  b3: Float,
): Float = (a1 heldTimes b1) heldPlus (a2 heldTimes b2) heldPlus (a3 heldTimes b3)

/**
 * The product of two finite floats, held at the largest finite float either side of zero.
 *
 * Both operands are finite, so the product is finite or an infinity and never a NaN, and the hold keeps it finite.
 */
internal infix fun Float.heldTimes(other: Float): Float = (this * other).coerceIn(-Float.MAX_VALUE, Float.MAX_VALUE)

// Both operands are finite, so the sum is finite or an infinity of one sign and never a NaN.
private infix fun Float.heldPlus(other: Float): Float = (this + other).coerceIn(-Float.MAX_VALUE, Float.MAX_VALUE)

/**
 * What this matrix writes for an encoded pixel, clamped to the range a frame can hold.
 *
 * This is the reference every backend is measured against.
 *
 * @return red, green and blue in the range zero to one.
 */
@InternalFilmstripApi
public fun ColorMatrix.transform(
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
 * What this matrix writes for a pixel of display light on a kept HDR grade, in cd/m2.
 *
 * The light is read as the SDR signal of [sdrSignalFromNits], the matrix runs on that as written, a
 * negative channel is floored at black, and the result goes back to light and is clamped at the
 * transfer's peak. This is the reference every backend that keeps a grade is measured against.
 *
 * @param transfer The transfer the grade is written in, which sets the ceiling.
 * @return red, green and blue in cd/m2, between zero and the transfer's peak.
 */
@InternalFilmstripApi
public fun ColorMatrix.transformNits(
  red: Float,
  green: Float,
  blue: Float,
  transfer: HdrTransfer,
): FloatArray {
  val r = sdrSignalFromNits(red)
  val g = sdrSignalFromNits(green)
  val b = sdrSignalFromNits(blue)
  val peak = transfer.peakNits

  return floatArrayOf(
    nitsFromSdrSignal(rr * r + rg * g + rb * b + rBias).coerceAtMost(peak),
    nitsFromSdrSignal(gr * r + gg * g + gb * b + gBias).coerceAtMost(peak),
    nitsFromSdrSignal(br * r + bg * g + bb * b + bBias).coerceAtMost(peak),
  )
}

/**
 * What this matrix writes for a pixel of HLG signal on a kept grade, as HLG signal.
 *
 * Each channel is read as the SDR signal of [sdrSignalFromHlgSignal], the matrix runs on that as written, and the
 * result is clamped between black and HLG's [sdrSignalCeilingDouble] and taken back through [hlgSignalFromSdrSignal].
 * It is the HLG reading of [transformNits], held in codes rather than light, and every backend that grades HLG is
 * measured against it.
 *
 * @return red, green and blue HLG signal, each between zero and one.
 */
@InternalFilmstripApi
public fun ColorMatrix.transformHlgCodes(
  red: Float,
  green: Float,
  blue: Float,
): FloatArray {
  val r = sdrSignalFromHlgSignal(red.toDouble())
  val g = sdrSignalFromHlgSignal(green.toDouble())
  val b = sdrSignalFromHlgSignal(blue.toDouble())
  val ceiling = HdrTransfer.Hlg.sdrSignalCeilingDouble
  val rows =
    doubleArrayOf(
      rr * r + rg * g + rb * b + rBias,
      gr * r + gg * g + gb * b + gBias,
      br * r + bg * g + bb * b + bBias,
    )

  return FloatArray(rows.size) { hlgSignalFromSdrSignal(rows[it].coerceIn(0.0, ceiling)).toFloat() }
}

/**
 * This matrix as the sixteen floats a GL `mat4` uniform takes, applied to `vec4(rgb, 1)`.
 *
 * Column-major, so the three rows land at a stride of four and the bias is the fourth column. The
 * fourth row passes the homogeneous one through, and a shader that keeps its own alpha never reads
 * it.
 */
@InternalFilmstripApi
@Suppress("ktlint:standard:argument-list-wrapping")
public fun ColorMatrix.toColumnMajor4x4(): FloatArray =
  floatArrayOf(
    rr, gr, br, 0f,
    rg, gg, bg, 0f,
    rb, gb, bb, 0f,
    rBias, gBias, bBias, 1f,
  )

/**
 * The matrix the sixteen floats of [toColumnMajor4x4] spell, or null for an array that is not a
 * `mat4`.
 *
 * A backend folding the uniforms of a chain it did not write reaches for this rather than the
 * throwing form, since a foreign pass is free to spell a uniform of that name any way it likes, an entry that is not
 * a finite number included.
 *
 * @param columns Sixteen floats, column-major, as [toColumnMajor4x4] lays them out.
 */
@InternalFilmstripApi
public fun colorMatrixOfColumnMajor4x4OrNull(columns: FloatArray): ColorMatrix? =
  if (columns.size == MAT4_SIZE && columns.all { it.isFinite() }) colorMatrixOfColumnMajor4x4(columns) else null

/**
 * The matrix the sixteen floats of [toColumnMajor4x4] spell.
 *
 * Reads the three rows and the bias back out of the column-major layout. The fourth row is not
 * read, since nothing this side writes anything but the homogeneous one there.
 *
 * @param columns Sixteen floats, column-major, as [toColumnMajor4x4] lays them out.
 */
internal fun colorMatrixOfColumnMajor4x4(columns: FloatArray): ColorMatrix {
  require(columns.size == MAT4_SIZE) { "A mat4 holds $MAT4_SIZE floats, not ${columns.size}." }

  return ColorMatrix(
    rr = columns[0],
    gr = columns[1],
    br = columns[2],
    rg = columns[4],
    gg = columns[5],
    bg = columns[6],
    rb = columns[8],
    gb = columns[9],
    bb = columns[10],
    rBias = columns[12],
    gBias = columns[13],
    bBias = columns[14],
  )
}

private const val MAT4_SIZE = 16

/**
 * A matrix that scales each channel on its own, with no bias and no mixing.
 */
internal fun scaleMatrix(
  red: Float,
  green: Float,
  blue: Float,
): ColorMatrix = ColorMatrix(rr = red, gg = green, bb = blue)
