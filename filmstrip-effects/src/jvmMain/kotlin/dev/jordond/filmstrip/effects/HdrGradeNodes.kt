package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.BT2020_CHROMA_SHIFT
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.media.HdrTransfer

// The nodes that carry an HDR grade's own Y'CbCr onto planar float R'G'B' and back. The matrix runs on core's constants
// both ways, in the tables and mixer rows of HdrGradeTables, and no format step applies one.

/**
 * The red, green and blue planes of the pixel geq is writing, as it reads them.
 *
 * An expression built on these reads each channel at the pixel's own position.
 */
internal val PIXEL: List<String> = listOf("r(X,Y)", "g(X,Y)", "b(X,Y)")

// geq's default bilinear sampling on ffmpeg 6.1.1 reads the last column and row of a frame from the ones next to them.
private val NEAREST_SAMPLE = "interpolation" to "nearest"

/**
 * The nodes that carry the grade's ten-bit Y'CbCr onto planar float, each plane holding one code.
 *
 * The frame is tagged limited range, since the grade's codes are video range whatever the frame is tagged with, and a
 * [PlaneLanes] node carries luma, Cb and Cr onto float planes one plane at a time. [signalFromGrade] reads the R'G'B'
 * signal off the planes they leave.
 */
internal val GRADE_TO_FLOAT: List<FilterNode> =
  listOf(FilterNode("setrange", "range" to "limited"), PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB))

/**
 * The nodes that read the planes [GRADE_TO_FLOAT] leaves as each pixel's red, green and blue signal, adding the tables
 * they read to this list of sidecars.
 *
 * [HdrGradeTables.CODE_CENTRES] picks out each plane's code and [HdrGradeTables.DECODE_ROWS] mixes them into R'G'B'.
 * lut1d clamps whatever reads the signal next to the range zero to one, which is where a code outside video range
 * decodes past.
 */
internal fun MutableList<Sidecar>.signalFromGrade(): List<FilterNode> =
  listOf(lut1d(HdrGradeTables.CODE_CENTRES, NEAREST), mixer(HdrGradeTables.DECODE_ROWS))

/**
 * The nodes that write each pixel's red, green and blue signal, shifted by [BT2020_CHROMA_SHIFT], as the grade's
 * ten-bit 4:2:0 Y'CbCr, adding the tables they read to this list of sidecars.
 *
 * [HdrGradeTables.ENCODE_ROWS] mixes the signal into each code's distance from black, [HdrGradeTables.CODE_STEPS]
 * rounds that to the whole code, and [CODES_TO_GRADE] carries the codes into the grade.
 */
internal fun MutableList<Sidecar>.shiftedSignalToGrade(): List<FilterNode> =
  listOf(mixer(HdrGradeTables.ENCODE_ROWS), lut1d(HdrGradeTables.CODE_STEPS, NEAREST)) + CODES_TO_GRADE

/**
 * A lut1d that reads [table] with [interp] sampling, adding the table's sidecar to this list.
 *
 * [interp] is [NEAREST] or [LINEAR].
 */
internal fun MutableList<Sidecar>.lut1d(
  table: LutTable,
  interp: String,
): FilterNode {
  add(table.sidecar)
  return FilterNode("lut1d", "file" to table.sidecar.placeholder, "interp" to interp)
}

/**
 * The nodes that write whole ten-bit codes on planar float, each held as its sixteen-bit value as a fraction of full
 * scale with luma on green, Cb on blue and Cr on red, as the grade's ten-bit 4:2:0 Y'CbCr.
 *
 * The first `format` carries the planes onto sixteen bits, mergeplanes relabels them as Y'CbCr, and the last `format`
 * rounds them back to ten bits and resamples the chroma to 4:2:0. The float to sixteen-bit step moves each plane by up
 * to 25 of 65535, about four tenths of a ten-bit code, on the codes a BT.2020 signal reaches, which the rounding back
 * to ten bits takes out.
 */
