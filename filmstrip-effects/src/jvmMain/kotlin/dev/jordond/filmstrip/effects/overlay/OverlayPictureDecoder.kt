package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import java.awt.image.BufferedImage

/**
 * Reads an overlay picture the JDK cannot, with the toolchain the backend draws pictures with.
 *
 * An animated overlay is sized against the picture's own pixels, and one on an HDR grade is decoded in this process so
 * its colours can be carried onto the grade's signal. The JDK is asked first, and a picture it has no reader for comes
 * here, so both take every picture the backend's own toolchain opens on an SDR grade.
 */
@InternalFilmstripApi
public interface OverlayPictureDecoder {
  /**
   * Decodes the first frame of [image] at the picture's own size.
   *
   * The pixels come back as straight sRGB ARGB, converted the way the toolchain converts them for an SDR overlay and
   * with no colour management on the way.
   *
   * @return The picture, or [OverlayPictureRead.Refused] with the toolchain's reason when it cannot decode it either.
   */
  public fun decode(image: ImageSource): OverlayPictureRead<BufferedImage>

  /**
   * Reads the pixel size of [image]'s first frame without decoding its pixels.
   *
   * @return The size, or [OverlayPictureRead.Refused] with the toolchain's reason when it cannot read the picture
   * either.
   */
  public fun measure(image: ImageSource): OverlayPictureRead<Size>
}

/**
 * What an [OverlayPictureDecoder] got out of a picture, either the value it read or the reason it could not.
 */
@InternalFilmstripApi
public sealed interface OverlayPictureRead<out T> {
  /**
   * The picture was read.
   *
   * @property value What was read out of it.
   */
  @InternalFilmstripApi
  public class Read<out T>(
    public val value: T,
  ) : OverlayPictureRead<T>

  /**
   * The picture could not be read.
   *
   * @property reason The toolchain's own account of why, as it wrote it.
   */
  @InternalFilmstripApi
  public class Refused(
    public val reason: String,
  ) : OverlayPictureRead<Nothing>
}
