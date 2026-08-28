package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.setValue
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.QuartzCore.CATransaction
import kotlin.math.PI

/**
 * The v1 preview chain on iOS, as Core Image.
 *
 * The stages match the Android chain one for one (crop, rotate, overlay composite) because the
 * whole point of the effect catalogue is that one `EditComposition` maps to `GlEffect` on Android
 * and `CIFilter` here. The overlay is composited inside the image chain with
 * `CISourceOverCompositing`, never as a `CALayer` above the video. That is the standing rule, and
 * it is what lets still mode re-render the overlay at all.
 *
 * The chain is a pure function of the source image and the placement, which is why a parameter write
 * costs nothing but a re-render.
 */
@OptIn(ExperimentalForeignApi::class)
class PreviewChain(
  private val overlayImage: CIImage,
  private val cropAspect: Double = NINE_BY_SIXTEEN,
  private val rotationDegrees: Double = 90.0,
) {
  fun apply(
    source: CIImage,
    placement: OverlayPlacement,
  ): CIImage {
    val cropped = crop(source)
    val rotated =
      cropped
        .imageByApplyingTransform(CGAffineTransformMakeRotation(rotationDegrees * PI / 180.0))
        .toOrigin()
    return composite(rotated, placement).toOrigin()
  }

  /**
   * Moves an image's extent back to the origin.
   *
   * Core Image transforms are about the coordinate origin, so rotating pushes the extent into
   * negative space. Rendering that into a buffer anchored at (0, 0) samples the empty region beside
   * the image and produces a blank frame, which still costs almost nothing and therefore looks like
   * a very fast render rather than a broken one.
   */
  private fun CIImage.toOrigin(): CIImage =
    extent.useContents {
      this@toOrigin.imageByApplyingTransform(
        platform.CoreGraphics.CGAffineTransformMakeTranslation(-origin.x, -origin.y),
      )
    }

  private fun crop(source: CIImage): CIImage =
    source.extent.useContents {
      val targetWidth = size.height * cropAspect
      val width = minOf(size.width, targetWidth)
      val inset = (size.width - width) / 2.0
      source.imageByCroppingToRect(
        CGRectMake(origin.x + inset, origin.y, width, size.height),
      )
    }

  private fun composite(
    background: CIImage,
    placement: OverlayPlacement,
  ): CIImage {
    val positioned =
      background.extent.useContents {
        // Normalised placement resolved to pixels once, at render time, exactly as the geometry
        // contract requires. An overlay authored at (0.5, 0.5) lands in the same relative spot at
        // preview scale and at export scale.
        val centreX = origin.x + size.width * (placement.x + 1.0) / 2.0
        val centreY = origin.y + size.height * (placement.y + 1.0) / 2.0
        overlayImage.extent.useContents {
          overlayImage.imageByApplyingTransform(
            platform.CoreGraphics.CGAffineTransformMakeTranslation(
              centreX - size.width / 2.0,
              centreY - size.height / 2.0,
            ),
          )
        }
      }

    val filter = CIFilter.filterWithName("CISourceOverCompositing") ?: return background
    filter.setValue(positioned, forKey = "inputImage")
    filter.setValue(background, forKey = "inputBackgroundImage")
    return filter.outputImage ?: background
  }

  private companion object {
    const val NINE_BY_SIXTEEN = 9.0 / 16.0
  }
}

/**
 * Still mode on iOS: filmstrip owns the paused source frame and re-applies the chain on demand.
 *
 * The documented iOS approach, reassigning `playerItem.videoComposition`, is ruled out for its
 * one-frame lag, occasional frame jumps and CPU overload under rapid reassignment. None of that
 * happens here because no `AVPlayerItem` property is touched at all after the source frame is
 * acquired. The frame comes from `AVPlayerItemVideoOutput.copyPixelBuffer(forItemTime:)`, is held
 * as a `CIImage`, and every redraw is a `CIContext` render of the same chain with new parameters.
 *
 * The `CIContext` is Metal-backed and created once. Creating one per frame is the usual way this is
 * accidentally made slow: it recompiles the filter graph each time.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStillRenderer(
  sourceBuffer: CVPixelBufferRef,
  private val chain: PreviewChain,
) {
  private val device = MTLCreateSystemDefaultDevice()

  /**
   * Metal-backed and created once.
   *
   * Building a `CIContext` per frame is the usual accidental way to make this slow: it recompiles
   * the filter graph every time.
   *
   * The cast is a cinterop artefact, not a type hole. `MTLCreateSystemDefaultDevice` is typed
   * `platform.Metal.MTLDeviceProtocol` while `contextWithMTLDevice` wants
   * `objcnames.protocols.MTLDeviceProtocol`. CoreImage's headers forward-declare the protocol
   * rather than importing Metal, so cinterop generates two Kotlin names for one Objective-C
   * protocol.
   */
  @Suppress("UNCHECKED_CAST")
  private val context: CIContext =
    device
      ?.let { CIContext.contextWithMTLDevice(it as objcnames.protocols.MTLDeviceProtocol) }
      ?: CIContext.contextWithOptions(null)

  /**
   * The retained source frame, held pre-effects so the chain can be re-applied to it.
   */
  private val source: CIImage = CIImage.imageWithCVPixelBuffer(sourceBuffer)

  /**
   * Re-renders into [target], which is what a `CAMetalLayer` drawable or a readback buffer is.
   *
   * @param awaitCompletion locks the target buffer afterwards, which blocks until the GPU has
   *   finished writing it. Core Image renders lazily, so without this the call returns as soon as
   *   the work is enqueued and any timing around it measures submission rather than the frame.
   *   Locking also forces a readback the on-screen path does not pay, so a timing taken this way is
   *   an upper bound on the real cost, not the cost itself.
   */
  fun redraw(
    placement: OverlayPlacement,
    target: CVPixelBufferRef,
    awaitCompletion: Boolean = false,
  ) {
    val output = chain.apply(source, placement)
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    context.render(output, toCVPixelBuffer = target)
    CATransaction.commit()

    if (awaitCompletion) {
      CVPixelBufferLockBaseAddress(target, kCVPixelBufferLock_ReadOnly)
      CVPixelBufferUnlockBaseAddress(target, kCVPixelBufferLock_ReadOnly)
    }
  }

  /**
   * Renders once and hands back a `CGImage`, the shape proposed for `FrameResult`.
   */
  fun renderToCGImage(placement: OverlayPlacement): platform.CoreGraphics.CGImageRef? {
    val output = chain.apply(source, placement)
    return context.createCGImage(output, fromRect = output.extent)
  }
}
