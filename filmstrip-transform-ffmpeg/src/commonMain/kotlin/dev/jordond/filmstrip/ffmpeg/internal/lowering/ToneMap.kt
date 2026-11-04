package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.GRADE_TO_FLOAT
import dev.jordond.filmstrip.effects.LINEAR
import dev.jordond.filmstrip.effects.LutTable
import dev.jordond.filmstrip.effects.MAX_1D_LEVEL
import dev.jordond.filmstrip.effects.PIXEL
import dev.jordond.filmstrip.effects.geq
import dev.jordond.filmstrip.effects.lut1d
import dev.jordond.filmstrip.effects.mixer
import dev.jordond.filmstrip.effects.signalFromGrade
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdr.KneePiece
import dev.jordond.filmstrip.transform.internal.hdr.PqKnee
import dev.jordond.filmstrip.transform.internal.hdr.RgbMatrix
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_BT2020_TO_BT709
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_HLG_EXPONENT
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_LUMA_WEIGHTS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_MASTERING_PEAK_NITS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_PQ_GAIN_SCALE
import dev.jordond.filmstrip.transform.internal.hdr.toneMapLinear
import dev.jordond.filmstrip.transform.internal.hdr.toneMapOetf

// The shared tone curve from SdrToneCurve.kt, lowered onto filter nodes. Every table and expression is generated from
// the curve's own pieces.

/**
 * The filters the tone map runs on, all of which ship in every ffmpeg build this backend supports.
 */
internal val TONE_MAP_FILTERS: List<String> = listOf("lut1d", "geq", "colorchannelmixer")

/**
 * Whether [toolchain] carries every filter in [TONE_MAP_FILTERS].
 */
internal fun canToneMap(toolchain: Toolchain): Boolean = TONE_MAP_FILTERS.all(toolchain::hasFilter)

/**
 * The grade stage, run on a clip that carries a [transfer] grade before anything else touches it.
 *
 * The grade's codes go onto float planes as BT.2020 signal, a lut1d decodes each channel to linear light, a geq
 * scales all three by the curve's one gain, colorchannelmixer converts to BT.709, and a last lut1d clamps and encodes
 * each channel. swscale then writes the result as BT.709 video range Y'CbCr, tagged as such, so every resolver
 * downstream is handed the SDR frame it was planned for. Every table travels as a sidecar.
 */
internal fun toneMapFragment(transfer: HdrTransfer): FilterFragment {
  val sidecars = mutableListOf<Sidecar>()
  val chain =
    buildList {
      addAll(GRADE_TO_FLOAT)
      addAll(sidecars.signalFromGrade())
      add(sidecars.lut1d(linearTable(transfer), LINEAR))
      add(geq(PIXEL.map { "($it)*(${gainExpression(transfer)})" }))
      add(mixer(TONE_MAP_BT2020_TO_BT709.rows))
      add(sidecars.lut1d(oetfTable, LINEAR))
      add(FilterNode("scale", "out_color_matrix" to SDR_TAG, "out_range" to "tv"))
      add(FilterNode("format", "pix_fmts" to "yuv420p"))
      add(
        FilterNode(
          "setparams",
          "color_primaries" to SDR_TAG,
          "color_trc" to SDR_TAG,
          "colorspace" to SDR_TAG,
          "range" to "tv",
        ),
      )
    }
  return FilterFragment(chain = chain, sidecars = sidecars.distinct())
}

/**
 * The tone curve's gain on a pixel of linear BT.2020 light, as a geq expression over the pixel's own channels.
 *
 * PQ reads the green channel through the knee's two Hermite pieces, HLG raises luminance by the curve's gamma less
 * one.
 */
internal fun gainExpression(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> {
      val nits = "ld(0)"
      val level = "ld(1)"
      val knee =
        with(PqKnee) {
          "if(lt($level,$x1),$level*$slope," +
            "if(lt($level,$x2),${lower.expression(level)},${upper.expression(level)}))"
        }
      "st(0,${PIXEL[1]}*$PQ_PEAK_NITS);st(1,clip($nits,0,$TONE_MAP_MASTERING_PEAK_NITS));" +
        "if(gt($nits,0),$knee/$nits,${PqKnee.slope})*$TONE_MAP_PQ_GAIN_SCALE"
    }
    HdrTransfer.Hlg -> {
      val weights = TONE_MAP_LUMA_WEIGHTS
      val luminance = "${weights.r}*${PIXEL[0]}+${weights.g}*${PIXEL[1]}+${weights.b}*${PIXEL[2]}"
      "pow(max($luminance,0),$TONE_MAP_HLG_EXPONENT)"
    }
  }

// The piece written as KneePiece.at writes it.
private fun KneePiece.expression(level: String): String {
  val t = "(($level-$start)/$width)"
  return "($from*(1+2*$t)+$startRise*$t)*(1-$t)*(1-$t)+($to*(3-2*$t)+$endRise*($t-1))*$t*$t"
}

// Each channel's signal to linear light on this transfer, one entry per sixteen-bit sample.
private fun linearTable(transfer: HdrTransfer): LutTable = linearTables.getValue(transfer)

private val linearTables: Map<HdrTransfer, LutTable> by lazy {
  HdrTransfer.entries.associateWith { transfer ->
    val column = DoubleArray(MAX_1D_LEVEL) { toneMapLinear(transfer, it.toDouble() / (MAX_1D_LEVEL - 1)) }
    LutTable(List(3) { column })
  }
}

private val RgbMatrix.rows: List<DoubleArray>
  get() = listOf(red, green, blue).map { doubleArrayOf(it.r, it.g, it.b) }

// Linear BT.709 SDR light to its signal. lut1d clamps its input to zero to one first, which is the curve's clamp.
private val oetfTable: LutTable by lazy {
  val column = DoubleArray(MAX_1D_LEVEL) { toneMapOetf(it.toDouble() / (MAX_1D_LEVEL - 1)) }
  LutTable(List(3) { column })
}

private const val SDR_TAG = "bt709"
