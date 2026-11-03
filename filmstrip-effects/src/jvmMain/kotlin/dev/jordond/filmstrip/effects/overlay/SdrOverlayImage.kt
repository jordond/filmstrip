package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.jpegProfileIsRgbOrGrey
import dev.jordond.filmstrip.picture.jpegWithoutProfile
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureContainerOf
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * The picture an SDR grade's graph opens for an overlay, or the reason it has none to open.
 *
 * [sdrOverlayImage] answers with one of these.
 */
internal sealed interface SdrOverlayImage {
  /**
   * The graph opens [image].
   *
   * @property image The overlay's own picture, or an untagged PNG of the codes its color description gives it.
   * @property size The picture's size as it is shown, or null when it was not decoded.
   * @property limits Each way the decode read the picture short of what its file stores.
   * @property orientation The turn the graph lays [image] out by, the one [orientationLeftOf] gives when [image] is the
   * overlay's own picture and [PictureOrientation.Normal] for a PNG of its codes, which is laid out already.
   */
  class Drawn(
    val image: ImageSource,
    val size: Size? = null,
    val limits: List<Degradation> = emptyList(),
    val orientation: PictureOrientation = PictureOrientation.Normal,
  ) : SdrOverlayImage

  /**
   * The picture needs a decode to be drawn, or to be drawn in its color, and nothing here could give it one.
   *
   * @property message Why, worded for the case it fell into.
   */
  class Refused(
    val message: String,
  ) : SdrOverlayImage
}

/**
 * Reads the samples [image] stores and the color description that defines them.
 *
 * With a [decoder] every picture is decoded by it, so its texels are the ones the backend's own toolchain decodes.
 * Without one the JDK reads it, with an RGB or grey JPEG profile kept out of its way so the samples come back
 * unconverted. Either way [croppedSamples] then cuts them to the rectangle [cropLeftOf] gives and [orientedSamples]
 * lays them out by the turn [orientationLeftOf] gives. The description
 * is read out of the encoded bytes with [pictureColor], so a container's own description is the only one read,
 * including a tiled HEIC whose decode drops it. A JPEG XL, whose color sits in its codestream, takes the description
 * [decoder]'s decode carried instead. Any other picture whose container yields none is untagged, whatever its decode
 * carries, since a decoder infers a description from chunks core reads as sRGB and from codestream color core leaves
 * to the container. A picture whose color model is neither RGB nor grey, such as a CMYK one, is converted to sRGB by
 * the JDK and read as untagged.
 *
 * A picture this process cannot read the bytes of, such as a URL ffmpeg opens and the JVM does not, has no container
 * to read a description out of. Its decode is taken as untagged when it carries no description, and is
 * [StoredRead.Unseen] when it carries one, since nothing here can tell whether the container wrote it.
 */
internal fun storedPicture(
  image: ImageSource,
  decoder: OverlayPictureDecoder?,
): StoredRead {
  val bytes = image.encodedBytes()
  return storedPicture(image, bytes, bytes?.let(::pictureColor), decoder)
}

/**
 * The picture an SDR grade draws for [image].
 *
 * An untagged picture is [image] itself, so the graph opens the same bytes it always has and lays them out by the
 * turn [orientationLeftOf] gives. A tagged one is read through [storedPicture] and its texels go through
 * [pictureSrgbCodes]. When that lands every texel where an untagged read does, as an sRGB profile or one that does not
 * parse does, the graph opens [image] too. Otherwise it opens an untagged PNG of the codes, laid out already. A JPEG
 * XL keeps its color in its codestream, so with a [decoder] it is decoded to find out whether it carries any. Without
 * one it is handed back as itself with no size, and [shownSize] refuses it, since the JDK reads no JPEG XL header.
 *
 * An AVIF or HEIC with a [decoder] always reaches the graph as an untagged PNG of its decode, tagged or not. The graph
 * repeats a still with the image demuxer's `-loop`, which ffmpeg refuses for the demuxer that opens an AVIF or HEIC,
 * and the decode is what converts one with the YCbCr matrix and range its `nclx` box records and assembles a tiled one.
 * Without a [decoder] an AVIF or HEIC is refused, since the graph could not open it.
 *
 * A URI only the toolchain opens, such as an `https` URL, could be any of these, so with a [decoder] it is decoded and
 * drawn as an untagged PNG of its decode, and refused when that decode describes its color, as [storedPicture] reads
 * it. Without one it is handed back as itself with no size. So is a path or file URL that is not a file this process
 * can read, and [shownSize] refuses both before a graph is built.
 */
