package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail

// The still of [item] with [alpha] as its alpha item, branded for the item's codec.
internal fun alphaStill(
  item: HeifThumbnail,
  alpha: HeifAlphaPlane?,
): ByteArray {
  val brand = if (item.itemType == "av01") "avif" else "heic"
  return HeifStills.still(brand, item.itemType, item.configuration, item.codestream, item.size, alpha = alpha)
}

// The filter ffmpeg converts a picture whose coding is [coding] through, or null when it is decoded from its planes or
// refused.
internal fun convertedFilter(coding: YcbcrCoding?): String? = (pictureRoute(coding) as? PictureRoute.Converted)?.filter
