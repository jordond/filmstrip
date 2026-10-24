package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Reads an overlay picture with the toolchain the backend draws pictures with.
 *
 * Every picture the catalogue decodes in this process comes here when the backend has a decoder, so its texels are the
 * ones the toolchain draws, oriented the way it orients them. An animated overlay is sized here too, so its size and
 * its texels agree. An overlay on an HDR grade is decoded so its colours can be carried onto the grade's signal, and a
 * colour-tagged picture is decoded on an SDR grade too, so its colours can be drawn the way its description defines
 * them, as is every AVIF and HEIC. The JDK reads pictures only for a catalogue built without a decoder.
 */
@InternalFilmstripApi
public interface OverlayPictureDecoder {
  /**
   * Decodes the first frame of [image] at the picture's own size, with the colour description its decode carries.
   *
   * The samples come back as the picture stores them, converted to RGB the way the toolchain converts them for an
   * overlay and with no colour management on the way. The description is the one the toolchain's decoder found, which
   * is how a format that keeps it inside its codestream, such as JPEG XL, reaches the caller.
   *
   * @return The picture, [OverlayPictureDecode.Undrawn] when its colour is described in a way the toolchain does not
   * draw, or [OverlayPictureRead.Refused] with the toolchain's reason when it cannot decode it.
   */
  public fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture>

  /**
   * Reads the pixel size of [image]'s first frame as [decode] hands it back, turned by any orientation the toolchain
   * applies.
   *
   * @return The size, or [OverlayPictureRead.Refused] with the toolchain's reason when it cannot read the picture.
   */
  public fun measure(image: ImageSource): OverlayPictureRead<Size>
}

/**
 * A picture an [OverlayPictureDecoder] decoded, as its stored samples and the colour description its decode carried.
 *
 * @property size The first frame's size in pixels.
 * @property samples The first frame's samples as the picture stores them, one texel per pixel in rows.
 * @property colour How the decode describes the samples' colour, or null when it carried no description.
 * @property limits Each way the toolchain decoded the picture short of what its file stores, as a cause the overlay
 * resolves degraded by, or empty when it decoded the picture as stored.
 */
@InternalFilmstripApi
public class DecodedPicture(
  public val size: Size,
  public val samples: StoredSamples,
  public val colour: PictureColour?,
  public val limits: List<Degradation> = emptyList(),
)

/**
 * An overlay picture's straight, unpremultiplied samples, before any colour conversion.
 *
 * A picture of eight bits a channel or fewer is held as [Argb], and a deeper one as [Rgba16], so a sixteen-bit or HDR
 * picture keeps every bit it stores.
 */
@InternalFilmstripApi
public sealed interface StoredSamples {
  /**
   * Samples of eight bits a channel.
   *
   * @property argb One packed ARGB colour per texel.
   */
  @InternalFilmstripApi
  public class Argb(
    public val argb: IntArray,
  ) : StoredSamples

  /**
   * Samples deeper than eight bits a channel, each scaled to sixteen bits.
   *
   * @property rgba Four samples per texel, red, green, blue and alpha, each read as an unsigned sixteen-bit value.
   */
  @InternalFilmstripApi
  public class Rgba16(
    public val rgba: ShortArray,
  ) : StoredSamples
}

/**
 * The picture a decoder wrote out as a PNG, with the colour description it carried in `cICP` or `iCCP`.
 *
 * The samples are read off the PNG's raster at the depth it stores, so no profile riding with them converts them. The
 * description is read with [PictureColour.fromDecodedPng] against the colour channels the PNG stores, so a decoder
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
  val colour = PictureColour.fromDecodedPng(png, image.colorModel.numColorComponents)
  return DecodedPicture(Size(image.width, image.height), samples, colour)
}

/**
 * What an [OverlayPictureDecoder] got out of decoding a picture, either an [OverlayPictureRead] or [Undrawn].
 *
 * [OverlayPictureDecoder.decode] hands this back. [OverlayPictureDecoder.measure] never looks at how the picture
 * describes its colour, so it hands back an [OverlayPictureRead].
 */
@InternalFilmstripApi
public sealed interface OverlayPictureDecode<out T> {
  /**
   * The picture was not decoded, since its colour is described in a way the toolchain does not draw as the
   * description defines it.
   *
   * @property reason What the description names and why it is not drawn, a sentence that reads on its own.
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
