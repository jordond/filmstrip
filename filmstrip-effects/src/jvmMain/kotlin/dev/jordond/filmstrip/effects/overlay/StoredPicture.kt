package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.jpegWithoutProfile
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.media.jpegProfileIsRgbOrGrey
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * An overlay picture as the samples it stores and the colour description that defines them.
 *
 * @property samples The samples before any colour conversion, one texel per pixel in rows.
 * @property size The picture's own size in pixels.
 * @property colour How the picture describes its colour, or null when it is untagged.
 * @property limits Each way the backend's decoder read the picture short of what its file stores.
 */
internal class StoredPicture(
  val samples: StoredSamples,
  val size: Size,
  val colour: PictureColour?,
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
   * @property picture Its samples, size and colour description.
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
   * The backend's decoder did not decode the picture, since its colour is described in a way the backend does not
   * draw.
   *
   * @property reason What the description names and why it is not drawn.
   */
  class Undrawn(
    val reason: String,
  ) : StoredRead

  /**
   * There is no decoder and the JDK could not read the picture.
   */
  data object Unread : StoredRead

  /**
   * This process could not read the picture's bytes, so its container's colour description is out of reach, and the
   * decode carried a description of its own that may or may not be the container's.
   */
  data object Unseen : StoredRead
}

/**
 * The picture an SDR grade's graph opens for an overlay, or the reason it has none to open.
 *
 * [sdrOverlayImage] answers with one of these.
 */
internal sealed interface SdrOverlayImage {
  /**
   * The graph opens [image].
   *
   * @property image The overlay's own picture, or an untagged PNG of the codes its colour description gives it.
   * @property size The picture's size as the decode handed it back, or null when it was not decoded.
   * @property limits Each way the decode read the picture short of what its file stores.
   */
  class Drawn(
    val image: ImageSource,
    val size: Size? = null,
    val limits: List<Degradation> = emptyList(),
  ) : SdrOverlayImage

  /**
   * The picture needs a decode to be drawn, or to be drawn in its colour, and nothing here could give it one.
   *
   * @property message Why, worded for the case it fell into.
   */
  class Refused(
    val message: String,
  ) : SdrOverlayImage
}

/**
 * Reads the samples [image] stores and the colour description that defines them.
 *
 * With a [decoder] every picture is decoded by it, so its texels are the ones the backend's own toolchain decodes,
 * oriented the way it orients them. Without one the JDK reads it, with an RGB or grey JPEG profile kept out of its way
 * so the samples come back unconverted. The description is read out of the encoded bytes with [pictureColour], so a
 * container's own description is the only one read, including a tiled HEIC whose decode drops it. A JPEG XL, whose
 * colour sits in its codestream, takes the description [decoder]'s decode carried instead. Any other picture whose
 * container yields none is untagged, whatever its decode carries, since a decoder infers a description from chunks
 * core reads as sRGB and from codestream colour core leaves to the container. A picture whose colour model is neither
 * RGB nor grey, such as a CMYK one, is converted to sRGB by the JDK and read as untagged.
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
  return storedPicture(image, bytes, bytes?.let(::pictureColour), decoder)
}

/**
 * The picture an SDR grade draws for [image].
 *
 * An untagged picture is [image] itself, so the graph opens the same bytes it always has. A tagged one is read through
 * [storedPicture] and its texels go through [pictureSrgbCodes]. When that lands every texel where an untagged read
 * does, as an sRGB profile or one that does not parse does, the graph opens [image] too. Otherwise it opens an
 * untagged PNG of the codes. A JPEG XL keeps its colour in its codestream, so with a [decoder] it is decoded to find
 * out whether it carries any, and without one it is drawn as it is.
 *
 * An AVIF or HEIC with a [decoder] always reaches the graph as an untagged PNG of its decode, tagged or not. The graph
 * repeats a still with the image demuxer's `-loop`, which ffmpeg refuses for the demuxer that opens an AVIF or HEIC,
 * and the decode is what converts one with the YCbCr matrix and range its `nclx` box records and assembles a tiled one.
 * Without a [decoder] an AVIF or HEIC is refused, since the graph could not open it.
 *
 * A URI only the toolchain opens, such as an `https` URL, could be any of these, so with a [decoder] it is decoded and
 * drawn as an untagged PNG of its decode, and refused when that decode describes its colour, as [storedPicture] reads
 * it. Without one it is drawn as it is, the one picture whose format nothing here can check before the graph opens it.
 * A path or file URL this process cannot read is drawn as it is too, and the graph that opens it fails on it.
 */
internal fun sdrOverlayImage(
  image: ImageSource,
  decoder: OverlayPictureDecoder?,
): SdrOverlayImage {
  val bytes = image.encodedBytes()
  val heif = bytes?.isHeif() == true
  if (heif && decoder == null) return SdrOverlayImage.Refused(UNREAD_HEIF)
  val described = bytes?.let(::pictureColour)
  val codestream = bytes?.isJpegXl() == true
  val decoded = decoder != null && (codestream || heif || (bytes == null && image.toolchainOnly))
  if (described == null && !decoded) return SdrOverlayImage.Drawn(image)

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
        return SdrOverlayImage.Refused(UNSEEN_COLOUR)
      }
    }
  val codes = picture.samples.srgbCodes(picture.colour)
  val asStored = codes.contentEquals(picture.samples.srgbCodes(null))
  if (bytes != null && !heif && asStored) return SdrOverlayImage.Drawn(image, picture.size, picture.limits)
  return SdrOverlayImage.Drawn(ImageSource.ofBytes(argbPng(codes, picture.size)), picture.size, picture.limits)
}

