package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed

/**
 * What one tape format at one speed does to the picture, which [TapePictureParams.at] scales by intensity.
 *
 * The bandwidths are in megahertz. Each scaler multiplies the strength a VHS tape has, so 1 is VHS. The dropout
 * length is a fraction of the frame width.
 */
internal class PictureStock(
  val lumaMHz: Double,
  val chromaMHz: Double,
  val lumaNoise: Double = 1.0,
  val chromaNoise: Double = 1.0,
  val chromaShift: Double = 1.0,
  val dropoutChance: Double = 1.0,
  val dropoutLength: Double = VHS_DROPOUT_LENGTH,
)

// How long a VHS dropout of middling length runs, a fraction of the frame width.
private const val VHS_DROPOUT_LENGTH = 0.03

internal val TapeSpeed.pictureStock: PictureStock
  get() = PictureStock(lumaMHz, chromaMHz)

internal val BetaSpeed.pictureStock: PictureStock
  get() =
    when (this) {
      BetaSpeed.I -> betamax(lumaMHz = 2.5, chromaMHz = 0.32)
      BetaSpeed.II -> betamax(lumaMHz = 2.4, chromaMHz = 0.32)
      BetaSpeed.III -> betamax(lumaMHz = 2.0, chromaMHz = 0.30)
    }

internal val Hi8Speed.pictureStock: PictureStock
  get() =
    when (this) {
      Hi8Speed.SP -> hi8(lumaMHz = 4.0, chromaMHz = 0.32)
      Hi8Speed.LP -> hi8(lumaMHz = 3.4, chromaMHz = 0.30)
    }

private fun betamax(
  lumaMHz: Double,
  chromaMHz: Double,
): PictureStock = PictureStock(lumaMHz, chromaMHz, lumaNoise = 0.75, chromaNoise = 0.75, chromaShift = 0.75)

private fun hi8(
  lumaMHz: Double,
  chromaMHz: Double,
): PictureStock =
  PictureStock(
    lumaMHz,
    chromaMHz,
    lumaNoise = 1.25,
    chromaNoise = 1.0,
    chromaShift = 0.5,
    dropoutChance = 3.0,
    dropoutLength = 0.015,
  )
