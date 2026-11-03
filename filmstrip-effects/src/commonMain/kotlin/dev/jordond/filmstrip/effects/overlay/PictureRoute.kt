package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.YcbcrEvaluator
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.UnturnedHeif

/**
 * How an overlay raster hands an AVIF or HEIC to its platform's decoder so the samples land the way core reads them.
 *
 * Apple works it out for ImageIO and the browser for Chrome, each from the matrices its own decoder reads.
 */
internal sealed interface PictureRoute {
  /**
   * The picture is handed to the decoder as [bytes], which it decodes through the matrix core reads it with, or through
   * the nearest one it reads when [limits] says so.
   *
   * @property bytes What the decoder is handed.
   * @property limits Empty when the decoder lands the picture exactly, and what it gave up otherwise.
   */
  class Decoded(
    val bytes: ByteArray,
    val limits: List<Degradation> = emptyList(),
  ) : PictureRoute

  /**
   * The picture is handed to the decoder as [bytes], which state the identity matrix at full range, so it hands out the
   * stored luma, blue difference and red difference samples as green, blue and red. [evaluator] then turns each texel
   * into color the way core reads the picture.
   *
   * @property bytes What the decoder is handed.
   * @property evaluator Decodes the samples at the picture's own depth and range.
   * @property bitDepth The depth of the picture's samples, and of the ones the decoder hands out.
   */
  class Evaluated(
    val bytes: ByteArray,
    val evaluator: YcbcrEvaluator,
    val bitDepth: Int,
  ) : PictureRoute

  /**
   * The picture is refused before the decoder sees it.
   *
   * @property message What the overlay reports.
   */
  class Refused(
    val message: String,
  ) : PictureRoute
}

/**
 * What [landed] makes of the bytes [rewrite] hands over, the refusal of an `irot`, `imir` and `clap` drop that is
 * refused, or [notRestated] for a restate that does not apply.
 */
internal inline fun routed(
  rewrite: RestatedHeif,
  notRestated: PictureRoute.Refused,
  landed: (ByteArray) -> PictureRoute,
): PictureRoute =
  when (rewrite) {
    is UnturnedHeif.Refused -> rewrite.refusal()
    is RestatedHeif.Unrestated -> notRestated
    is RestatedHeif.Restated -> landed(rewrite.bytes)
  }

/**
 * The picture refused for an `irot`, `imir` and `clap` drop that is refused, with the sentence core gives for it.
 */
internal fun UnturnedHeif.Refused.refusal(): PictureRoute.Refused = PictureRoute.Refused(message)