internal fun sdrOverlayImage(
  image: ImageSource,
  decoder: OverlayPictureDecoder?,
): SdrOverlayImage {
  val bytes = image.encodedBytes()
  val container = bytes?.let(::pictureContainerOf)
  val heif = container == PictureContainer.Heif
  if (heif && decoder == null) return SdrOverlayImage.Refused(UNREAD_HEIF)
  val described = bytes?.let(::pictureColor)
  val codestream = container == PictureContainer.JxlCodestream || container == PictureContainer.JxlBoxed
  val decoded = decoder != null && (codestream || heif || (bytes == null && image.toolchainOnly))
  // ffmpeg's limit: a URI only ffmpeg opens, such as an https URL, has no bytes this process can read, so core reads
  // no orientation or clap for it. It is drawn unturned and cropped however that ffmpeg build crops it as it decodes
  // with -noautorotate. 8.0.1 and 9.0.2 crop an AVIF or HEIC by its clap.
  val orientation = orientationLeftOf(bytes)
  if (described == null && !decoded) return SdrOverlayImage.Drawn(image, orientation = orientation)

  val picture =
    when (val read = storedPicture(image, bytes, described, decoder)) {
      is StoredRead.Read -> {
        read.picture
      }
      is StoredRead.Undecoded -> {
        val refusal =
          when {
            bytes == null -> UNDECODED_UNREADABLE_IMAGE
            codestream -> UNDECODED_JPEG_XL
            described == null -> UNDECODED_HEIF
            else -> UNDECODED_TAGGED_IMAGE
          }
        return SdrOverlayImage.Refused(refusal.saying(read.reason))
      }
      is StoredRead.Undrawn -> {
        return SdrOverlayImage.Refused(read.reason)
      }
      StoredRead.Unread -> {
        return SdrOverlayImage.Refused(UNREAD_TAGGED_IMAGE)
      }
      StoredRead.Unseen -> {
        return SdrOverlayImage.Refused(UNSEEN_COLOR)
      }
    }
  val codes = picture.samples.srgbCodes(picture.color)
  val asStored = codes.contentEquals(picture.samples.srgbCodes(null))
  if (bytes != null && !heif && asStored) {
    return SdrOverlayImage.Drawn(image, picture.size, picture.limits, orientation)
  }
  return SdrOverlayImage.Drawn(ImageSource.ofBytes(argbPng(codes, picture.size)), picture.size, picture.limits)
}

private fun storedPicture(
  image: ImageSource,
  bytes: ByteArray?,
  described: PictureColor?,
  decoder: OverlayPictureDecoder?,
): StoredRead {
  if (decoder == null) {
    val read = bytes?.let(::readWithJdk)?.stored(described) ?: return StoredRead.Unread
    return read.laidOut(bytes)
  }

  return when (val read = decoder.decode(image)) {
    is OverlayPictureRead.Read -> {
      val decoded = read.value
      if (bytes == null && decoded.color != null) return StoredRead.Unseen
      val codestream = decoded.color.takeIf { bytes != null && isJpegXl(bytes) }
      val picture = StoredPicture(decoded.samples, decoded.size, described ?: codestream, decoded.limits)
      picture.laidOut(bytes)
    }
    is OverlayPictureRead.Refused -> {
      StoredRead.Undecoded(read.reason)
    }
    is OverlayPictureDecode.Undrawn -> {
      StoredRead.Undrawn(read.reason)
    }
  }
}

// A refusal with the toolchain's own account of the failure after it, when it gave one.
internal fun String.saying(reason: String?): String =
  if (reason.isNullOrBlank()) this else "$this The toolchain said: ${reason.trim()}"

