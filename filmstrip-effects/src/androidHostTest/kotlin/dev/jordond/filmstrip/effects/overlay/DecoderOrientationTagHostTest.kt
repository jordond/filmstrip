package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureWithoutOrientationTag
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

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
        withClue("core reads $name $orientation") { pictureOrientationOf(tagged) shouldBe orientation }

        val input = tagged.forAndroidDecoder(LATEST_API)

        withClue("$name $orientation refused") { input.refusal.shouldBeNull() }
        withClue("$name $orientation") { input.bytes shouldBe pictureWithoutOrientationTag(tagged) }
        withClue("$name $orientation") { pictureOrientationOf(input.bytes) shouldBe PictureOrientation.Normal }
      }
    }
  }

  private companion object {
    const val LATEST_API = 36

    // A start of image marker and an end of image marker, enough for an Exif segment to go between.
    val BARE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
  }
}
