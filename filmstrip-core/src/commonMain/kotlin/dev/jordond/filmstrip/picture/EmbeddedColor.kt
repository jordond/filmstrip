package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.describes
import dev.jordond.filmstrip.color.isSrgb
import dev.jordond.filmstrip.color.isSupported
import dev.jordond.filmstrip.color.isUnspecified
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.bytes.profileSize
import dev.jordond.filmstrip.picture.heif.heifColor
import dev.jordond.filmstrip.picture.heif.heifTiled
import dev.jordond.filmstrip.picture.heif.heifYcbcrCoding
import dev.jordond.filmstrip.picture.png.pngColor

/**
 * How an encoded picture describes its color, or null when it lands exactly as an untagged picture does.
 *
 * Reads the description straight out of the container without decoding the picture, taking the first one in the
 * container's order of precedence that is understood and fits the picture's stored channels. A description that is
 * not understood, or that is for a different number of channels, defers to the next. Not understood covers a chunk
 * or box that is short, fails its CRC or breaks its format's rules, and an ICC profile that does not parse as an RGB
 * or grey profile. CICP code points whose primaries or transfer is 2, unspecified, describe nothing and defer too.
 * CICP code points that are understood but not supported do not defer. They end the search and the picture lands as
 * untagged, since the picture said what it is and a later description is only a fallback.
 *
 * - PNG: a `cICP` chunk, then an `iCCP` profile, then an `sRGB` chunk, then `gAMA` and `cHRM`. Only chunks before the
 * first `IDAT` whose CRC holds are read, and a profile inflates to exactly the size its header declares.
 * - AVIF and HEIC: the primary item's `colr` of type `prof` or `rICC`, then its `nclx`, then the same from the first
 * tile the primary item is derived from that has one. Every `ipma` box is read in order and an item's first
 * association of each kind wins. A file without a primary item has no description.
 * - JPEG: the `ICC_PROFILE` `APP2` segments before the first scan, joined in sequence order.
 * - WebP: the `ICCP` chunk of an extended file whose `VP8X` header flags a profile.
 * - TIFF and BigTIFF: tag 34675 of the first image directory.
 *
 * The stored channels come from PNG's color type, the JPEG frame's component count, TIFF's samples per pixel less its
 * extra samples, and the AVIF or HEIC primary item's `pixi`, `av1C` or `hvcC`. A WebP is always color.
 *
 * Null covers no description, an `sRGB` chunk, CICP code points or PNG gamma that are sRGB itself, CICP code points
 * that are not supported, one for a different number of channels, any other format, a truncated or malformed
 * container, and a profile that is empty or over 16 MiB. An ICC profile that is sRGB comes back as itself, and
 * [dev.jordond.filmstrip.color.pictureLight] lands it as untagged. A malformed container returns null rather than
 * throwing.
 */
@InternalFilmstripApi
public fun pictureColor(bytes: ByteArray): PictureColor? {
  val embedded = embeddedColor(bytes) ?: return null
  return chooseColor(embedded.descriptions, embedded.channels)
}

/**
 * The YCbCr matrix and range an AVIF or HEIC lands with, the codec of its primary item, the video signal its
 * codestream states and the depth and layout of its samples, or null for any other picture.
 *
 * The matrix and range are the ones the primary item's `nclx` records, then the first tile's that has one, the same
 * boxes [pictureColor] reads. Whether the box's primaries and transfer are supported, and whether a `prof` wins over
 * it as the picture's color, makes no difference, since the decoder needs the matrix and range either way. Without
 * an `nclx` they are the codestream's own, as [YcbcrCoding] describes. A file without one whose primary item is
 * neither HEVC nor AV1 gives null, and one whose codestream signal does not read still gives its codec.
 *
 * The codec comes from the item type the primary item's `infe` entry names, or its first tile's when the item is
 * derived from tiles such as a `grid`. The `ftyp` brands play no part.
 *
 * The video signal is the item's codestream's, or its first tile's, read without changing which description wins. For
 * HEVC it is the first sequence parameter set in the `hvcC`, or the first at the start of the item's data when the
 * `hvcC` holds none. For AV1 it is the first sequence header at the start of the item's data, or in its `av1C` when
 * the data holds none.
 *
 * The depth and layout are the ones every sequence parameter set or sequence header of the item and its tiles states,
 * in their `hvcC` or `av1C` and at the start of their data, and that those boxes state too. Both are null when any of
 * them does not read or states another depth or layout than the rest.
 */
@InternalFilmstripApi
public fun pictureYcbcrCoding(bytes: ByteArray): YcbcrCoding? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { heifYcbcrCoding(picture) }
}

/**
 * Whether [bytes] are an AVIF or HEIC whose primary item is derived from tiles, such as a `grid`, rather than coded
 * as one image.
 *
 * False for any other picture and for a file that is truncated, malformed or has no primary item.
 */
@InternalFilmstripApi
public fun heifPrimaryIsTiled(bytes: ByteArray): Boolean {
  val picture = ByteView(bytes, bytes.size)
  return picture.container() == PictureContainer.Heif && deferring { heifTiled(picture) } == true
}

