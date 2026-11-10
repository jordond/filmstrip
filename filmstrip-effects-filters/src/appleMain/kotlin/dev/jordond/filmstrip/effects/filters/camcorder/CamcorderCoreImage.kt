package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.color.inCodesOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * [camcorder] as a Core Image step, laying the display [CamcorderOsd.rasterAt] draws over the frame.
 *
 * @param frameRate The output frame rate a counter counts frames at, or null when the output does not say.
 */
internal fun camcorderEffect(
  camcorder: Camcorder,
  frameRate: Float?,
): PlatformEffect = PlatformEffect(camcorderStep(CamcorderOsd(camcorder, frameRate)))

/**
 * The Core Image step that draws [osd] source over the frame, on the frame's own codes.
 *
 * The raster is drawn at the frame's extent with one pixel per texel, so every pixel lands where the shared shader puts
 * it on the other backends. The display reads the frame's composition time, and a new image is only built when what it
 * shows or the frame size changes, drawing only the region that changed.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun camcorderStep(osd: CamcorderOsd): CoreImageEffect {
  val images = OsdImages(osd)
  return CoreImageEffect { image, frame ->
    val (x, y, width, height) =
      image.extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }
    val display =
      images
        .at(frame.compositionTime, width.roundToInt(), height.roundToInt())
        .imageByApplyingTransform(CGAffineTransformMakeTranslation(x, y))
    inCodesOf(frame.attributes.colorSpace) { toCodes -> display.imageByCompositingOverImage(toCodes(image)) }
  }
}

// The image for the last display drawn, held so frames sharing a state reuse it. Every pixel of the raster is fully
// clear or fully opaque, so its straight bytes are already premultiplied, and with no colour space they pass as codes.
// A new state copies the last image's bytes and draws only the region that changed over them, so frames drawn on
// other threads never see bytes change under them.
@OptIn(BetaInteropApi::class, ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
private class OsdImages(
  private val osd: CamcorderOsd,
) {
  private val held = AtomicReference<HeldImage?>(null)

  fun at(
    time: Duration,
    width: Int,
    height: Int,
  ): CIImage {
    val state = osd.stateAt(time)
    val last = held.load()
    if (last != null && last.state == state && last.width == width && last.height == height) return last.image
    val bytes =
      if (last != null && last.width == width && last.height == height) {
        val region = osd.changedRegion(last.state, state, width, height)
        last.bytes.copyOf().also { if (region != null) osd.redrawInto(it, state, width, height, region) }
      } else {
        osd.rasterOf(state, width, height).rgbaBytes()
      }
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    val image =
      CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (width * RGBA).convert(),
        size = CGSizeMake(width.toDouble(), height.toDouble()),
        format = kCIFormatRGBA8,
        colorSpace = null,
      )
    held.store(HeldImage(state, width, height, bytes, image))
    return image
  }
}

private class HeldImage(
  val state: OsdState,
  val width: Int,
  val height: Int,
  val bytes: ByteArray,
  val image: CIImage,
)

private const val RGBA = 4
