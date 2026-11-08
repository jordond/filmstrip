package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.container

/**
 * How a coded image item of an AVIF or HEIC is coded, as [heifCodedImages] reads it from its item type.
 */
@InternalFilmstripApi
public enum class HeifCodec {
  /**
   * An `hvc1` or `hev1` item, its sample length prefixed NAL units.
   */
  Hevc,

  /**
   * An `av01` item, its sample OBUs in the low overhead format.
   */
  Av1,

  /**
   * A `jpeg` item, its sample a whole JPEG.
   */
  Jpeg,
}

/**
 * One coded image item of an AVIF or HEIC, its data and the decoder configuration it is decoded with.
 *
 * @property item The item's id.
 * @property codec How the item is coded.
 * @property configuration The payload of the item's `hvcC` or `av1C`, the record that follows the box header, or
 * empty for a [HeifCodec.Jpeg] item.
 * @property sample The item's data, every extent `iloc` lists for it joined in order.
 */
@InternalFilmstripApi
public class HeifCodedImage(
  public val item: Long,
  public val codec: HeifCodec,
  public val configuration: ByteArray,
  public val sample: ByteArray,
)

/**
 * The coded image items [items] name in the AVIF or HEIC [bytes], in the order of [items], or null when one of them
 * cannot be read out of the file.
 *
 * Each item is read once however often [items] names it, and every place it is named holds that one [HeifCodedImage].
 *
 * Each item's data is read where `iloc` places it, in the file or in `idat`, and its configuration is the first `hvcC`
 * or `av1C` its codec takes that the `ipma` boxes associate with it. A file that is not an AVIF or HEIC, is truncated
 * or malformed gives null. So does an item that is not `hvc1`, `hev1`, `av01` or `jpeg`, one whose data sits in
 * another file or is built by another construction method, an HEVC or AV1 item with no configuration, and one whose
 * extents together come to more bytes than the whole file, which only extents read more than once can.
 */
@InternalFilmstripApi
public fun heifCodedImages(
  bytes: ByteArray,
  items: List<Long>,
): List<HeifCodedImage>? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.codedImages(items) }
}

private fun ByteView.codedImages(items: List<Long>): List<HeifCodedImage>? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val locations = parts.locations ?: return null
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  val distinct = items.toSet()
  val configurations = heif.configurations(distinct, parts)
  val read =
    distinct.associateWith { item ->
      val codec = codecNamed(types[item]) ?: return null
      val configuration =
        when (codec) {
          HeifCodec.Jpeg -> ByteArray(0)
          else -> configurations[item to configurationType(codec)]?.let { bytes.copyOfRange(it.start, it.end) }
        } ?: return null
      HeifCodedImage(item, codec, configuration, sampleOf(locations, parts.idat, item) ?: return null)
    }
  return items.map(read::getValue)
}

// The first av1C and the first hvcC the ipma boxes associate with each of [wanted], keyed by item and box type.
private fun ByteView.configurations(
  wanted: Set<Long>,
  parts: MetaParts,
): Map<Pair<Long, Int>, Box> {
  val listed = parts.properties
  val found = mutableMapOf<Pair<Long, Int>, Box>()
  for (ipma in parts.ipmas) {
    within(ipma.end).forEachAssociation(ipma) { item, index ->
      val property = listed.getOrNull(index - 1)
      if (item in wanted && property != null && (property.type == AV1C || property.type == HVCC)) {
        found.getOrPut(item to property.type) { property }
      }
    }
  }
  return found
}

// Every extent of [item]'s data joined in order, or null when it is not in the file or idat.
private fun ByteView.sampleOf(
  locations: ItemLocations,
  idat: Box?,
  item: Long,
): ByteArray? {
  val location = locations.location(item) ?: return null
  if (location.external || location.method > 1 || location.extents.isEmpty()) return null
  val spans = location.extents.map { extentBytes(location, it, idat) }
  val length = spans.sumOf { (start, stop) -> (stop - start).toLong() }
  if (length > bytes.size) return null
  val sample = ByteArray(length.toInt())
  var at = 0
  for ((start, stop) in spans) {
    bytes.copyInto(sample, at, start, stop)
    at += stop - start
  }
  return sample
}

private fun codecNamed(itemType: Int?): HeifCodec? =
  when (itemType) {
    HVC1, HEV1 -> HeifCodec.Hevc
    AV01 -> HeifCodec.Av1
    JPEG -> HeifCodec.Jpeg
    else -> null
  }

private fun configurationType(codec: HeifCodec): Int = if (codec == HeifCodec.Av1) AV1C else HVCC

private val HVC1 = fourcc("hvc1")
private val HEV1 = fourcc("hev1")
private val AV01 = fourcc("av01")
private val JPEG = fourcc("jpeg")
