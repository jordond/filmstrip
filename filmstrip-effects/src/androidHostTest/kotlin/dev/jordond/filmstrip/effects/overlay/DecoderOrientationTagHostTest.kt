package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.internal.pictureOrientationOf
import dev.jordond.filmstrip.internal.pictureWithoutOrientationTag
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.test.OrientedPictures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hands pictures tagged with every orientation to Android's decoder, checking it is given each one with the tag core
 * reads written as 1, so [rasterFor] is the only thing that turns it.
 */
class DecoderOrientationTagHostTest {
  @Test
  fun `the decoder is handed every tagged picture with its tag written as 1`() {
    for (orientation in PictureOrientation.entries) {
      val pictures =
        listOf(
          "PNG" to OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif),
          "JPEG" to OrientedPictures.taggedJpeg(BARE_JPEG, orientation.exif),
          "TIFF" to OrientedPictures.tiff(orientation.exif),
        )
      for ((name, tagged) in pictures) {
        assertEquals(orientation, pictureOrientationOf(tagged), "core reads $name $orientation")

        val input = tagged.forAndroidDecoder(LATEST_API)

        assertNull(input.refusal, "$name $orientation refused")
        assertContentEquals(pictureWithoutOrientationTag(tagged), input.bytes, "$name $orientation")
        assertEquals(PictureOrientation.Normal, pictureOrientationOf(input.bytes), "$name $orientation")
      }
    }
  }

  private companion object {
    const val LATEST_API = 36

    // A start of image marker and an end of image marker, enough for an Exif segment to go between.
    val BARE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
  }
}
