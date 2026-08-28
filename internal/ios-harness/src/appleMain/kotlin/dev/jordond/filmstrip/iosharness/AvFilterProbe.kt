package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.insertTimeRange
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoComposition
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGSize
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * Does the Core Image filter handler reach Kotlin, and what does it hand over?
 *
 * Two questions the Apple export backend cannot be designed around until they are answered.
 *
 * The first is mechanical. `videoCompositionWithAsset:applyingCIFiltersWithHandler:` is the graph
 * mechanism that avoids registering an Objective-C class with AVFoundation, which is the one thing
 * Kotlin/Native cannot do. It is only usable if the block bridges to a Kotlin lambda and the
 * request's four members come through with it.
 *
 * The second decides what a normalised effect parameter is measured against. When one composition
 * track holds clips of different natural sizes, either the handler sees each clip at its own size
 * or AVFoundation has already fitted it to the track's. The planner measures each clip's effects
 * against that clip's own frame, so the second answer would make every clip but the first wrong.
 */
@OptIn(ExperimentalForeignApi::class)
public class AvFilterProbe {
  /**
   * One frame, as the handler saw it.
   *
   * @property compositionTimeSeconds Where in the composition the frame sits.
   * @property sourceWidth The width of the image handed to the handler.
   * @property sourceHeight The height of the image handed to the handler.
   * @property renderWidth The width the composition says it is rendering at.
   * @property renderHeight The height the composition says it is rendering at.
   */
  public class Observation(
    public val compositionTimeSeconds: Double,
    public val sourceWidth: Double,
    public val sourceHeight: Double,
    public val renderWidth: Double,
    public val renderHeight: Double,
  ) {
    override fun toString(): String =
      "t=${compositionTimeSeconds.format()} source=${sourceWidth.format()}x${sourceHeight.format()} " +
        "render=${renderWidth.format()}x${renderHeight.format()}"
  }

  /**
   * The handler never ran, or the pipeline refused to start.
   */
  public class ProbeFailure(
    public val reason: String,
  )

  /**
   * Result of one probe run.
   */
  public class ProbeResult(
    public val observations: List<Observation>,
    public val failure: ProbeFailure?,
  )

  private val observations = mutableListOf<Observation>()

  /**
   * Concatenates [first] and [second] into one video track and pulls [frames] frames through a
   * filter handler, recording what it was given.
   *
   * @param first Path to the first clip.
   * @param second Path to the second clip, ideally a different size from [first].
   * @param frames How many frames to pull before stopping.
   */
  public fun run(
    first: String,
    second: String,
    frames: Int,
  ): ProbeResult {
    observations.clear()

    val composition = AVMutableComposition()
    val track =
      composition.addMutableTrackWithMediaType(AVMediaTypeVideo, PREFERRED_TRACK_ID)
        ?: return failed("the composition refused a video track")

    var cursor = CMTimeMake(0, TIMESCALE)
    listOf(first, second).forEach { path ->
      val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      val source =
        (asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack)
          ?: return failed("no video track in $path")
      val inserted =
        track.insertTimeRange(
          timeRange = CMTimeRangeMake(CMTimeMake(0, TIMESCALE), asset.duration),
          ofTrack = source,
          atTime = cursor,
          error = null,
        )
      if (!inserted) return failed("could not insert $path")
      cursor = composition.duration
    }

    // The block under test. Everything it touches is recorded, then the frame is passed through
    // untouched so the reader keeps pulling.
    val videoComposition =
      AVMutableVideoComposition.videoCompositionWithAsset(composition) { request ->
        val filtering = request ?: return@videoCompositionWithAsset
        val source = filtering.sourceFrame()
        observations +=
          Observation(
            compositionTimeSeconds = CMTimeGetSeconds(filtering.compositionTime),
            sourceWidth = source.extent.width(),
            sourceHeight = source.extent.height(),
            renderWidth = filtering.renderSize.width(),
            renderHeight = filtering.renderSize.height(),
          )
        filtering.finishWithFrame(source)
      }

    val reader = AVAssetReader(asset = composition, error = null)
    val output =
      AVAssetReaderVideoCompositionOutput(
        videoTracks = composition.tracksWithMediaType(AVMediaTypeVideo),
        // The CoreVideo constants are CFStringRef, which does not bridge into a Kotlin map as an
        // NSString key: AVFoundation rejects the dictionary as having no recognised keys. The
        // literal is the documented value of kCVPixelBufferPixelFormatTypeKey.
        videoSettings = mapOf<Any?, Any?>(PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt()),
      )
    output.videoComposition = videoComposition
    if (!reader.canAddOutput(output)) return failed("the reader refused the composition output")
    reader.addOutput(output)
    if (!reader.startReading()) return failed("startReading failed: ${reader.error?.localizedDescription}")

    var pulled = 0
    while (pulled < frames) {
      val buffer = output.copyNextSampleBuffer() ?: break
      platform.CoreFoundation.CFRelease(buffer)
      pulled++
    }
    reader.cancelReading()

    return ProbeResult(observations.toList(), null)
  }

  private fun failed(reason: String) = ProbeResult(observations.toList(), ProbeFailure(reason))

  private companion object {
    const val PREFERRED_TRACK_ID = 0
    const val TIMESCALE = 600
    const val PIXEL_FORMAT_KEY = "PixelFormatType"
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CValue<CGRect>.width(): Double = useContents { size.width }

@OptIn(ExperimentalForeignApi::class)
private fun CValue<CGRect>.height(): Double = useContents { size.height }

@OptIn(ExperimentalForeignApi::class)
private fun CValue<CGSize>.width(): Double = useContents { width }

@OptIn(ExperimentalForeignApi::class)
private fun CValue<CGSize>.height(): Double = useContents { height }

private fun Double.format(): String = ((this * 10).toLong() / 10.0).toString()
