package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What every target reports for a still, so a header read only has to answer the bounds and the
 * orientation and the rest is settled here.
 */
class ImageMediaInfoTest {
  @Test
  fun theDeclaredDurationIsWhatTheInfoReports() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "jpeg", 4.seconds)

    assertEquals(4.seconds, info.duration)
  }

  @Test
  fun anUnrotatedStillIsDisplayedAtTheSizeItWasStoredAt() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    assertEquals(PHOTO, video.codedSize)
    assertEquals(PHOTO, video.displaySize)
    assertEquals(0, video.rotationDegrees)
  }

  // displaySize is what a target sizes an output from, so a photo a phone stored sideways has to
  // report the frame it is shown at rather than the one it was written at.
  @Test
  fun aQuarterTurnSwapsTheDisplayedSides() {
    val info = imageMediaInfoOf(PHOTO, ORIENTATION_ROTATE_90, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    assertEquals(PHOTO, video.codedSize)
    assertEquals(Size(PHOTO.height, PHOTO.width), video.displaySize)
    assertEquals(90, video.rotationDegrees)
  }

  @Test
  fun aHalfTurnKeepsTheDisplayedSides() {
    val info = imageMediaInfoOf(PHOTO, ORIENTATION_ROTATE_180, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    assertEquals(PHOTO, video.displaySize)
    assertEquals(180, video.rotationDegrees)
  }

  // Other is what makes videoCodecOf refuse to name a codec, which is what stops a copy being
  // planned over pixels no muxer carries. The format still travels, for a bug report to read.
  @Test
  fun theCodecNamesTheStillFormatAndIsRecognisedAsNothing() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "heic", 4.seconds)
    val video = requireNotNull(info.video)

    assertEquals("heic", video.codec.name)
    assertEquals(CodecKind.Other, video.codec.kind)
  }

  @Test
  fun aFormatNoTargetNamedIsReportedEmptyRatherThanGuessedAt() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "", 4.seconds)

    assertEquals("", requireNotNull(info.video).codec.name)
  }

  @Test
  fun aStillCarriesNoCadenceNoDepthNoRateAndNoAudio() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "png", 4.seconds)
    val video = requireNotNull(info.video)

    assertNull(video.frameRate)
    assertNull(video.bitDepth)
    assertNull(video.bitrate)
    assertNull(video.hdrTransfer)
    assertNull(info.audio)
  }

  @Test
  fun aStillIsSquarePixelledBtSevenOhNineAndExportable() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "png", 4.seconds)
    val video = requireNotNull(info.video)

    assertEquals(1f, video.pixelAspectRatio)
    assertEquals(ColorSpace.Bt709, video.colorSpace)
    assertTrue(info.isExportable)
  }

  // The four mirrored orientations sit in the middle of the range and are the ones a mapping that
  // multiplied the tag out would get wrong. Each reports the turn it shares with its twin.
  @Test
  fun aMirroredOrientationReportsTheTurnItSharesWithItsTwin() {
    assertEquals(imageRotationOf(EXIF_ORIENTATION_NORMAL), imageRotationOf(ORIENTATION_FLIP_HORIZONTAL))
    assertEquals(imageRotationOf(ORIENTATION_ROTATE_180), imageRotationOf(ORIENTATION_FLIP_VERTICAL))
    assertEquals(imageRotationOf(ORIENTATION_ROTATE_90), imageRotationOf(ORIENTATION_TRANSPOSE))
    assertEquals(imageRotationOf(ORIENTATION_ROTATE_270), imageRotationOf(ORIENTATION_TRANSVERSE))
  }

  @Test
  fun everyOrientationTheTagDefinesMapsToAQuarterTurn() {
    val turns = (EXIF_ORIENTATION_NORMAL..ORIENTATION_ROTATE_270).map(::imageRotationOf)

    assertEquals(listOf(0, 0, 180, 180, 90, 90, 270, 270), turns)
  }

  @Test
  fun aTagOutsideTheRangeReadsAsNoRotation() {
    assertEquals(0, imageRotationOf(0))
    assertEquals(0, imageRotationOf(9))
    assertEquals(0, imageRotationOf(-1))
  }

  private companion object {
    /**
     * Landscape, so a quarter turn is observable and the two sides can never be confused.
     */
    val PHOTO = Size(4032, 3024)

    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8
  }
}