/**
 * Every color description a container carries, in its order of precedence, and how many color channels it stores,
 * or null when that count is unknown.
 */
internal class EmbeddedColor(
  val descriptions: List<PictureColor>,
  val channels: Int?,
)

/**
 * The first of [descriptions] that fits [channels] and is not unspecified CICP code points, or null when none does or
 * the one that fits is sRGB itself or CICP code points that are not supported.
 */
internal fun chooseColor(
  descriptions: List<PictureColor>,
  channels: Int?,
): PictureColor? =
  descriptions
    .firstOrNull { it.describes(channels) && !(it is Cicp && it.isUnspecified) }
    ?.takeUnless { it.isSrgb || (it is Cicp && !it.isSupported) }

internal fun iccDescription(profile: ByteArray?): Icc? = profile?.takeIf { it.isNotEmpty() }?.let { Icc(it) }

/**
 * Reads the color descriptions out of [bytes], or returns null for any other format and a malformed container.
 */
internal fun embeddedColor(bytes: ByteArray): EmbeddedColor? {
  val picture = ByteView(bytes, bytes.size)
  return try {
    when (picture.container()) {
      PictureContainer.Png -> pngColor(picture, decoded = false)
      PictureContainer.Jpeg -> jpegColor(picture)
      PictureContainer.Webp -> webpColor(picture)
      PictureContainer.Heif -> heifColor(picture)
      PictureContainer.Tiff -> tiffColor(picture)
      PictureContainer.JxlCodestream, PictureContainer.JxlBoxed, null -> null
    }
  } catch (_: MalformedPicture) {
    null
  }
}

internal fun isJpeg(bytes: ByteArray): Boolean = ByteView(bytes, bytes.size).container() == PictureContainer.Jpeg

private fun jpegColor(jpeg: ByteView): EmbeddedColor {
  val starts = IntArray(MAX_JPEG_SEGMENTS + 1)
  val ends = IntArray(MAX_JPEG_SEGMENTS + 1)
  var count = 0
  var found = 0
  var whole = true
  var channels: Int? = null
  walkJpeg(jpeg) { marker, _, dataStart, end ->
    if (marker in SOF0..SOF15 && marker !in NOT_FRAMES && channels == null) {
      channels = jpeg.within(end).u8(dataStart + 5)
    }
    if (whole && jpeg.isProfileSegment(marker, dataStart, end)) {
      val sequence = jpeg.u8(dataStart + ICC_PROFILE.size)
      val total = jpeg.u8(dataStart + ICC_PROFILE.size + 1)
      if (sequence !in 1..total || (count != 0 && total != count) || ends[sequence] != 0) {
        whole = false
      } else {
        count = total
        starts[sequence] = dataStart + ICC_PROFILE.size + 2
        ends[sequence] = end
        found++
      }
    }
  }
  if (!whole || found == 0 || found != count) return EmbeddedColor(emptyList(), channels)
  val profile = ByteArray(profileSize((1..count).sumOf { (ends[it] - starts[it]).toLong() }))
  var at = 0
  for (sequence in 1..count) {
    at = jpeg.copyInto(profile, at, starts[sequence], ends[sequence])
  }
  return EmbeddedColor(listOfNotNull(iccDescription(profile)), channels)
}

/**
 * [bytes] with every `APP2` segment that carries part of an ICC profile left out, or [bytes] itself when it is not a
 * JPEG.
 *
 * The segments are walked up to the first scan the way [pictureColor] walks them. Everything else is kept byte for
 * byte, fill bytes and other `APP2` segments such as MPF among it. A segment whose length runs past the end of the
 * picture or cannot hold itself ends the walk there, and the rest is kept as it stands.
 */
@InternalFilmstripApi
public fun jpegWithoutProfile(bytes: ByteArray): ByteArray {
  val jpeg = ByteView(bytes, bytes.size)
  if (jpeg.container() != PictureContainer.Jpeg) return bytes
  val cuts = mutableListOf<IntRange>()
  try {
    walkJpeg(jpeg) { marker, start, dataStart, end ->
      if (jpeg.isProfileSegment(marker, dataStart, end)) cuts += start until end
    }
  } catch (_: MalformedPicture) {
    // The segments walked so far are cut and the rest is kept.
  }
  if (cuts.isEmpty()) return bytes
  val kept = ByteArray(bytes.size - cuts.sumOf { it.last - it.first + 1 })
  var from = 0
  var at = 0
  for (cut in cuts) {
    bytes.copyInto(kept, at, from, cut.first)
    at += cut.first - from
    from = cut.last + 1
  }
  bytes.copyInto(kept, at, from, bytes.size)
  return kept
}

/**
 * Hands [visit] each marker segment ahead of the first scan, with its marker, where its marker's 0xFF sits, where its
 * data starts and where it ends. Fill bytes ahead of a marker are skipped, and a segment that runs past the end or
 * cannot hold its own length throws [MalformedPicture].
 */