private fun storedPicture(
  image: ImageSource,
  bytes: ByteArray?,
  described: PictureColour?,
  decoder: OverlayPictureDecoder?,
): StoredRead {
  if (decoder == null) {
    return bytes?.let(::readWithJdk)?.let { StoredRead.Read(it.stored(described)) } ?: StoredRead.Unread
  }

  return when (val read = decoder.decode(image)) {
    is OverlayPictureRead.Read -> {
      val decoded = read.value
      if (bytes == null && decoded.colour != null) return StoredRead.Unseen
      val codestream = decoded.colour.takeIf { bytes?.isJpegXl() == true }
      StoredRead.Read(StoredPicture(decoded.samples, decoded.size, described ?: codestream, decoded.limits))
    }
    is OverlayPictureRead.Refused -> {
      StoredRead.Undecoded(read.reason)
    }
    is OverlayPictureDecode.Undrawn -> {
      StoredRead.Undrawn(read.reason)
    }
  }
}

// The encoded bytes the graph would open, or null when this process cannot read them. A URI is read as the file path
// the backend materialises it to.
private fun ImageSource.encodedBytes(): ByteArray? =
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

// The picture with [colour], or as the JDK's sRGB conversion with none when its colour model is neither RGB nor grey.
private fun BufferedImage.stored(colour: PictureColour?): StoredPicture {
  val size = Size(width, height)
  return storedSamples()?.let { StoredPicture(it, size, colour) }
    ?: StoredPicture(StoredSamples.Argb(getRGB(0, 0, width, height, null, 0, width)), size, null)
}

/**
 * The texels as the picture stores them, or null for a colour model that is neither RGB, grey nor a palette.
 *
 * A palette's entries are stored codes, so a palette picture is read through them. Every other RGB or grey picture is
 * read off its raster, so neither a profile the JDK honours nor the linear light the JDK tags every grey PNG with moves
 * a sample. A picture whose bands are eight bits or fewer comes back as [StoredSamples.Argb], and a deeper one as
 * [StoredSamples.Rgba16], each band scaled from its own depth.
 */
internal fun BufferedImage.storedSamples(): StoredSamples? {
  val model = colorModel
  if (model is IndexColorModel) return StoredSamples.Argb(getRGB(0, 0, width, height, null, 0, width))
  val grey =
    when (model.colorSpace.type) {
      ColorSpace.TYPE_RGB -> false
      ColorSpace.TYPE_GRAY -> true
      else -> return null
    }

  val bands = model.numComponents
  val floating = raster.dataBuffer.dataType.let { it == DataBuffer.TYPE_FLOAT || it == DataBuffer.TYPE_DOUBLE }
  val deep = floating || (0 until bands).any { model.getComponentSize(it) > EIGHT_BITS }
  val alphaBand = if (model.hasAlpha()) model.numColorComponents else NO_BAND
  val full = FloatArray(bands) { if (floating) 1f else ((1L shl model.getComponentSize(it)) - 1).toFloat() }
  val premultiplied = model.isAlphaPremultiplied
  val argb = if (deep) null else IntArray(width * height)
  val rgba = if (deep) ShortArray(width * height * RGBA) else null
  val row = FloatArray(width * bands)
  val levels = FloatArray(RGBA)
  for (y in 0 until height) {
    raster.getPixels(0, y, width, 1, row)
    for (x in 0 until width) {
      val at = x * bands
      val alpha = if (alphaBand == NO_BAND) 1f else (row[at + alphaBand] / full[alphaBand]).coerceIn(0f, 1f)
      val unmultiply = if (premultiplied && alpha > 0f) alpha else 1f
      for (channel in 0 until 3) {
        val band = if (grey) 0 else channel
        levels[channel] = (row[at + band] / full[band] / unmultiply).coerceIn(0f, 1f)
      }
      levels[3] = alpha
      val texel = y * width + x
      if (argb != null) {
        fun code(channel: Int): Int = (levels[channel] * BYTE_MAX).roundToInt()
        argb[texel] = (code(3) shl ALPHA_SHIFT) or (code(0) shl RED_SHIFT) or (code(1) shl GREEN_SHIFT) or code(2)
      } else {
        for (channel in 0 until RGBA) {
          checkNotNull(rgba)[texel * RGBA + channel] = (levels[channel] * WIDE_MAX).roundToInt().toShort()
        }
      }
    }
  }
  return argb?.let { StoredSamples.Argb(it) } ?: StoredSamples.Rgba16(checkNotNull(rgba))
}

