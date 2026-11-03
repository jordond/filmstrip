package dev.jordond.filmstrip.still

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size

/**
 * An encoded still held in memory, or why there is none.
 *
 * A format this target cannot write is a [Failure] carrying
 * [FilmstripError.UnsupportedStillFormat], so branch on it rather than reading an empty buffer as an
 * encode that worked.
 */
public sealed interface StillBytes {
  /**
   * The frame was encoded.
   *
   * @property bytes The encoded image, ready to write or upload.
   * @property size The size the image was encoded at, which honours [StillSpec.heightPx].
   * @property format The format [bytes] holds.
   */
  @Poko
  public class Success(
    @Poko.ReadArrayContent public val bytes: ByteArray,
    public val size: Size,
    public val format: StillFormat,
  ) : StillBytes

  /**
   * The frame could not be encoded.
   *
   * @property error Why the encode failed.
   */
  @Poko
  public class Failure(
    public val error: FilmstripError,
  ) : StillBytes
}
