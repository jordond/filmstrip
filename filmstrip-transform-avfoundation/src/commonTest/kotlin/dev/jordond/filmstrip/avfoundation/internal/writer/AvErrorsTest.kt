package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import platform.AVFoundation.AVErrorContentIsNotAuthorized
import platform.AVFoundation.AVErrorContentIsProtected
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorEncoderNotFound
import platform.AVFoundation.AVErrorEncoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentDuration
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentSourceDuration
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentSourceStartTime
import platform.AVFoundation.AVErrorInvalidSourceMedia
import platform.AVFoundation.AVErrorInvalidVideoComposition
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorOperationNotSupportedForAsset
import platform.AVFoundation.AVErrorSessionHardwareCostOverage
import platform.AVFoundation.AVErrorUndecodableMediaData
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVErrorUnsupportedOutputSettings
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSFileWriteNoPermissionError
import platform.Foundation.NSFileWriteOutOfSpaceError
import platform.Foundation.NSLocalizedDescriptionKey
import kotlin.test.Test

/**
 * How an `NSError` off a reader or a writer lands in filmstrip's own error model.
 */
class AvErrorsTest {
  @Test
  fun `a protected or unsupported asset is not exportable`() {
    listOf(AVErrorOperationNotSupportedForAsset, AVErrorContentIsProtected, AVErrorContentIsNotAuthorized).forEach {
      val error =
        av(
          it,
        ).toFilmstripError(
          VideoCodec.H264,
          Reader,
          SOURCE,
          SINK,
        ).shouldBeInstanceOf<FilmstripError.SourceNotExportable>()
      error.message shouldBe DETAIL
    }
  }

  @Test
  fun `a source the decoder cannot read names the source`() {
    listOf(
      AVErrorDecodeFailed,
      AVErrorDecoderNotFound,
      AVErrorUndecodableMediaData,
      AVErrorInvalidSourceMedia,
      AVErrorFileFormatNotRecognized,
      AVErrorFileFailedToParse,
    ).forEach {
      val error =
        av(
          it,
        ).toFilmstripError(
          VideoCodec.H264,
          Writer,
          SOURCE,
          SINK,
        ).shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
      error.codec shouldBe SOURCE
      error.message shouldBe DETAIL
    }
  }

  @Test
  fun `a composition AVFoundation refuses says how its instructions have to tile`() {
    listOf(
      AVErrorInvalidVideoComposition,
      AVErrorInvalidCompositionTrackSegmentDuration,
      AVErrorInvalidCompositionTrackSegmentSourceStartTime,
      AVErrorInvalidCompositionTrackSegmentSourceDuration,
    ).forEach {
      val error =
        av(
          it,
        ).toFilmstripError(
          VideoCodec.H264,
          Writer,
          SOURCE,
          SINK,
        ).shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      error.message shouldContain DETAIL
      error.message shouldContain "tile the whole timeline"
    }
  }

  @Test
  fun `a missing encoder names the codec the plan asked for`() {
    listOf(AVErrorEncoderNotFound, AVErrorUnsupportedOutputSettings).forEach {
      val error =
        av(
          it,
        ).toFilmstripError(VideoCodec.Hevc, Writer, SOURCE, SINK).shouldBeInstanceOf<FilmstripError.NoEncoder>()
      error.codec shouldBe VideoCodec.Hevc
      error.message shouldBe DETAIL
    }
  }

  @Test
  fun `revoked hardware reads as an interrupted export`() {
    listOf(
      AVErrorDecoderTemporarilyUnavailable,
      AVErrorEncoderTemporarilyUnavailable,
      AVErrorSessionHardwareCostOverage,
    ).forEach {
      val error =
        av(
          it,
        ).toFilmstripError(
          VideoCodec.H264,
          Reader,
          SOURCE,
          SINK,
        ).shouldBeInstanceOf<FilmstripError.InterruptedByBackgrounding>()
      error.message shouldBe DETAIL
    }
  }

  @Test
  fun `a full disk is a storage failure with no size to ask for`() {
    val errors =
      listOf(av(AVErrorDiskFull), av(AVErrorMaximumFileSizeReached), cocoa(NSFileWriteOutOfSpaceError))

    errors.forEach {
      val error =
        it
          .toFilmstripError(
            VideoCodec.H264,
            Writer,
            SOURCE,
            SINK,
          ).shouldBeInstanceOf<FilmstripError.InsufficientStorage>()
      error.requiredBytes shouldBe null
      error.message shouldBe DETAIL
    }
  }

  @Test
  fun `a write without permission names the output rather than the source`() {
    val error =
      cocoa(
        NSFileWriteNoPermissionError,
      ).toFilmstripError(VideoCodec.H264, Writer, SOURCE, SINK).shouldBeInstanceOf<FilmstripError.SinkUnwritable>()

    error.sink shouldBe SINK
    error.message shouldBe DETAIL
  }

  @Test
  fun `an unknown code off the reader is a source that could not be read`() {
    val error =
      av(
        AVErrorUnknown,
      ).toFilmstripError(VideoCodec.H264, Reader, SOURCE, SINK).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

    error.source shouldBe SOURCE
    error.message shouldBe DETAIL
  }

  @Test
  fun `an unknown code off the writer carries the platform code`() {
    val error =
      av(
        AVErrorUnknown,
      ).toFilmstripError(VideoCodec.H264, Writer, SOURCE, SINK).shouldBeInstanceOf<FilmstripError.Underlying>()

    error.platformCode shouldBe AVErrorUnknown.toInt()
    error.message shouldBe DETAIL
  }

  @Test
  fun `a code from another domain falls back on the side that reported it`() {
    val foreign = NSError(domain = FOREIGN_DOMAIN, code = FOREIGN_CODE, userInfo = described())

    foreign
      .toFilmstripError(
        VideoCodec.H264,
        Reader,
        SOURCE,
        SINK,
      ).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    foreign
      .toFilmstripError(
        VideoCodec.H264,
        Writer,
        SOURCE,
        SINK,
      ).shouldBeInstanceOf<FilmstripError.Underlying>()
      .platformCode shouldBe
      FOREIGN_CODE.toInt()
  }

  @Test
  fun `a blank description is replaced by the code and the domain`() {
    val blank = NSError(domain = FOREIGN_DOMAIN, code = FOREIGN_CODE, userInfo = described(""))

    val error = blank.toFilmstripError(VideoCodec.H264, Writer, SOURCE, SINK)

    error.message shouldBe "AVFoundation reported error $FOREIGN_CODE in $FOREIGN_DOMAIN."
  }

  private fun av(code: Long): NSError = NSError(domain = AVFoundationErrorDomain, code = code, userInfo = described())

  private fun cocoa(code: Long): NSError = NSError(domain = NSCocoaErrorDomain, code = code, userInfo = described())

  private fun described(detail: String = DETAIL): Map<Any?, Any?> = mapOf(NSLocalizedDescriptionKey to detail)

  private companion object {
    val Reader = FailingSide.Reader
    val Writer = FailingSide.Writer
    const val SOURCE = "/clips/holiday.mov"
    const val SINK = "/exports/holiday.mp4"
    const val DETAIL = "The operation could not be completed."
    const val FOREIGN_DOMAIN = "dev.jordond.filmstrip.test"
    const val FOREIGN_CODE = 4_242L
  }
}
