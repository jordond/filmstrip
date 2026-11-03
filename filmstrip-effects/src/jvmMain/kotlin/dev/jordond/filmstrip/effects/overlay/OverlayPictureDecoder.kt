package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColorFromDecodedPng
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Reads an overlay picture with the toolchain the backend draws pictures with.
 *
 * Every picture the catalogue decodes in this process comes here when the backend has a decoder, so its texels are the
 * ones the toolchain draws, turned by no orientation tag but a JPEG XL's, which its decoder applies itself. The caller
 * lays them out by the orientation the picture's bytes carry. An animated overlay is sized here too, so its size and
 * its texels agree. An overlay on an HDR grade is decoded so its colors can be carried onto the grade's signal, and a
 * color-tagged picture is decoded on an SDR grade too, so its colors can be drawn the way its description defines
 * them, as is every AVIF and HEIC. The JDK reads pictures only for a catalogue built without a decoder.
 */
@InternalFilmstripApi
public interface OverlayPictureDecoder {
  /**
   * Decodes the first frame of [image] at the picture's own size, with the color description its decode carries.
   *
   * The samples come back as the picture stores them, turned by no orientation tag, converted to RGB the way the
   * toolchain converts them for an overlay and with no color management on the way. A JPEG XL is the exception and
   * comes back laid out by its codestream's orientation, since its decoder applies that whatever it is asked. The
   * description is the one the toolchain's decoder found, which is how a format that keeps it inside its codestream,
   * such as JPEG XL, reaches the caller.
   *
   * @return The picture, [OverlayPictureDecode.Undrawn] when its color is described in a way the toolchain does not
   * draw or it is an AVIF or HEIC whose `irot`, `imir` and `clap` cannot be dropped, or [OverlayPictureRead.Refused]
   * with the toolchain's reason when it cannot decode it.
   */
  public fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture>

  /**
   * Reads the pixel size of [image]'s first frame as [decode] hands it back, as stored for every picture but a JPEG
   * XL.
   *
   * @return The size, or [OverlayPictureRead.Refused] with the toolchain's reason when it cannot read the picture, or
   * with core's sentence when it is an AVIF or HEIC whose `irot`, `imir` and `clap` cannot be dropped.
   */
  public fun measure(image: ImageSource): OverlayPictureRead<Size>
}

/**
 * A picture an [OverlayPictureDecoder] decoded, as its stored samples and the color description its decode carried.
 *
 * @property size The first frame's size in pixels.
 * @property samples The first frame's samples as [OverlayPictureDecoder.decode] lays them out, one texel per pixel in
 * rows.
 * @property color How the decode describes the samples' color, or null when it carried no description.
 * @property limits Each way the toolchain decoded the picture short of what its file stores, as a cause the overlay
 * resolves degraded by, or empty when it decoded the picture as stored.
 */
@InternalFilmstripApi
public class DecodedPicture(
  public val size: Size,
  public val samples: StoredSamples,
  public val color: PictureColor?,
  public val limits: List<Degradation> = emptyList(),
)

/**
 * The picture a decoder wrote out as a PNG, with the color description it carried in `cICP` or `iCCP`.
 *
 * The samples are read off the PNG's raster at the depth it stores, so no profile riding with them converts them. The
 * description is read with [pictureColorFromDecodedPng] against the color channels the PNG stores, so a decoder
 * that writes a grey picture out as grey keeps a grey profile.
 *
 * @return The picture, or null when the JDK cannot read the PNG.
 */
@InternalFilmstripApi
public fun decodedPng(png: ByteArray): DecodedPicture? {
  val image =
    try {
      ImageIO.read(ByteArrayInputStream(png))
    } catch (unreadable: IOException) {
      null
    } ?: return null
  val samples = image.storedSamples() ?: return null
  val color = pictureColorFromDecodedPng(png, image.colorModel.numColorComponents)
  return DecodedPicture(Size(image.width, image.height), samples, color)
}

/**
 * What an [OverlayPictureDecoder] got out of decoding a picture, either an [OverlayPictureRead] or [Undrawn].
 *
 * [OverlayPictureDecoder.decode] hands this back. [OverlayPictureDecoder.measure] never looks at how the picture
 * describes its color, so it hands back an [OverlayPictureRead].
 */
@InternalFilmstripApi
public sealed interface OverlayPictureDecode<out T> {
  /**
   * The picture was not decoded, since its color is described in a way the toolchain does not draw as the description
   * defines it, or it is an AVIF or HEIC whose `irot`, `imir` and `clap` could not be dropped ahead of the decoder and
   * so could be turned another way than core turns it.
   *
   * @property reason What the description names and why it is not drawn, or core's sentence for the drop, a sentence
   * that reads on its own.
   */
  @InternalFilmstripApi
  public class Undrawn(
    public val reason: String,
  ) : OverlayPictureDecode<Nothing>
}

/**
 * What an [OverlayPictureDecoder] got out of a picture, either the value it read or the reason it could not.
 */
@InternalFilmstripApi
public sealed interface OverlayPictureRead<out T> : OverlayPictureDecode<T> {
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
