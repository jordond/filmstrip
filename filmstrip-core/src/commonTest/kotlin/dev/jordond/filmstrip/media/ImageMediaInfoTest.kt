package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.color.hdrTransfer
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What every target reports for a still, so a header read only has to answer the bounds and the
 * orientation and the rest is settled here.
 */
class ImageMediaInfoTest {
  @Test
  fun theDeclaredDurationIsWhatTheInfoReports() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "jpeg", 4.seconds)

    info.duration shouldBe 4.seconds
  }

  @Test
  fun anUnrotatedStillIsDisplayedAtTheSizeItWasStoredAt() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    video.codedSize shouldBe PHOTO
    video.displaySize shouldBe PHOTO
    video.rotationDegrees shouldBe 0
  }

  // displaySize is what a target sizes an output from, so a photo a phone stored sideways has to
  // report the frame it is shown at rather than the one it was written at.
  @Test
  fun aQuarterTurnSwapsTheDisplayedSides() {
    val info = imageMediaInfoOf(PHOTO, ORIENTATION_ROTATE_90, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    video.codedSize shouldBe PHOTO
    video.displaySize shouldBe Size(PHOTO.height, PHOTO.width)
    video.rotationDegrees shouldBe 90
  }

  @Test
  fun aHalfTurnKeepsTheDisplayedSides() {
    val info = imageMediaInfoOf(PHOTO, ORIENTATION_ROTATE_180, "jpeg", 4.seconds)
    val video = requireNotNull(info.video)

    video.displaySize shouldBe PHOTO
    video.rotationDegrees shouldBe 180
  }

  // Other is what makes videoCodecOf name no codec, which is what stops a copy being
  // planned over pixels no muxer carries. The format still travels, for a bug report to read.
  @Test
  fun theCodecNamesTheStillFormatAndIsRecognisedAsNothing() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "heic", 4.seconds)
    val video = requireNotNull(info.video)

    video.codec.name shouldBe "heic"
    video.codec.kind shouldBe CodecKind.Other
  }

  @Test
  fun aFormatNoTargetNamedIsReportedEmptyRatherThanGuessedAt() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "", 4.seconds)

    requireNotNull(info.video).codec.name shouldBe ""
  }

  @Test
  fun aStillCarriesNoCadenceNoDepthNoRateAndNoAudio() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "png", 4.seconds)
    val video = requireNotNull(info.video)

    video.frameRate.shouldBeNull()
    video.bitDepth.shouldBeNull()
    video.bitrate.shouldBeNull()
    video.hdrTransfer.shouldBeNull()
    info.audio.shouldBeNull()
  }

  @Test
  fun aStillIsSquarePixelledBtSevenOhNineAndExportable() {
    val info = imageMediaInfoOf(PHOTO, EXIF_ORIENTATION_NORMAL, "png", 4.seconds)
    val video = requireNotNull(info.video)

    video.pixelAspectRatio shouldBe 1f
    video.colorSpace shouldBe ColorSpace.Bt709
    info.isExportable.shouldBeTrue()
  }

  // The four mirrored orientations sit in the middle of the range and are the ones a mapping that
  // multiplied the tag out would get wrong. Each reports the turn it shares with its twin.
  @Test
  fun aMirroredOrientationReportsTheTurnItSharesWithItsTwin() {
    imageRotationOf(ORIENTATION_FLIP_HORIZONTAL) shouldBe imageRotationOf(EXIF_ORIENTATION_NORMAL)
    imageRotationOf(ORIENTATION_FLIP_VERTICAL) shouldBe imageRotationOf(ORIENTATION_ROTATE_180)
    imageRotationOf(ORIENTATION_TRANSPOSE) shouldBe imageRotationOf(ORIENTATION_ROTATE_90)
    imageRotationOf(ORIENTATION_TRANSVERSE) shouldBe imageRotationOf(ORIENTATION_ROTATE_270)
  }

  @Test
  fun everyOrientationTheTagDefinesMapsToAQuarterTurn() {
    val turns = (EXIF_ORIENTATION_NORMAL..ORIENTATION_ROTATE_270).map(::imageRotationOf)

    turns shouldBe listOf(0, 0, 180, 180, 90, 90, 270, 270)
  }

  @Test
  fun aTagOutsideTheRangeReadsAsNoRotation() {
    imageRotationOf(0) shouldBe 0
    imageRotationOf(9) shouldBe 0
    imageRotationOf(-1) shouldBe 0
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
