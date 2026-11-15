package dev.jordond.filmstrip.effects.filters.tape

/**
 * Draws [TapePictureExpressions.grainMap], [TapePictureExpressions.dropMap], [TapePictureExpressions.snowMap] and
 * [TapePictureExpressions.snowMask] straight from [TapePictureSignal], at the codes those expressions write, as whole
 * 4:2:0 frames [width] by [height]. The snow and its weight share one frame, side by side.
 *
 * A pixel's position reads the way a `geq` reads it, its own plane's fraction across and its own plane's row.
 */
internal class TapePicturePlanes(
  private val seed: Int,
  private val params: TapePictureParams,
  private val width: Int,
  private val height: Int,
  private val lumaGrainScale: Double,
  private val chromaGrainScale: Double,
) {
  private val chromaWidth = (width + 1) / 2
  private val chromaHeight = (height + 1) / 2
  private val lumaAcross = DoubleArray(width) { (it + 0.5) / width }
  private val chromaAcross = DoubleArray(chromaWidth) { (it + 0.5) / chromaWidth }

  /**
   * The added noise at [tick], as an offset from mid scale in each plane.
   */
  fun grainAt(tick: Int): ByteArray {
    val frame = newFrame()
    var at = 0
    for (y in 0 until height) {
      val row = TapePictureSignal.virtualRow(y, height)
      val band = TapePictureSignal.trackingWeight(seed, tick, row, params)
      for (x in lumaAcross) {
        var grain = params.lumaNoise * TapePictureSignal.streak(seed, PASS_LUMA_NOISE, tick, row, x, STREAK)
        if (band > 0.0) {
          grain +=
            params.trackingNoise * band *
            TapePictureSignal.streak(seed, PASS_TRACK_NOISE, tick, row, x, TRACK_NOISE_CELL)
        }
        frame[at++] = (MID_CODE + lumaGrainScale * LUMA_CODES * grain).toInt().toByte()
      }
    }
    at = chromaGrain(frame, at, PASS_CB_NOISE, tick)
    chromaGrain(frame, at, PASS_CR_NOISE, tick)
    return frame
  }

  /**
   * The dropouts at [tick], in luma, and what is left of chroma in the chroma planes.
   */
  fun dropAt(tick: Int): ByteArray {
    val frame = newFrame()
    var at = 0
    for (y in 0 until height) {
      val row = TapePictureSignal.virtualRow(y, height)
      for (x in lumaAcross) {
        frame[at++] = (ROUNDING + FULL_SCALE * TapePictureSignal.dropout(seed, tick, row, x, params)).toInt().toByte()
      }
    }
    repeat(2) {
      for (y in 0 until chromaHeight) {
        val row = TapePictureSignal.virtualRow(y, chromaHeight)
        val band = TapePictureSignal.trackingWeight(seed, tick, row, params)
        val kept = (1.0 - TRACK_CHROMA_LOSS * band) * (1.0 - params.trackingSnow * band)
        for (x in chromaAcross) {
          val gain = kept * (1.0 - TapePictureSignal.dropout(seed, tick, row, x, params))
          frame[at++] = (ROUNDING + FULL_SCALE * gain).toInt().toByte()
        }
      }
    }
    return frame
  }

  /**
   * The snow at [tick] with its weight beside it, as one frame [weightWidth] samples wider than the raster.
   *
   * Each luma row holds the snow as the lifted code it lands on times its weight, so it adds onto a frame the weight
   * has been taken off, 0 on a row the band misses. The [weightWidth] samples after it hold the row's weight, full
   * scale at 1. The chroma planes are left at mid scale, since [dropAt] takes chroma toward zero.
   */
  fun snowAt(
    tick: Int,
    weightWidth: Int,
  ): ByteArray {
    val wide = width + weightWidth
    val frame = ByteArray(wide * height + 2 * ((wide + 1) / 2) * chromaHeight)
    for (y in 0 until height) {
      val row = TapePictureSignal.virtualRow(y, height)
      val weight = params.trackingSnow * TapePictureSignal.trackingWeight(seed, tick, row, params)
      val at = y * wide
      if (weight > 0.0) {
        lumaAcross.forEachIndexed { column, x ->
          val snow = params.lift + TapePictureSignal.snowValue(seed, tick, row, x) * (1.0 - params.lift)
          frame[at + column] = (ROUNDING + weight * (LUMA_BLACK + LUMA_CODES * snow)).toInt().toByte()
        }
      }
      frame.fill((ROUNDING + FULL_SCALE * weight).toInt().toByte(), at + width, at + wide)
    }
    frame.fill(MID_SCALE.toByte(), wide * height, frame.size)
    return frame
  }

  private fun chromaGrain(
    frame: ByteArray,
    from: Int,
    pass: Int,
    tick: Int,
  ): Int {
    var at = from
    val cell = STREAK * CHROMA_COARSENESS
    for (y in 0 until chromaHeight) {
      val row = TapePictureSignal.virtualRow(y, chromaHeight) / CHROMA_NOISE_ROWS
      for (x in chromaAcross) {
        val streak = TapePictureSignal.streak(seed, pass, tick, row, x, cell)
        frame[at++] = (MID_CODE + chromaGrainScale * CHROMA_CODES * params.chromaNoise * streak).toInt().toByte()
      }
    }
    return at
  }

  private fun newFrame() = ByteArray(width * height + 2 * chromaWidth * chromaHeight)
}

private const val ROUNDING = 0.5
private const val MID_CODE = MID_SCALE + ROUNDING
