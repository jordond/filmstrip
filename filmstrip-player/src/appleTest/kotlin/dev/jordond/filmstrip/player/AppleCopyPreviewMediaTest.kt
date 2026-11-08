package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewPlan
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVComposition
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.duration
import platform.AVFoundation.segments
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The frames a preview of a copy that ends before its source does shows, against the count the plan settled for the
 * export.
 *
 * The long fixture reorders two B-frames behind each reference, so one window ends on a whole reorder group and the
 * other inside one. A preview that laid the clip's whole span would show a frame past the grid the export never
 * writes on the first. On the second the export also writes references presented past the clip's end, which the
 * preview leaves out so it ends where the clip does.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleCopyPreviewMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  // The frames a decoding read hands back are the ones the preview shows, one for each slot of the grid.
  @Test
  fun `a copy's preview shows the frames its export writes on the grid`() =
    contractTest(timeout = TIMEOUT) {
      listOf(WHOLE_GROUP_END, SPLIT_GROUP_END).forEach { end ->
        val plan = previewOf(end)
        plan.resolved.copyPrefix.shouldNotBeNull()

        val shown = shownFramesOf(plan.resolved.toAvComposition().composition)

        withClue("the preview of a copy to $end showed $shown") {
          shown.size.toLong() shouldBe plan.resolved.videoSlots
          shown.distinct().size shouldBe shown.size
        }
      }
    }

  // The window ending inside a reorder group writes references presented past the clip's end. The preview stops on
  // that end and holds the last frame on the grid up to it.
  @Test
  fun `a copy's preview ends where its clip does`() =
    contractTest(timeout = TIMEOUT) {
      val plan = previewOf(SPLIT_GROUP_END)
      val counted = plan.resolved.copyPrefix.shouldNotBeNull()
      withClue("the window was meant to write frames past the grid") {
        (counted.count > plan.resolved.videoSlots).shouldBeTrue()
      }
      val composition = plan.resolved.toAvComposition().composition
      val last = shownFramesOf(composition).last()
      val held = last + (plan.resolved.duration - last) / 2

      composition.duration.toDuration() shouldBe plan.resolved.duration
      withClue("a seek to $held, after the last frame on the grid at $last") {
        shownAt(composition, held) shouldBe shownAt(composition, last)
      }
    }

  // The window's audio runs past the last frame the copy writes, and the picture holds that frame over the rest.
  @Test
  fun `a copy's preview holds its last frame under the audio`() =
    contractTest(timeout = TIMEOUT) {
      listOf(WHOLE_GROUP_END, SPLIT_GROUP_END).forEach { end ->
        val plan = previewOf(end)
        val clip =
          plan.resolved.tracks
            .first()
            .clips
            .first()

        val track = videoTrackOf(plan.resolved.toAvComposition().composition)

        withClue("the preview of a copy to $end") {
          (CMTimeRangeGetEnd(track.timeRange).toDuration() >= clip.outputDuration).shouldBeTrue()
          track.segments
            .filterIsInstance<AVAssetTrackSegment>()
            .none { it.empty }
            .shouldBeTrue()
        }
      }
    }

  private suspend fun previewOf(end: Duration): PreviewPlan {
    val source = MediaSource.of(appleFixture(LONG))
    val composition = compositionOf { clip(source) { trim(TimeRange.of(Duration.ZERO, end)) } }
    val plan =
      AvPreviewPlanner(CONTRACT_COMPONENTS)
        .plan(composition, PreviewQualityPolicy.Full, ExportSpec())
        .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
        .plan
    withClue("an untouched window from the source's start is meant to copy") {
      plan.resolved.path shouldBe ExportPath.Copy
    }
    return plan
  }

  private fun videoTrackOf(composition: AVComposition): AVAssetTrack =
    composition.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
      ?: fail("the preview laid no video track")

  // Decoded, so a sample the composition reads only to decode another is left out, the way a player leaves it out.
  private fun shownFramesOf(composition: AVComposition): List<Duration> {
    val output =
      AVAssetReaderTrackOutput(
        track = videoTrackOf(composition),
        outputSettings =
          NSDictionary.dictionaryWithObject(
            NSNumber.numberWithUnsignedInt(kCVPixelFormatType_32BGRA),
            forKey = NSString.create(string = "PixelFormatType"),
          ),
      )
    val reader = AVAssetReader.assetReaderWithAsset(composition, error = null) ?: fail("no reader for the preview")
    reader.addOutput(output)
    if (!reader.startReading()) fail("the reader refused to start: ${reader.error?.localizedDescription}")
    try {
      return buildList {
        while (true) {
          val buffer = output.copyNextSampleBuffer() ?: break
          try {
            if (CMSampleBufferGetImageBuffer(buffer) != null) {
              add(CMSampleBufferGetPresentationTimeStamp(buffer).toDuration())
            }
          } finally {
            CFRelease(buffer)
          }
        }
      }
    } finally {
      reader.cancelReading()
    }
  }

  // The pixels a seek to [at] shows, read with no tolerance either side the way a scrub is.
  @Suppress("DEPRECATION")
  private fun shownAt(
    composition: AVComposition,
    at: Duration,
  ): List<Byte> {
    val generator = AVAssetImageGenerator(asset = composition)
    generator.requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
    generator.requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
    val image = generator.copyCGImageAtTime(at.toCMTime(), actualTime = null, error = null)
    image ?: fail("the preview has no frame at $at")
    try {
      val data = CGDataProviderCopyData(CGImageGetDataProvider(image)) ?: fail("the frame at $at has no pixels")
      try {
        val bytes = CFDataGetBytePtr(data) ?: fail("the frame at $at has no pixels")
        return bytes.readBytes(CFDataGetLength(data).toInt()).toList()
      } finally {
        CFRelease(data)
      }
    } finally {
      CGImageRelease(image)
    }
  }

  private companion object {
    const val LONG = "apple_export_long.mp4"

    val TIMEOUT = 2.seconds * 60

    // Thirty slots close on a whole reorder group, and thirty one close with the frame on the last of them decoding
    // behind two references presented past it.
    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds
  }
}
