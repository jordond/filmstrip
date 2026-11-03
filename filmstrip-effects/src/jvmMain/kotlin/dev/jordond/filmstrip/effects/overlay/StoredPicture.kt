package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size

/**
 * An overlay picture as the samples it stores and the color description that defines them.
 *
 * @property samples The samples before any color conversion, one texel per pixel in rows, laid out as the picture is
 * shown.
 * @property size The picture's size in pixels as it is shown.
 * @property color How the picture describes its color, or null when it is untagged.
 * @property limits Each way the backend's decoder read the picture short of what its file stores.
 */
internal class StoredPicture(
  val samples: StoredSamples,
  val size: Size,
  val color: PictureColor?,
  val limits: List<Degradation> = emptyList(),
)

/**
 * What reading an overlay picture's stored samples came to.
 *
 * [storedPicture] answers with one of these.
 */
internal sealed interface StoredRead {
  /**
   * The picture was read.
   *
   * @property picture Its samples, size and color description.
   */
  class Read(
    val picture: StoredPicture,
  ) : StoredRead

  /**
   * The backend's decoder could not decode the picture.
   *
   * @property reason The toolchain's own account of why.
   */
  class Undecoded(
    val reason: String,
  ) : StoredRead

  /**
   * The backend's decoder did not decode the picture, since its color is described in a way the backend does not
   * draw, or it is an AVIF or HEIC whose `irot`, `imir` and `clap` could not be dropped ahead of the decoder, or whose
   * decode holds no rectangle its `clap` keeps.
   *
   * @property reason What the description names and why it is not drawn, or core's sentence for the drop or the crop.
   */
  class Undrawn(
    val reason: String,
  ) : StoredRead

  /**
   * There is no decoder and the JDK could not read the picture.
   */
  data object Unread : StoredRead

  /**
   * This process could not read the picture's bytes, so its container's color description is out of reach, and the
   * decode carried a description of its own that may or may not be the container's.
   */
  data object Unseen : StoredRead
}