internal inline fun walkJpeg(
  jpeg: ByteView,
  visit: (marker: Int, start: Int, dataStart: Int, end: Int) -> Unit,
) {
  var pos = 2
  while (true) {
    if (jpeg.u8(pos) != 0xFF) malformed()
    while (jpeg.u8(pos) == 0xFF) pos++
    val start = pos - 1
    val marker = jpeg.u8(pos++)
    if (marker == SOS || marker == EOI) return
    if (marker in RST0..RST7 || marker == TEM) continue
    val length = jpeg.u16(pos)
    if (length < 2) malformed()
    val end = jpeg.span(pos, length.toLong())
    visit(marker, start, pos + 2, end)
    pos = end
  }
}

/**
 * Whether the segment of [marker] whose data runs from [dataStart] to [end] is an `APP2` segment carrying part of an
 * ICC profile, its signature followed by its sequence number and the segment count.
 */
internal fun ByteView.isProfileSegment(
  marker: Int,
  dataStart: Int,
  end: Int,
): Boolean = marker == APP2 && end - dataStart >= ICC_PROFILE.size + 2 && matches(dataStart, ICC_PROFILE)

private fun webpColor(file: ByteView): EmbeddedColor {
  val webp = file.within(minOf(8 + file.u32(4, little = true), file.end.toLong()).toInt())
  var pos = 12
  if (webp.u32(pos).toInt() != WEBP_VP8X || webp.u8(pos + 8) and VP8X_ICC_FLAG == 0) {
    return EmbeddedColor(emptyList(), WEBP_CHANNELS)
  }
  while (true) {
    val size = webp.u32(pos + 4, little = true)
    val end = webp.span(pos + 8, size)
    if (webp.u32(pos).toInt() == ICCP_WEBP) {
      return EmbeddedColor(listOfNotNull(iccDescription(webp.profile(pos + 8, end))), WEBP_CHANNELS)
    }
    pos = end + (size and 1L).toInt()
  }
}

private fun tiffColor(tiff: ByteView): EmbeddedColor {
  val little = tiff.u8(0) == 'I'.code
  val big = tiff.u16(2, little) == BIG_TIFF
  val entrySize = if (big) 20 else 12
  val inlineSize = if (big) 8 else 4
  val directory =
    if (big) {
      if (tiff.u16(4, little) != 8) malformed()
      tiff.offset(tiff.u64(8, little))
    } else {
      tiff.offset(tiff.u32(4, little))
    }
  val entries = if (big) tiff.u64(directory, little) else tiff.u16(directory, little).toLong()
  var profile: ByteArray? = null
  var samples = 1L
  var extraSamples = 0L
  var palette = false
  var entry = directory + if (big) 8 else 2
  var remaining = entries
  while (remaining-- > 0) {
    tiff.span(entry, entrySize.toLong())
    val type = tiff.u16(entry + 2, little)
    val length = if (big) tiff.u64(entry + 4, little) else tiff.u32(entry + 4, little)
    val value = entry + if (big) 12 else 8
    when (tiff.u16(entry, little)) {
      ICC_TAG -> {
        if (profile == null && type in ICC_TAG_TYPES) {
          val start =
            when {
              length <= inlineSize -> value
              big -> tiff.offset(tiff.u64(value, little))
              else -> tiff.offset(tiff.u32(value, little))
            }
          profile = tiff.profile(start, tiff.span(start, length))
        }
      }
      SAMPLES_PER_PIXEL -> {
        samples = tiff.shortOrLong(value, type, little) ?: samples
      }
      EXTRA_SAMPLES -> {
        extraSamples = length
      }
      PHOTOMETRIC -> {
        palette = tiff.shortOrLong(value, type, little) == PHOTOMETRIC_PALETTE
      }
    }
    entry += entrySize
  }
  val channels = if (palette) 3L else samples - extraSamples
  return EmbeddedColor(listOfNotNull(iccDescription(profile)), channels.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
}

private fun ByteView.shortOrLong(
  at: Int,
  type: Int,
  little: Boolean,
): Long? =
  when (type) {
    TIFF_SHORT -> u16(at, little).toLong()
    TIFF_LONG -> u32(at, little)
    else -> null
  }

private val ICC_PROFILE = ascii("ICC_PROFILE\u0000")
internal val ICCP_WEBP = fourcc("ICCP")

private const val SOS = 0xDA
private const val EOI = 0xD9
private const val APP2 = 0xE2
private const val SOF0 = 0xC0
private const val SOF15 = 0xCF

// Markers in the frame range that start no frame: DHT, JPG and DAC.
private val NOT_FRAMES = setOf(0xC4, 0xC8, 0xCC)
private const val MAX_JPEG_SEGMENTS = 255
private const val RST0 = 0xD0
private const val RST7 = 0xD7
private const val TEM = 0x01
internal const val VP8X_ICC_FLAG = 0x20
private const val WEBP_CHANNELS = 3
private const val BIG_TIFF = 43
private const val ICC_TAG = 34675
private const val SAMPLES_PER_PIXEL = 277
private const val EXTRA_SAMPLES = 338
private const val PHOTOMETRIC = 262
private const val PHOTOMETRIC_PALETTE = 3L
private const val TIFF_SHORT = 3
private const val TIFF_LONG = 4
private val ICC_TAG_TYPES = setOf(1, 7)
