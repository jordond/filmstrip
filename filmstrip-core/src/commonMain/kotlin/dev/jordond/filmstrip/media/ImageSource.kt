package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A still image, either composited over video as a watermark or shown on the timeline in its own
 * right.
 *
 * A reference to an image rather than decoded pixels, so a composition holding one stays
 * serialisable. The backend decodes it when it renders. Construct one through the companion
 * factories, which are also what a Swift caller uses.
 */
@Serializable
public sealed interface ImageSource {
  /**
   * An image file on disk.
   *
   * @property path A filesystem path, readable by the calling process.
   */
  @Serializable
  @SerialName("path")
  @Poko
  public class Path(
    public val path: String,
  ) : ImageSource

  /**
   * A platform URI or URL string.
   *
   * @property uri An `android.net.Uri` string on Android, or an `NSURL` absolute string on Apple
   *   platforms.
   */
  @Serializable
  @SerialName("uri")
  @Poko
  public class Uri(
    public val uri: String,
  ) : ImageSource

  /**
   * Encoded image bytes, in PNG, JPEG or WebP.
   *
   * @property bytes The encoded image, still to be decoded by the backend.
   */
  @Serializable
  @SerialName("bytes")
  @Poko
  public class Bytes(
    @Poko.ReadArrayContent public val bytes: ByteArray,
  ) : ImageSource

  public companion object {
    /**
     * An image read from [path].
     */
    public fun of(path: String): ImageSource = Path(path)

    /**
     * An image read from a platform URI or URL string.
     */
    public fun ofUri(uri: String): ImageSource = Uri(uri)

    /**
     * An image decoded from encoded [bytes].
     */
    public fun ofBytes(bytes: ByteArray): ImageSource = Bytes(bytes)
  }
}

/**
 * Renders an image source as a short string for an error message.
 */
@InternalFilmstripApi
public fun ImageSource.describe(): String =
  when (this) {
    is ImageSource.Path -> path
    is ImageSource.Uri -> uri
    is ImageSource.Bytes -> "bytes[${bytes.size}]"
  }
