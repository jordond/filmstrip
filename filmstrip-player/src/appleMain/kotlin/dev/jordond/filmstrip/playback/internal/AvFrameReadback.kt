package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetImageGeneratorResult
import platform.AVFoundation.AVAssetImageGeneratorSucceeded
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.Foundation.NSError
import platform.Foundation.NSValue
import kotlin.time.Duration

/**
 * Reads one rendered preview frame back without touching the live item.
 *
 * A generator over the same `AVMutableComposition` and the same `AVVideoComposition` the player is
 * showing renders the same frames through the same effect chain, so the readback is the preview's
 * own output rather than a second rendering of it. Because it is a separate object, no seek is
 * observable, the playhead does not move, and playback if running is unaffected, which is what
 * [PreviewFrameReadback] requires.
 *
 * Setting `videoComposition` is what carries the effects. It also makes
 * `appliesPreferredTrackTransform` irrelevant, because the chain has already baked each clip's
 * container rotation into the pixels.
 *
 * @param scope The engine's dispatcher. Every callback is delivered on it, in request order or not:
 *   `generateCGImagesAsynchronouslyForTimes` may answer out of the order it was asked.
 * @param composition The graph the player is showing, or null before one is loaded.
 * @param policy How hard the preview may work, which caps the generated frame.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
internal class AvFrameReadback(
  private val scope: CoroutineScope,
  private val composition: () -> AvComposition?,
  private val policy: () -> PreviewQualityPolicy,
  private val renderScale: () -> Float,
) : PreviewFrameReadback {
  @Suppress("DEPRECATION")
  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    val av = composition() ?: return refuse(callback, NO_COMPOSITION)
    val generator = generatorFor(av)
    val scale = renderScale()
    var delivered = false

    generator.generateCGImagesAsynchronouslyForTimes(
      listOf(NSValue.valueWithCMTime(position.toCMTime())),
    ) { _, image, actualTime, result, error ->
      // The handler runs on the generator's own queue. Everything past this point belongs to the
      // engine's dispatcher, and nothing may throw back into AVFoundation.
      val outcome =
        try {
          readback(image, actualTime, result, error, av, scale)
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Exception,
        ) {
          ReadbackResult.Failure(
            PlaybackError.Underlying(
              PlaybackError.Underlying.NO_PLATFORM_CODE,
              broken.message ?: broken.toString(),
            ),
          )
        }
      scope.launch {
        if (!delivered) {
          delivered = true
          callback.onReadback(outcome)
        }
      }
    }

    return Cancellable {
      delivered = true
      generator.cancelAllCGImageGeneration()
    }
  }

  /**
   * A generator over [av]'s own composition, rendering through [av]'s video composition.
   *
   * Both tolerances are zeroed. A readback is compared against an export at the same composition
   * time, so it has to land on that frame rather than on the nearest sync sample.
   */
  @Suppress("DEPRECATION")
  private fun generatorFor(av: AvComposition): AVAssetImageGenerator =
    AVAssetImageGenerator(asset = av.composition).apply {
      videoComposition = av.videoComposition
      requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
      requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
      cappedSize(av)?.let { maximumSize = it }
    }

  /**
   * The size the generated frame is capped to, or null when the policy caps nothing.
   */
  private fun cappedSize(av: AvComposition): CValue<CGSize>? {
    val cap = (policy() as? PreviewQualityPolicy.CapHeight)?.heightPx ?: return null
    val size = av.chain?.output?.size ?: return null
    if (cap >= size.height) return null
    return CGSizeMake(size.width.toDouble() * cap / size.height, cap.toDouble())
  }

  private fun readback(
    image: CGImageRef?,
    actualTime: CValue<CMTime>,
    result: AVAssetImageGeneratorResult,
    error: NSError?,
    av: AvComposition,
    scale: Float,
  ): ReadbackResult {
    if (result != AVAssetImageGeneratorSucceeded || image == null) {
      val reason = error?.localizedDescription ?: "the generator returned no frame"
      return ReadbackResult.Failure(
        PlaybackError.Underlying(error?.code?.toInt() ?: PlaybackError.Underlying.NO_PLATFORM_CODE, reason),
      )
    }

    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    return ReadbackResult.Success(
      ReadbackFrame(
        pixels = image.toRgba(width, height),
        size = Size(width, height),
        presentationTime = actualTime.toDuration(),
        colorSpace = if (av.encodesHdr) ColorSpace.Bt2020 else ColorSpace.Bt709,
        renderScale = scale,
      ),
    )
  }

  /**
   * This image as tightly packed RGBA_8888, row major.
   *
   * Drawn into the image's own colour space rather than into a device one. The chain renders into
   * the video composition's colour properties, and naming a different space here would convert the
   * frame on its way out, so a readback would stop being the pixels the export encodes.
   *
   * The row stride is set to `width * 4` rather than left to Core Graphics, which would round it up
   * to its own alignment and leave padding the contract forbids. The frame is flattened onto the
   * composition's fill before it reaches here, so every pixel it draws is opaque and premultiplied
   * and straight alpha are the same bytes.
   */
  private fun CGImageRef.toRgba(
    width: Int,
    height: Int,
  ): ByteArray {
    val pixels = ByteArray(width * height * CHANNELS)
    val own = CGImageGetColorSpace(this)
    val fallback = if (own == null) CGColorSpaceCreateDeviceRGB() else null

    try {
      pixels.usePinned { pinned ->
        val context =
          CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = BITS_PER_CHANNEL.toULong(),
            bytesPerRow = (width * CHANNELS).toULong(),
            space = own ?: fallback,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
          ) ?: error("Core Graphics refused a bitmap context for a ${width}x$height frame.")
        try {
          CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
        } finally {
          CGContextRelease(context)
        }
      }
    } finally {
      fallback?.let(::CGColorSpaceRelease)
    }

    return pixels
  }

  private fun refuse(
    callback: ReadbackCallback,
    message: String,
  ): Cancellable {
    callback.onReadback(ReadbackResult.Failure(PlaybackError.SourceUnreadable(message)))
    return Cancellable { }
  }

  private companion object {
    const val CHANNELS = 4
    const val BITS_PER_CHANNEL = 8
    const val NO_COMPOSITION = "No composition is loaded, so there is no frame to read back."
  }
}
