package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.media.ImageSource
import kotlin.concurrent.Volatile

/**
 * What one lowering read of each overlay picture named by path or URI, so every effect naming a picture draws one read.
 *
 * [readFor] replaces the whole set ahead of a lowering and a picture is read again every time, so nothing carries into
 * a later lowering. A picture the set does not hold, one resolved with no lowering around it, is read on the spot.
 *
 * @param T What a read of one picture gives, its bytes or why there are none.
 * @param read Reads one picture.
 */
internal class LoweringPictures<T : Any>(
  private val read: (ImageSource) -> T,
) {
  @Volatile
  private var held: Map<ImageSource, T> = emptyMap()

  /**
   * Reads every picture [composition] names by path or URI, and holds those reads in place of the last ones.
   */
  fun readFor(composition: EditComposition) {
    held =
      overlayEffectsOf(composition)
        .filterIsInstance<ImageOverlay>()
        .map { it.image }
        .filterNot { it is ImageSource.Bytes }
        .distinct()
        .associateWith(read)
  }

  /**
   * The read of [source] this lowering holds, or a new one when it holds none.
   */
  operator fun get(source: ImageSource): T = held[source] ?: read(source)
}

/**
 * One read of a picture, for a backend whose read says only what it found.
 *
 * @property bytes The picture's encoded bytes, or null when they could not be read.
 * @property refusal Why the bytes were turned away, or null when they were not, which [pictureTooLargeToRead] gives
 * for bytes past the ceiling.
 */
internal class HeldBytes(
  val bytes: ByteArray?,
  val refusal: String? = null,
)

/**
 * The effects [composition] holds itself, each of its tracks holds and each of its clips holds.
 */
internal fun overlayEffectsOf(composition: EditComposition): List<EffectSpec> =
  composition.effects +
    composition.tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } }
