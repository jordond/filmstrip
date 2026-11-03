package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.describes
import dev.jordond.filmstrip.color.iccChannels
import dev.jordond.filmstrip.picture.bytes.MAX_PROFILE_SIZE
import dev.jordond.filmstrip.picture.png.decodedPngColor

/**
 * Whether [bytes] are a JPEG whose embedded ICC profile is for RGB or grey channels.
 *
 * False for any other picture, a JPEG without a whole profile, and one whose profile is for CMYK or any other color
 * space.
 */
@InternalFilmstripApi
public fun jpegProfileIsRgbOrGrey(bytes: ByteArray): Boolean =
  isJpeg(bytes) &&
    embeddedColor(bytes)?.descriptions?.any { it is Icc && iccChannels(it.profile) != null } == true

/**
 * Wraps the ICC profile a decoder carried for a picture whose container holds none, or returns null when the
 * profile does not describe the picture.
 *
 * A profile describes the picture when it parses as an RGB profile and the decode has three color channels, or as
 * a grey profile and the decode has one. Any other color space, a profile that does not parse, an empty profile
 * and one over 16 MiB return null.
 *
 * @param profile The profile bytes as the decoder handed them over.
 * @param channels How many color channels the decode stores, alpha not counted.
 */
@InternalFilmstripApi
public fun pictureColorFromDecodedProfile(
  profile: ByteArray,
  channels: Int,
): PictureColor? {
  if (profile.size !in 1..MAX_PROFILE_SIZE) return null
  return Icc(profile.copyOf()).takeIf { it.describes(channels) }
}

/**
 * Reads the color of a picture a decoder wrote out as a PNG, from its `cICP` chunk and then its `iCCP` profile,
 * or returns null when it lands exactly as an untagged picture does.
 *
 * A decoder writes `sRGB`, `cHRM` and `gAMA` chunks from what it guessed about the source, so they are never read
 * here. Only `cICP` and `iCCP` carry the source's own color. The first of the two that is understood and fits
 * [channels] wins. A `cICP` whose code points are unspecified defers to `iCCP`, and one whose code points are sRGB
 * or not supported lands the picture as untagged, the same rule [pictureColor] follows.
 *
 * @param png The decoded PNG's bytes.
 * @param channels How many color channels the source picture stores, alpha not counted, since a decoder may
 * write a grey picture out as RGB.
 */
@InternalFilmstripApi
public fun pictureColorFromDecodedPng(
  png: ByteArray,
  channels: Int,
): PictureColor? = decodedPngColor(png)?.let { chooseColor(it.descriptions, channels) }
