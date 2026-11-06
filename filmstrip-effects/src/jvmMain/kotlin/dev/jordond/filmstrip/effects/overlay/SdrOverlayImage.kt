package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.jpegProfileIsRgbOrGrey
import dev.jordond.filmstrip.picture.jpegWithoutProfile
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureContainerOf
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferUShort
import java.awt.image.Raster
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
   * @property image The overlay's own bytes as this lowering read them, an untagged PNG of the codes its color
   * description gives it, or the overlay's own picture when this process cannot read its bytes.
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
  bytes: ByteArray? = image.encodedBytes(),
): StoredRead = storedPicture(image, bytes, bytes?.let(::pictureColor), decoder)

/**
 * The picture an SDR grade draws for [image].
 *
 * The bytes of [image] are read once, by the lowering's prepare step or here, and everything below comes from them. The
 * graph opens those bytes rather than the file, and [decoder] is handed them too, so a picture changed or deleted after
 * that read is still drawn the way it was read, its color, orientation, crop, samples and size alike. An untagged
 * picture is those bytes, laid out by the turn [orientationLeftOf] gives. A tagged one is read through [storedPicture]
 * and its texels go through [pictureSrgbCodes]. When that lands every texel where an untagged read does, as an sRGB
 * profile or one that does not parse does, the graph opens the bytes too. Otherwise it opens an untagged PNG of the
 * codes, laid out already. A decode that lands where it is stored keeps its sixteen-bit samples in that PNG, so a deep
 * picture only ffmpeg's decode can read reaches the graph at the depth a deep PNG does.
 *
 * The graph repeats a still with the image demuxer's `-loop` from a scratch file named for a PNG, and a picture that
 * yields no frame there spins forever rather than failing. So bytes the graph would open are first run through one
 * frame of a decode, by [decoder]'s measure, which also sizes them, or by the JDK without one. A picture that yields no
 * frame is refused by name.
 *
 * A JPEG XL keeps its color in its codestream, so with a [decoder] it is decoded to find out whether it carries any.
 * Without one it is refused, since the JDK decodes no JPEG XL.
 *
 * An AVIF or HEIC with a [decoder] always reaches the graph as an untagged PNG of its decode, tagged or not. `-loop` is
 * refused by the demuxer that opens an AVIF or HEIC, and the decode is what converts one with the YCbCr matrix and
 * range its `nclx` box records and assembles a tiled one. Without a [decoder] an AVIF or HEIC is refused, since the
 * graph could not open it. A GIF's demuxer refuses `-loop` too, so a GIF goes the same way and draws its first frame
 * on its logical screen.
 *
 * A file whose bytes ffmpeg cannot recognise without its extension, such as a TGA, is decoded by [decoder] from a copy
 * of those bytes named for its format, and drawn as an untagged PNG of its decode, since the scratch file is not named
 * for them. Without a [decoder] it is refused, since nothing could be opened. [probedByContent] lists what ffmpeg does
 * recognise.
 *
 * A URI only the toolchain opens, such as an `https` URL, could be any of these, so with a [decoder] it is decoded and
 * drawn as an untagged PNG of its decode, and refused when that decode describes its color, as [storedPicture] reads
 * it. Without one it is handed back as itself with no size. So is a path or file URL that is not a file this process
 * can read, and [shownSize] refuses both before a graph is built.
 *
 * @param bytes The bytes of [image] as this lowering read them, or null when this process cannot read them.
 */
