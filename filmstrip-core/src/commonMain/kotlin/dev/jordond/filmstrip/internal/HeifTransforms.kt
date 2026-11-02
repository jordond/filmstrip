package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PictureCrop
import dev.jordond.filmstrip.media.PictureOrientation

// Reads the properties an AVIF or HEIC item lists, its ispe sizes, irot turns, imir mirrors, clap crops and iscl
// scales, in the order its associations list them.

/**
 * What the transformative properties of one AVIF or HEIC item do, each applied to the picture the ones listed ahead of
 * it give.
 *
 * @property orientation Every `irot` turn and `imir` mirror folded into the one orientation they show the item in.
 * @property scalesTurned Whether an `iscl` is listed where the `irot` and `imir` ahead of it fold into another
 * orientation than [PictureOrientation.Normal], so it scales a turned or mirrored picture rather than the stored one.
 * Turns ahead of it that cancel out, an `irot` of 0 among them, leave it scaling the stored picture.
 * @property crop The texels of the stored picture libheif keeps once every `clap` has cropped the frame the
 * properties ahead of it leave, as [clapCrop] reads each one and [narrowedTo] carries it back to the stored picture.
 * It is the whole frame [heifTransformsOf] was given when nothing crops it. Null without a frame, or when a `clap`
 * does not read or libheif refuses it.
 * @property clamped Whether a `clap` reaches past the frame it crops, where libheif decodes a smaller picture than the
 * size it reports.
 * @property size The stored size ahead of any turn or mirror, the size of [crop]. Null when [crop] is or [clamped] is
 * set.
 */
internal class HeifTransforms(
  val orientation: PictureOrientation,
  val scalesTurned: Boolean,
  val crop: PictureCrop?,
  val clamped: Boolean,
) {
  val size: Size? get() = crop?.takeUnless { clamped }?.size
}

/**
 * The [HeifTransforms] of an item whose associations list [properties] in this order, each read out of this view,
 * cropped from [frame] when it is set.
 *
 * Every `irot` and `imir` counts, not only the first of each. A `clap` crops the frame the turns and mirrors ahead of
 * it show, and `iscl` never changes it. Properties of any other type are passed over. An `irot` or `imir` with no byte
 * to read throws [MalformedPicture], and so does a `clap` too short for its fields when there is a frame.
 */
internal fun ByteView.heifTransformsOf(
  properties: Sequence<Box>,
  frame: Size? = null,
): HeifTransforms {
  var turns = 0
  var axis: Int? = null
  var scalesTurned = false
  var crop = frame?.let { PictureCrop(0, 0, it) }
  var clamped = false
  for (property in properties) {
    when (property.type) {
      IROT -> {
        val turn = within(property.end).u8(property.start) and 3
        // A mirror ahead of an odd number of quarter turns is the mirror on the other axis after them.
        if (turn and 1 == 1) axis = axis?.xor(1)
        turns = (turns + turn) and 3
      }
      IMIR -> {
        val mirror = within(property.end).u8(property.start) and 1
        // The same mirror twice undoes itself, and one on each axis is a half turn.
        if (axis != null && axis != mirror) turns = (turns + 2) and 3
        axis = if (axis == null) mirror else null
      }
      ISCL -> {
        scalesTurned = scalesTurned || turns != 0 || axis != null
      }
      CLAP -> {
        val uncropped = crop ?: continue
        val orientation = heifOrientationOf(turns, axis)
        val shown = orientation.orientedSize(uncropped.size)
        val kept = clapCrop(property, shown)
        clamped = clamped || kept?.clamped == true
        crop = kept?.let { uncropped.narrowedTo(it, orientation, shown) }
      }
    }
  }
  return HeifTransforms(heifOrientationOf(turns, axis), scalesTurned, crop, clamped)
}

/**
 * Every property the first entry for [item] across [ipmas] associates with it, in the order it lists them, each looked
 * up in [properties], the boxes of the `ipco` those `ipma` boxes index into. Null when no entry lists the item or the
 * entry lists an `ispe` above version 0, both of which libheif refuses.
 *
 * Only that first entry counts, even when a later entry or `ipma` box lists the item again, and it holds at most 255
 * associations. An index past the end of [properties] throws [MalformedPicture], and so does an `ispe` with no version
 * to read.
 */
internal fun ByteView.heifListing(
  ipmas: List<Box>,
  item: Long,
  properties: List<Box>,
): List<Box>? {
  for (ipma in ipmas) {
    val box = within(ipma.end)
    var listing: List<Box>? = null
    box.forEachEntry(ipma) { entry, first, count ->
      if (entry == item) {
        val indices = List(count) { box.associationIndex(ipma, first, it) }.filter { it != 0 }
        listing = indices.map { index -> properties.getOrElse(index - 1) { malformed() } }
      }
      entry != item
    }
    val listed = listing ?: continue
    return listed.takeIf { it.none { property -> unsupportedIspe(property) } }
  }
  return null
}

private fun ByteView.unsupportedIspe(property: Box): Boolean =
  property.type == ISPE && within(property.end).u8(property.start) != 0

/**
 * The stored size an item whose properties are [listing] is shown at ahead of any turn or mirror, starting from the
 * last `ispe` in it and cropped by [heifTransformsOf].
 *
 * Null without an `ispe`, for any `ispe` with a zero side and for a last one with a side that does not fit an Int. An
 * `ispe` shorter than its fields throws [MalformedPicture].
 */
internal fun ByteView.heifExtentOf(listing: List<Box>): Size? =
  heifFrameOf(listing)?.let { heifTransformsOf(listing.asSequence(), it).size }

/**
 * The texels of the stored picture libheif keeps of an item whose properties are [listing], starting from the frame
 * of the last `ispe` in it and cropped by [heifTransformsOf], clamped crops included. Null where [heifExtentOf] has no
 * frame and where [HeifTransforms.crop] is null.
 */
internal fun ByteView.heifCropOf(listing: List<Box>): PictureCrop? =
  heifFrameOf(listing)?.let { heifTransformsOf(listing.asSequence(), it).crop }

// The last ispe's frame, null as heifExtentOf says.
private fun ByteView.heifFrameOf(listing: List<Box>): Size? {
  var frame: Size? = null
  for (ispe in listing) {
    if (ispe.type != ISPE) continue
    val box = within(ispe.end)
    val width = box.u32(ispe.start + 4)
    val height = box.u32(ispe.start + 8)
    if (width == 0L || height == 0L) return null
    frame = if (width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) Size(width.toInt(), height.toInt()) else null
  }
  return frame
}

private val ISPE = fourcc("ispe")
internal val IROT = fourcc("irot")
internal val IMIR = fourcc("imir")
internal val CLAP = fourcc("clap")
internal val ISCL = fourcc("iscl")
