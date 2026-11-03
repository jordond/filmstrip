package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureContainerOf
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf

// Cuts an overlay picture's decoded samples to the rectangle its clap keeps and lays them out by its orientation, both
// read once by core out of the encoded bytes.

/**
 * The turn ffmpeg's graph or core lays a picture of encoded [bytes] out by, after ffmpeg opens it with `-noautorotate`.
 *
 * It is the orientation [pictureOrientationOf] reads, so every backend draws the picture the same way up. A JPEG XL
 * leaves none, since libjxl lays it out by its codestream's orientation whatever ffmpeg asks, measured on ffmpeg 6.1
 * and 8.0. Bytes this process cannot read leave none either, so a URI only ffmpeg opens is drawn as stored.
 */
internal fun orientationLeftOf(bytes: ByteArray?): PictureOrientation =
  if (bytes == null || isJpegXl(bytes)) PictureOrientation.Normal else pictureOrientationOf(bytes)

// Whether [bytes] are a bare or boxed JPEG XL, as core classifies a picture.
internal fun isJpegXl(bytes: ByteArray): Boolean =
  when (pictureContainerOf(bytes)) {
    PictureContainer.JxlCodestream, PictureContainer.JxlBoxed -> true
    else -> false
  }

/**
 * The rectangle of a picture of encoded [bytes] its decode is cut to before [orientationLeftOf] turns it, the one
 * [pictureCropOf] reads out of an AVIF or HEIC's `clap`. Every decoder is handed the picture with its `clap` dropped,
 * so the decode is the whole stored frame.
 *
 * Null for any other picture and for bytes this process cannot read, so a URI only ffmpeg opens is cut by nothing here.
 */
internal fun cropLeftOf(bytes: ByteArray?): PictureCrop? = bytes?.let(::pictureCropOf)

// This picture cut to the rectangle cropLeftOf reads out of bytes and laid out by the turn orientationLeftOf gives, or
// Undrawn with core's sentence when that rectangle does not lie within it.
internal fun StoredPicture.laidOut(bytes: ByteArray?): StoredRead {
  val crop = cropLeftOf(bytes)
  if (crop != null && !crop.liesWithin(size)) return StoredRead.Undrawn(crop.refusalFor(size))
  return StoredRead.Read(cropped(crop).oriented(orientationLeftOf(bytes)))
}

// This picture cut to crop, or as it is when crop is null or keeps all of it.
internal fun StoredPicture.cropped(crop: PictureCrop?): StoredPicture {
  if (crop == null || crop.keepsAll(size)) return this
  val kept =
    when (val stored = samples) {
      is StoredSamples.Argb -> StoredSamples.Argb(croppedSamples(crop, stored.argb, size))
      is StoredSamples.Rgba16 -> StoredSamples.Rgba16(croppedSamples(crop, stored.rgba, size))
    }
  return StoredPicture(kept, crop.size, color, limits)
}

// This picture laid out the way [orientation] shows it.
internal fun StoredPicture.oriented(orientation: PictureOrientation): StoredPicture {
  if (orientation == PictureOrientation.Normal) return this
  val shown =
    when (val stored = samples) {
      is StoredSamples.Argb -> StoredSamples.Argb(orientedSamples(orientation, stored.argb, size))
      is StoredSamples.Rgba16 -> StoredSamples.Rgba16(orientedSamples(orientation, stored.rgba, size))
    }
  return StoredPicture(shown, orientation.orientedSize(size), color, limits)
}