// The encoded bytes the graph would open, or null when this process cannot read them. A URI is read as the file path
// the backend materialises it to.
internal fun ImageSource.encodedBytes(): ByteArray? =
  try {
    when (this) {
      is ImageSource.Path -> File(path).takeIf { it.isFile }?.readBytes()
      is ImageSource.Uri -> filePathOf(uri)?.let(::File)?.takeIf { it.isFile }?.readBytes()
      is ImageSource.Bytes -> bytes
    }
  } catch (unreadable: IOException) {
    null
  }

// A URI with no file path behind it, which this process has no reader for and only the toolchain opens.
private val ImageSource.toolchainOnly: Boolean
  get() = this is ImageSource.Uri && filePathOf(uri) == null

// The JDK's JPEG reader converts to sRGB through the profile an APP2 segment carries, so an RGB or grey JPEG is read
// without it. A CMYK or YCCK JPEG keeps its profile, since the JDK needs it to read the picture at all.
private fun readWithJdk(bytes: ByteArray): BufferedImage? =
  try {
    val readable = if (jpegProfileIsRgbOrGrey(bytes)) jpegWithoutProfile(bytes) else bytes
    ImageIO.read(ByteArrayInputStream(readable))
  } catch (unreadable: IOException) {
    null
  }

// The picture with [color], or as the JDK's sRGB conversion with none when its color model is neither RGB nor grey.
private fun BufferedImage.stored(color: PictureColor?): StoredPicture {
  val size = Size(width, height)
  return storedSamples()?.let { StoredPicture(it, size, color) }
    ?: StoredPicture(StoredSamples.Argb(getRGB(0, 0, width, height, null, 0, width)), size, null)
}

// An eight-bit RGBA PNG of [argb] with no color description in it.
private fun argbPng(
  argb: IntArray,
  size: Size,
): ByteArray {
  val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, size.width, size.height, argb, 0, size.width)
  return ByteArrayOutputStream()
    .also { check(ImageIO.write(image, "png", it)) { "The JDK has no PNG writer for an eight-bit picture." } }
    .toByteArray()
}

private const val UNDECODED_TAGGED_IMAGE =
  "The overlay image could not be read. It describes its color, so this backend decodes the picture to draw its " +
    "colors the way the description defines them, and this ffmpeg could not decode it. Check that the bytes are a " +
    "picture this ffmpeg build decodes."

private const val UNDECODED_JPEG_XL =
  "The overlay image could not be read. A JPEG XL keeps its color description inside its codestream, so this " +
    "backend decodes the picture to find it, and this ffmpeg could not decode it. Check that this ffmpeg build " +
    "decodes JPEG XL."

private const val UNDECODED_HEIF =
  "The overlay image could not be read. This backend draws an AVIF or HEIC from its own decode of the picture, " +
    "since its graph cannot repeat one as it is, and this ffmpeg could not decode it. Check that this ffmpeg build " +
    "decodes AVIF and HEIC."

private const val UNDECODED_UNREADABLE_IMAGE =
  "The overlay image could not be read. This process cannot read the picture's bytes, so this backend decodes it " +
    "to draw it, and this ffmpeg could not decode it either. Check that the path or URL is readable by this " +
    "process, and that the bytes are a picture this ffmpeg build decodes."

private const val UNREAD_HEIF =
  "The overlay image could not be read. This backend draws an AVIF or HEIC from a decode of the picture, since its " +
    "graph cannot repeat one as it is, and this catalogue was built without a picture decoder. Build the catalogue " +
    "with the backend's picture decoder, or use a format the graph repeats, such as PNG."

/**
 * Why a picture is refused when this process cannot read its bytes and its decode describes its color.
 */
internal const val UNSEEN_COLOR =
  "The overlay image could not be drawn in its color. This process cannot read the picture's bytes, so the color " +
    "description its container carries is out of reach, and the decode carried one that may not be the " +
    "container's. Use a path or a file URL this process can read."

private const val UNREAD_TAGGED_IMAGE =
  "The overlay image could not be read. It describes its color, so the picture is decoded to draw its colors " +
    "the way the description defines them, and the JDK could not decode it. This catalogue was built without a " +
    "picture decoder, so it draws a color-tagged picture only when the JDK reads it. Use a format the JDK reads, " +
    "such as PNG or JPEG, or build the catalogue with the backend's picture decoder."