internal val CODES_TO_GRADE: List<FilterNode> =
  // swscale pulls the planes of a three-plane float to sixteen-bit step against each other, and ffmpeg has no exact way
  // round it: a gray float lane lands up to 5375 of 65535 off on 9.0.2, which reads it as video range and writes gray16
  // as full whatever the tag, and the Mac's build has no zscale. So every code is whole before the step, and the pull
  // staying under half a ten-bit code lets the rounding step back to ten bits land each code where it was worked out.
  listOf(
    FilterNode("format", "pix_fmts" to WIDE_RGB),
    FilterNode("mergeplanes", "map1p" to "1", "map2p" to "2", "format" to WIDE_YUV),
    FilterNode("format", "pix_fmts" to GRADE_YUV),
  )

/**
 * The fragment a color [matrix] on a kept [transfer] grade lowers to, run through the tables of [HdrGradeTables].
 *
 * [GRADE_TO_FLOAT] carries the grade's codes onto float planes, lut1d and colorchannelmixer run each table and set of
 * rows in turn, and [CODES_TO_GRADE] writes the whole codes the last table rounds to. Every table travels as a sidecar.
 */
internal fun gradedOnTables(
  matrix: ColorMatrix,
  transfer: HdrTransfer,
): FilterFragment {
  val tables = HdrGradeTables(matrix, transfer)
  val sidecars = mutableListOf<Sidecar>()
  val chain =
    buildList {
      addAll(GRADE_TO_FLOAT)
      addAll(sidecars.signalFromGrade())
      tables.decodeWarp?.let { add(sidecars.lut1d(it, LINEAR)) }
      add(sidecars.lut1d(tables.curve, LINEAR))
      tables.mix?.let { mix ->
        if (mix.bias == null) {
          add(mixer(mix.rows))
        } else {
          sidecars += mix.sidecar
          add(FilterNode("lut3d", "file" to mix.sidecar.placeholder))
        }
      }
      add(sidecars.lut1d(tables.encodeWarp, LINEAR))
      add(sidecars.lut1d(tables.encode, LINEAR))
      addAll(sidecars.shiftedSignalToGrade())
    }
  return FilterFragment(chain = chain, sidecars = sidecars.distinct())
}

// A colorchannelmixer that writes each of red, green and blue as its row of red, green and blue in.
private fun mixer(rows: List<DoubleArray>): FilterNode =
  FilterNode(
    "colorchannelmixer",
    MIXER_KEYS.flatMapIndexed { out, keys ->
      keys.mapIndexed { from, key -> FilterArgument(key, rows[out][from].toString()) }
    },
  )

private val MIXER_KEYS = listOf(listOf("rr", "rg", "rb"), listOf("gr", "gg", "gb"), listOf("br", "bg", "bb"))

/**
 * lut1d's sampling that reads the entry nearest its input.
 *
 * A table read this way picks out one entry per sample, so it suits a table with an entry for every sample.
 */
internal const val NEAREST: String = "nearest"

/**
 * lut1d's sampling that reads the line between the entries either side of its input.
 *
 * A curve read this way is piecewise linear between its entries.
 */
internal const val LINEAR: String = "linear"

/**
 * A geq on planar float RGB that writes [rgb] to the red, green and blue planes, each pixel read at its own position.
 *
 * It samples the nearest pixel, so the last column and row read their own values.
 */
internal fun geq(rgb: List<String>): FilterNode =
  FilterNode("geq", "r" to rgb[0], "g" to rgb[1], "b" to rgb[2], NEAREST_SAMPLE)

/**
 * Planar float RGB, which geq and maskedmerge run on, so a signal or linear light is held without banding.
 *
 * It is the lanes' opaque target, so a grade's planes are carried straight onto it.
 */
internal val FLOAT_RGB: String get() = PlaneLanes.OPAQUE.format

// The grade's own YUV: BT.2020 non-constant luminance, limited range, ten-bit 4:2:0.
private const val GRADE_YUV = "yuv420p10le"

// The grade's codes at sixteen bits and 4:4:4, and planar RGB at the same depth, which mergeplanes swaps between.
private const val WIDE_YUV = "yuv444p16le"
private const val WIDE_RGB = "gbrp16le"