internal fun sdrOverlayImage(
  image: ImageSource,
  decoder: OverlayPictureDecoder?,
  bytes: ByteArray? = image.encodedBytes(),
): SdrOverlayImage {
  val container = bytes?.let(::pictureContainerOf)
  val heif = container == PictureContainer.Heif
  if (heif && decoder == null) return SdrOverlayImage.Refused(UNREAD_HEIF)
  val described = bytes?.let(::pictureColor)
  val codestream = container == PictureContainer.JxlCodestream || container == PictureContainer.JxlBoxed
  val opensAsBytes = bytes != null && probedByContent(bytes, container)
  val unprobed = bytes != null && !opensAsBytes
  if (unprobed && decoder == null) return SdrOverlayImage.Refused(unprobedWithoutDecoder(image))
  val decoded = decoder != null && (codestream || heif || unprobed || (bytes == null && image.toolchainOnly))
  // ffmpeg's limit: a URI only ffmpeg opens, such as an https URL, has no bytes this process can read, so core reads
  // no orientation or clap for it. It is drawn unturned and cropped however that ffmpeg build crops it as it decodes
  // with -noautorotate. 8.0.1 and 9.0.2 crop an AVIF or HEIC by its clap.
  val orientation = orientationLeftOf(bytes)
  val opened = if (image is ImageSource.Bytes) image else bytes?.let(ImageSource::ofBytes) ?: image
  if (described == null && !decoded) {
    val stored = if (bytes == null) null else oneFrameOf(image, bytes, opened, decoder) { return it }
    val shown = if (bytes == null || stored == null) null else shownOf(bytes, stored)
    return SdrOverlayImage.Drawn(opened, shown, orientation = orientation)
  }

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
            heif && described == null -> UNDECODED_HEIF
            described == null -> UNDECODED_UNPROBED
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
  if (bytes != null && !heif && asStored && opensAsBytes) {
    return SdrOverlayImage.Drawn(opened, picture.size, picture.limits, orientation)
  }
  val deep = (picture.samples as? StoredSamples.Rgba16)?.takeIf { asStored }
  val png = deep?.let { rgba16Png(it.rgba, picture.size) } ?: argbPng(codes, picture.size)
  return SdrOverlayImage.Drawn(ImageSource.ofBytes(png), picture.size, picture.limits)
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

  return when (val read = decodedOnce(image, bytes, decoder)) {
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

// What [decoder] makes of [bytes], the one read of [image], without letting it open [image] again. A format ffmpeg
// finds by extension alone goes through a private copy under the name [image] gives it. With no bytes there is only
// [image].
private fun decodedOnce(
  image: ImageSource,
  bytes: ByteArray?,
  decoder: OverlayPictureDecoder,
): OverlayPictureDecode<DecodedPicture> {
  if (bytes == null) return decoder.decode(image)
  if (image is ImageSource.Bytes) return decoder.decode(image)
  val container = pictureContainerOf(bytes)
  if (probedByContent(bytes, container) || container == PictureContainer.Heif) {
    return decoder.decode(ImageSource.ofBytes(bytes))
  }
  val copy =
    try {
      File.createTempFile(COPY_PREFIX, image.extensionOf())
    } catch (unwritable: IOException) {
      return OverlayPictureRead.Refused(unwritable.message.orEmpty())
    }
  return try {
    copy.writeBytes(bytes)
    decoder.decode(ImageSource.of(copy.path))
  } catch (unwritable: IOException) {
    OverlayPictureRead.Refused(unwritable.message.orEmpty())
  } finally {
    copy.delete()
  }
}

// The stored size of the first frame of [bytes], the bytes of [image] that [opened] names, or [refuse] called when they
// yield none, which the graph's `-loop` would spin on. Null when only the JDK looks at them, which sizes them later.
private inline fun oneFrameOf(
  image: ImageSource,
  bytes: ByteArray,
  opened: ImageSource,
  decoder: OverlayPictureDecoder?,
  refuse: (SdrOverlayImage.Refused) -> Nothing,
): Size? {
  if (decoder == null) {
    if (readWithJdk(bytes) == null) refuse(SdrOverlayImage.Refused(unreadByJdk(image)))
    return null
  }
  return when (val read = decoder.measure(opened)) {
    is OverlayPictureRead.Read -> read.value
    is OverlayPictureRead.Refused -> refuse(SdrOverlayImage.Refused(noFrame(image).saying(read.reason)))
  }
}

// The extension [image]'s path ends in, as a suffix ffmpeg can match, or none when it has none or could read as a
// pattern.
private fun ImageSource.extensionOf(): String {
  val path =
    when (this) {
      is ImageSource.Path -> path
      is ImageSource.Uri -> filePathOf(uri).orEmpty()
      is ImageSource.Bytes -> ""
    }
  val extension = File(path).extension
  return if (extension.isNotEmpty() && extension.all(Char::isLetterOrDigit)) ".$extension" else ""
}

// A refusal with the toolchain's own account of the failure after it, when it gave one.
internal fun String.saying(reason: String?): String =
  if (reason.isNullOrBlank()) this else "$this The toolchain said: ${reason.trim()}"

// The encoded bytes the graph would open, or null when this process cannot read them.
internal fun ImageSource.encodedBytes(): ByteArray? = encodedRead().bytes

// One read of the encoded bytes the graph would open. They are refused past [ceiling], by the file's length before a
// byte is read and by what the read gave for a file that grew on the way. A URI is read as the file path the backend
// materialises it to. Bytes are held by the caller already, so they are not read at all.
internal fun ImageSource.encodedRead(ceiling: Long = PICTURE_PEAK_CEILING): HeldBytes {
  val path =
    when (this) {
      is ImageSource.Path -> path
      is ImageSource.Uri -> filePathOf(uri)
      is ImageSource.Bytes -> return HeldBytes(bytes)
    }
  val tooLarge = HeldBytes(null, pictureTooLargeToRead(this, ceiling))
  return try {
    val file = path?.let(::File)?.takeIf { it.isFile } ?: return HeldBytes(null)
    if (file.length() > ceiling) return tooLarge
    val bytes = file.inputStream().use { it.readNBytes(ceiling.toInt() + 1) }
    if (bytes.size > ceiling) tooLarge else HeldBytes(bytes)
  } catch (unreadable: IOException) {
    HeldBytes(null)
  } catch (forbidden: SecurityException) {
    HeldBytes(null)
  } catch (outOfMemory: OutOfMemoryError) {
    HeldBytes(null)
  }
}

// Whether [bytes] are a format the graph can open under the scratch file's name, one ffmpeg recognises off the bytes
// themselves and repeats with `-loop`, as measured on ffmpeg 8.0.1 and 9.0.2. A format with no signature ffmpeg probes,
// such as TGA, WBMP or PAM, is decoded by the file's extension, and the scratch file's is not its own, so ffmpeg's
// image demuxer would retry the wrong decoder forever. A GIF is recognised and opens on its own demuxer, which has no
// `-loop`.
private fun probedByContent(
  bytes: ByteArray,
  container: PictureContainer?,
): Boolean =
  when (container) {
    PictureContainer.Jpeg,
    PictureContainer.Png,
    PictureContainer.Webp,
    PictureContainer.Tiff,
    PictureContainer.JxlCodestream,
    PictureContainer.JxlBoxed,
    -> true
    else -> CONTENT_PROBED.any(bytes::startsWith) || isProbedPnm(bytes)
  }

private fun ByteArray.startsWith(signature: ByteArray): Boolean =
  size >= signature.size && signature.indices.all { this[it] == signature[it] }

// ffmpeg's own probe: the magic on a line of its own, then a digit or a comment.
private fun isProbedPnm(bytes: ByteArray): Boolean {
  if (bytes.size <= PNM_FIRST_FIELD || bytes[0] != 'P'.code.toByte()) return false
  val first = bytes[PNM_FIRST_FIELD].toInt().toChar()
  return bytes[1].toInt().toChar() in '1'..'6' && bytes[2] == '\n'.code.toByte() && (first == '#' || first.isDigit())
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
  } catch (malformed: IllegalArgumentException) {
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

// A sixteen-bit RGBA PNG of [rgba], four samples a texel, with no color description in it.
private fun rgba16Png(
  rgba: ShortArray,
  size: Size,
): ByteArray {
  val model =
    ComponentColorModel(
      ColorSpace.getInstance(ColorSpace.CS_sRGB),
      true,
      false,
      Transparency.TRANSLUCENT,
      DataBuffer.TYPE_USHORT,
    )
  val buffer = DataBufferUShort(rgba, rgba.size)
  val raster =
    Raster.createInterleavedRaster(
      buffer,
      size.width,
      size.height,
      size.width * RGBA_BANDS.size,
      RGBA_BANDS.size,
      RGBA_BANDS,
      null,
    )
  val image = BufferedImage(model, raster, false, null)
  return ByteArrayOutputStream()
    .also { check(ImageIO.write(image, "png", it)) { "The JDK has no PNG writer for a sixteen-bit picture." } }
    .toByteArray()
}

private val RGBA_BANDS = intArrayOf(0, 1, 2, 3)
private const val COPY_PREFIX = "filmstrip-overlay-copy"
private const val PNM_FIRST_FIELD = 3

// BMP, QOI, JPEG 2000 as a box file and as a bare codestream, OpenEXR, Radiance, DPX either way round, SGI, Sun raster
// and XBM.
private val CONTENT_PROBED: List<ByteArray> =
  listOf(
    "BM".encodeToByteArray(),
    "qoif".encodeToByteArray(),
    byteArrayOf(0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20),
    byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51),
    byteArrayOf(0x76, 0x2F, 0x31, 0x01),
    "#?RADIANCE".encodeToByteArray(),
    "SDPX".encodeToByteArray(),
    "XPDS".encodeToByteArray(),
    byteArrayOf(0x01, 0xDA.toByte()),
    byteArrayOf(0x59, 0xA6.toByte(), 0x6A, 0x95.toByte()),
    "#define".encodeToByteArray(),
  )

// Why a picture the graph could only open by its own extension is refused with no decoder to turn it into a PNG.
private fun unprobedWithoutDecoder(image: ImageSource): String =
  "The overlay image ${image.describe()} could not be drawn. This backend's graph opens a picture's bytes from a " +
    "scratch file, which takes a format ffmpeg recognises from those bytes and can repeat, and this is not one. " +
    "This catalogue was built without a picture decoder to draw it from a decode. Build the catalogue with the " +
    "backend's picture decoder, or use a format the graph repeats, such as PNG."

// Why bytes that yield no frame are refused before the graph spins on them.
private fun noFrame(image: ImageSource): String =
  "The overlay image ${image.describe()} could not be decoded. This backend draws a picture by repeating its first " +
    "frame, and this ffmpeg could not decode one. Check that the file is whole and that it is a picture this ffmpeg " +
    "build decodes."

// The same refusal with only the JDK to ask, since the catalogue was built without a picture decoder.
private fun unreadByJdk(image: ImageSource): String =
  "The overlay image ${image.describe()} could not be decoded. This backend draws a picture by repeating its first " +
    "frame, and the JDK could not decode one. This catalogue was built without a picture decoder, so it draws only " +
    "what the JDK reads. Check that the file is whole, or build the catalogue with the backend's picture decoder."

private const val UNDECODED_UNPROBED =
  "The overlay image could not be read. This backend draws a picture ffmpeg cannot recognise from its bytes alone " +
    "from its own decode of the picture, and this ffmpeg could not decode it. Check that the bytes are a picture " +
    "this ffmpeg build decodes."

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
