package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.playback.contract.CONTRACT_TIMEOUT
import dev.jordond.filmstrip.playback.contract.awaitStep
import dev.jordond.filmstrip.test.DEFAULT_MIN_PSNR_DB
import dev.jordond.filmstrip.test.DEFAULT_MIN_SSIM
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.compareFrames
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetImageGeneratorSucceeded
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoComposition
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.setColorPrimaries
import platform.AVFoundation.setColorTransferFunction
import platform.AVFoundation.setColorYCbCrMatrix
import platform.AVFoundation.valueWithCMTime
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreGraphics.CGImageRetain
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import kotlin.coroutines.resume
import kotlin.test.fail
import kotlin.time.Duration

/**
 * The frame an export of [composition] draws at [position], for an edit showing the fixture clip
 * there.
 *
 * This is [appleExportFrame] on any machine where AVFoundation's reader and image generator are
 * handed the same source frame. Where they are not, the export's own lowering is drawn through an
 * image generator instead, so the comparison still holds the preview's graph to the export's graph
 * without also measuring how the two AVFoundation paths convert the clip's colour.
 */
internal suspend fun appleFixtureExportFrame(
  composition: EditComposition,
  position: Duration,
): TestFrame {
  if (!fixtureColourSplits()) return appleExportFrame(composition, position)

  val av = appleExportLowering(composition).toAvComposition()
  val filter = av.videoComposition ?: fail("the export lowered the fixture without a video composition")
  return av.composition.generateFrame(filter, position)
}

/**
 * Whether AVFoundation converts the fixture clip's colour one way for a reader and another way for
 * an image generator.
 *
 * Measured once per process, through a pass-through video composition that runs no filmstrip code
 * and tags its output Rec.709 the way an SDR export does. When the two disagree it is printed, so a
 * run that took the generator path says so.
 */
private suspend fun fixtureColourSplits(): Boolean {
  colourSplit?.let { return it }

  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(appleFixtureClip()), options = null)
  val passThrough = passThroughComposition(asset)
  val position = PROBE_POSITIONS.first()
  val comparison =
    compareFrames(
      expected = asset.readFrame(passThrough, CLIP_LENGTH, position),
      actual = asset.generateFrame(passThrough, position),
    )

  // AVFoundation's limit, not filmstrip's. The fixture carries no colour tags, so VideoToolbox
  // guesses SMPTE-C primaries and a BT.601 matrix for it, and AVFoundation converts every frame to
  // Rec.709 before a filter handler sees it. Apple silicon does that on its hardware colour
  // converter, and both paths agree exactly. The macOS 26 VM behind GitHub's runners has no such
  // converter, and there the reader converts the gamut while the generator only swaps the matrix.
  // This pass-through measured 23.0 dB and SSIM 0.924 apart on it, and filmstrip's own lowering with
  // no effects 23.0 dB and 0.923.
  val splits = comparison.psnrDb < DEFAULT_MIN_PSNR_DB || comparison.ssim < DEFAULT_MIN_SSIM
  if (splits) {
    println(
      "AVFoundation's reader and image generator convert the fixture's colour differently on this " +
        "machine ($comparison), so fixture exports are drawn through an image generator.",
    )
  }
  colourSplit = splits
  return splits
}

private var colourSplit: Boolean? = null

/**
 * A video composition over [asset] that hands every frame back untouched, rendered to Rec.709.
 */
@OptIn(ExperimentalForeignApi::class)
private fun passThroughComposition(asset: AVAsset): AVMutableVideoComposition =
  AVMutableVideoComposition
    .videoCompositionWithAsset(asset) { request ->
      request?.finishWithImage(request.sourceImage, null)
    }.apply {
      setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
      setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
      setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
    }

/**
 * The frame an image generator draws from this asset at [position], through [composition].
 *
 * Both tolerances are pinned to zero, so the frame is the one a reader hands back at the same time.
 *
 * @param timeout How long the draw may take before the test fails.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
@Suppress("DEPRECATION")
internal suspend fun AVAsset.generateFrame(
  composition: AVVideoComposition,
  position: Duration,
  timeout: Duration = CONTRACT_TIMEOUT,
): TestFrame {
  val generator =
    AVAssetImageGenerator(asset = this).apply {
      videoComposition = composition
      requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
      requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
    }

  val (image, reason) =
    awaitStep<Pair<PlatformImage?, String?>>("the image generator to draw $position", timeout) {
      suspendCancellableCoroutine { continuation ->
        generator.generateCGImagesAsynchronouslyForTimes(
          listOf(NSValue.valueWithCMTime(position.toCMTime())),
        ) { _, image, _, result, error ->
          val drawn =
            image?.takeIf { result == AVAssetImageGeneratorSucceeded }?.let { PlatformImage(CGImageRetain(it)) }
          if (continuation.isActive) {
            continuation.resume(drawn to error?.localizedDescription)
          } else {
            drawn?.close()
          }
        }
        continuation.invokeOnCancellation { generator.cancelAllCGImageGeneration() }
      }
    }

  val frame = image ?: fail("the generator drew nothing at $position: ${reason ?: "no reason given"}")
  return frame.use { TestFrame(it.toRgba8888(), Size(it.widthPx, it.heightPx)) }
}
