package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

// [bytes] as [decoder] decodes them, failing with its reason when it decodes nothing.
internal fun decodedBy(
  decoder: FfmpegPictureDecoder,
  bytes: ByteArray,
): DecodedPicture {
  val read = decoder.decode(ImageSource.ofBytes(bytes))
  val why = (read as? OverlayPictureRead.Refused)?.reason ?: (read as? OverlayPictureDecode.Undrawn)?.reason
  return withClue("this ffmpeg decoded nothing: $why") {
    (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
  }
}

// The packed ARGB texel at [x] and [y] of [decoded], which must have come back at eight bits.
internal fun texelOf(
  decoded: DecodedPicture,
  x: Int,
  y: Int,
): Int {
  val argb =
    withClue("an eight-bit still decoded deeper") {
      decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>()
    }.argb
  return argb[y * decoded.size.width + x]
}