internal val StoredSamples.texelCount: Int
  get() =
    when (this) {
      is StoredSamples.Argb -> argb.size
      is StoredSamples.Rgba16 -> rgba.size / RGBA
    }

/**
 * The colour of the texel at [index] with its alpha left out, as one key that tells apart every colour the samples
 * can hold.
 */
internal fun StoredSamples.colourKey(index: Int): Long =
  when (this) {
    is StoredSamples.Argb -> {
      (argb[index] and RGB_MASK).toLong()
    }
    is StoredSamples.Rgba16 -> {
      val at = index * RGBA
      (wide(at) shl WIDE_BITS * 2) or (wide(at + 1) shl WIDE_BITS) or wide(at + 2)
    }
  }

/**
 * The alpha of the texel at [index] as a sixteen-bit sample.
 */
internal fun StoredSamples.wideAlpha(index: Int): Int =
  when (this) {
    is StoredSamples.Argb -> (argb[index] ushr ALPHA_SHIFT) * WIDE_MAX / BYTE_MAX
    is StoredSamples.Rgba16 -> wide(index * RGBA + 3).toInt()
  }

/**
 * The texels at [indices], in that order and each made opaque.
 */
internal fun StoredSamples.opaqueAt(indices: IntArray): StoredSamples =
  when (this) {
    is StoredSamples.Argb -> {
      StoredSamples.Argb(IntArray(indices.size) { argb[indices[it]] or OPAQUE })
    }
    is StoredSamples.Rgba16 -> {
      val picked = ShortArray(indices.size * RGBA)
      indices.forEachIndexed { slot, index ->
        rgba.copyInto(picked, slot * RGBA, index * RGBA, index * RGBA + 3)
        picked[slot * RGBA + 3] = WIDE_MAX.toShort()
      }
      StoredSamples.Rgba16(picked)
    }
  }

/**
 * The linear BT.2020 display light [pictureLight] gives these samples under [colour], four floats per texel.
 */
internal fun StoredSamples.light(colour: PictureColour?): FloatArray =
  when (this) {
    is StoredSamples.Argb -> pictureLight(colour, argb)
    is StoredSamples.Rgba16 -> pictureLight(colour, rgba)
  }

/**
 * The sRGB codes [pictureSrgbCodes] gives these samples under [colour], one packed ARGB colour per texel.
 */
internal fun StoredSamples.srgbCodes(colour: PictureColour?): IntArray =
  when (this) {
    is StoredSamples.Argb -> pictureSrgbCodes(colour, argb)
    is StoredSamples.Rgba16 -> pictureSrgbCodes(colour, rgba)
  }

private fun StoredSamples.Rgba16.wide(at: Int): Long = rgba[at].toLong() and WIDE_MAX.toLong()

// An eight-bit RGBA PNG of [argb] with no colour description in it.
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

// A JPEG XL opens on its codestream's FF 0A, or on the twelve byte signature box of its ISOBMFF container.
private fun ByteArray.isJpegXl(): Boolean = regionMatches(0, JXL_CODESTREAM) || regionMatches(0, JXL_CONTAINER)

// An AVIF or HEIC opens on its `ftyp` box.
private fun ByteArray.isHeif(): Boolean = regionMatches(4, FTYP)

private fun ByteArray.regionMatches(
  offset: Int,
  prefix: ByteArray,
): Boolean = offset + prefix.size <= size && prefix.indices.all { this[offset + it] == prefix[it] }

private val JXL_CODESTREAM = byteArrayOf(0xFF.toByte(), 0x0A)
private val JXL_CONTAINER =
  byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
private val FTYP = "ftyp".encodeToByteArray()

private const val NO_BAND = -1
private const val RGBA = 4
private const val EIGHT_BITS = 8
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val WIDE_BITS = 16
private const val ALPHA_SHIFT = 24
private const val BYTE_MAX = 0xFF
private const val WIDE_MAX = 0xFFFF
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE = 0xFF shl ALPHA_SHIFT

private const val UNDECODED_TAGGED_IMAGE =
  "The overlay image could not be read. It describes its colour, so this backend decodes the picture to draw its " +
    "colours the way the description defines them, and this ffmpeg could not decode it. Check that the bytes are a " +
    "picture this ffmpeg build decodes."

private const val UNDECODED_JPEG_XL =
  "The overlay image could not be read. A JPEG XL keeps its colour description inside its codestream, so this " +
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
 * Why a picture is refused when this process cannot read its bytes and its decode describes its colour.
 */
internal const val UNSEEN_COLOUR =
  "The overlay image could not be drawn in its colour. This process cannot read the picture's bytes, so the colour " +
    "description its container carries is out of reach, and the decode carried one that may not be the " +
    "container's. Use a path or a file URL this process can read."

private const val UNREAD_TAGGED_IMAGE =
  "The overlay image could not be read. It describes its colour, so the picture is decoded to draw its colours " +
    "the way the description defines them, and the JDK could not decode it. This catalogue was built without a " +
    "picture decoder, so it draws a colour-tagged picture only when the JDK reads it. Use a format the JDK reads, " +
    "such as PNG or JPEG, or build the catalogue with the backend's picture decoder."
