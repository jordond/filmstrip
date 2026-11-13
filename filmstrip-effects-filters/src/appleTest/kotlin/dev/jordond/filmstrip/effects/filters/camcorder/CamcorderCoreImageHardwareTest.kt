@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.filters.vhs.vhsStep
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Reads the Core Image step a [Camcorder] draws, with no encoder in the way, against [CamcorderOsd.rasterAt].
 *
 * A ramp goes in and comes out in the codes of the BT.709 buffer, rendered to floats, so a pixel the display leaves
 * clear reads the ramp and a lit or edge pixel reads the raster's code. Rows of a render come back from the top.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
class CamcorderCoreImageHardwareTest {
  @Test
  fun `every glyph and edge pixel lands on the raster`() {
    val frame = drawn(listOf(camcorderStep(OSD)), WIDTH, HEIGHT, BLINK_ON)

    assertOnRaster(frame, WIDTH, HEIGHT, OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT))
  }

  @Test
  fun `a frame each side of a second shows its own display`() {
    val step = camcorderStep(OSD)
    val before = OSD.rasterAt(BEFORE_SECOND, WIDTH, HEIGHT)
    val after = OSD.rasterAt(AFTER_SECOND, WIDTH, HEIGHT)

    withClue("the counter has to change across the second for this to tell") {
      before.pixels.contentEquals(after.pixels) shouldBe false
    }
    assertOnRaster(drawn(listOf(step), WIDTH, HEIGHT, BEFORE_SECOND), WIDTH, HEIGHT, before)
    assertOnRaster(drawn(listOf(step), WIDTH, HEIGHT, AFTER_SECOND), WIDTH, HEIGHT, after)
  }

  @Test
  fun `a smaller frame is the raster at its own size`() {
    val width = WIDTH / 2
    val height = HEIGHT / 2
    val frame = drawn(listOf(camcorderStep(OSD)), width, height, BLINK_ON)

    assertOnRaster(frame, width, height, OSD.rasterAt(BLINK_ON, width, height))
  }

  @Test
  fun `a frame away from the origin draws the same display`() {
    val moved = ramp(WIDTH, HEIGHT).imageByApplyingTransform(CGAffineTransformMakeTranslation(MOVED_X, MOVED_Y))
    val output = camcorderStep(OSD).apply(moved, FrameInfo(attributes(WIDTH, HEIGHT), BLINK_ON))
    val back = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(-MOVED_X, -MOVED_Y))

    assertOnRaster(rendered(back, WIDTH, HEIGHT), WIDTH, HEIGHT, OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT))
  }

  // Every pixel of a row the display lights is read against VhsSignal over the frame the display was drawn into.
  @Test
  fun `a display ahead of vhs is recorded on the tape`() {
    val vhs = Vhs(intensity = 0.5f)
    val params = VhsParams.of(vhs)
    val tick = VhsSignal.tickAt(BLINK_ON.toDouble(DurationUnit.SECONDS))
    val recorded = drawn(listOf(camcorderStep(OSD)), WIDTH, HEIGHT, BLINK_ON)
    val burned = drawn(listOf(camcorderStep(OSD), vhsStep(vhs)), WIDTH, HEIGHT, BLINK_ON)
    val raster = OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT)
    var widest = 0.0
    var moved = 0.0
    var rows = 0

    for (y in 0 until HEIGHT) {
      if ((0 until WIDTH).all { raster.argbAt(it, y) == HIDDEN }) continue
      rows++
      val source = { x: Int ->
        DoubleArray(RGB) { channel -> recorded[(y * WIDTH + x) * CHANNELS + channel].toDouble() }
      }
      for (x in 0 until WIDTH) {
        val want =
          VhsSignal.pixelAt(
            (x + 0.5) / WIDTH,
            VhsSignal.virtualRow(y, HEIGHT),
            tick,
            vhs.seed,
            WIDTH,
            params,
            source,
          )
        val read = rgbAt(burned, WIDTH, x, y)
        widest = max(widest, (0 until RGB).maxOf { abs(read[it] - want[it]) })
        if (raster.argbAt(x, y) != HIDDEN) moved = max(moved, read.gapTo(raster.argbAt(x, y)))
      }
    }
    withClue("the display lights rows to read") { (rows > 0) shouldBe true }
    withClue("the widest gap from VhsSignal on the display's rows was $widest") {
      widest shouldBeLessThan KERNEL_HARD_EDGE
    }
    withClue("the tape moved the display off its own codes") { moved shouldBeGreaterThan 0.1 }
  }

  @Test
  fun `a display after vhs stays clean`() {
    val frame = drawn(listOf(vhsStep(Vhs(intensity = 0.5f)), camcorderStep(OSD)), WIDTH, HEIGHT, BLINK_ON)
    val raster = OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT)
    var lit = 0
    var widest = 0.0

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val argb = raster.argbAt(x, y)
        if (argb == HIDDEN) continue
        lit++
        widest = max(widest, rgbAt(frame, WIDTH, x, y).gapTo(argb))
      }
    }
    lit shouldNotBe 0
    widest shouldBeLessThan TOLERANCE
  }

  private fun assertOnRaster(
    frame: FloatArray,
    width: Int,
    height: Int,
    raster: OsdRaster,
  ) {
    var lit = 0
    var widest = 0.0
    var worst = ""
    for (y in 0 until height) {
      for (x in 0 until width) {
        val argb = raster.argbAt(x, y)
        val read = rgbAt(frame, width, x, y)
        val gap =
          if (argb == HIDDEN) {
            val ramp = rampPixel(x, width)
            (0 until RGB).maxOf { abs(read[it] - ramp[it]) }
          } else {
            lit++
            read.gapTo(argb)
          }
        if (gap > widest) {
          widest = gap
          worst = "($x, $y) ${if (argb == HIDDEN) "clear" else "lit"}"
        }
      }
    }
    lit shouldNotBe 0
    withClue("widest gap at $worst") { widest shouldBeLessThan TOLERANCE }
  }

  private fun drawn(
    steps: List<CoreImageEffect>,
    width: Int,
    height: Int,
    time: Duration,
  ): FloatArray {
    val info = FrameInfo(attributes(width, height), time)
    val output = steps.fold(ramp(width, height)) { image, step -> step.apply(image, info) }
    return rendered(output, width, height)
  }

  private fun rendered(
    image: CIImage,
    width: Int,
    height: Int,
  ): FloatArray {
    val values = FloatArray(width * height * CHANNELS)
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      values.usePinned { pinned ->
        floatContext().render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (width * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return values
  }

  // Core Image works in half floats unless told otherwise, whose rounding is wider than a read of 1e-4.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
    return CIContext.contextWithOptions(options as Map<Any?, *>)
  }

  private fun ramp(
    width: Int,
    height: Int,
  ): CIImage {
    val values = FloatArray(width * height * CHANNELS)
    for (y in 0 until height) {
      for (x in 0 until width) {
        (rampPixel(x, width) + 1f).copyInto(values, (y * width + x) * CHANNELS)
      }
    }
    return imageOf(values, width, height)
  }

  // Float RGBA codes with the top row first, tagged with the BT.709 buffer's own space.
  private fun imageOf(
    values: FloatArray,
    width: Int,
    height: Int,
  ): CIImage {
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      val data =
        values.usePinned { pinned ->
          NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
        }
      return CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (width * CHANNELS * Float.SIZE_BYTES).convert(),
        size = CGSizeMake(width.toDouble(), height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  // Red and blue running opposite ways, so a row read off by a pixel shows.
  private fun rampPixel(
    column: Int,
    width: Int,
  ): FloatArray {
    val t = column.toFloat() / (width - 1)
    return floatArrayOf(RAMP_LOW + RAMP_SPAN * t, RAMP_MID, RAMP_LOW + RAMP_SPAN * (1f - t))
  }

  private fun rgbAt(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): DoubleArray {
    val at = (y * width + x) * CHANNELS
    return DoubleArray(RGB) { frame[at + it].toDouble() }
  }

  private fun DoubleArray.gapTo(argb: Int): Double =
    maxOf(
      abs(this[0] - (argb shr 16 and 0xFF) / FULL),
      abs(this[1] - (argb shr 8 and 0xFF) / FULL),
      abs(this[2] - (argb and 0xFF) / FULL),
    )

  private fun attributes(
    width: Int,
    height: Int,
  ): Attributes {
    val size = Size(width, height)
    return Attributes(
      inputSize = size,
      outputSize = size,
      layoutSize = size,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = FRAME_RATE,
      span = TimeRange(Duration.ZERO, SPAN),
    )
  }

  private companion object {
    const val FRAME_RATE = 30f
    val OSD =
      CamcorderOsd(
        Camcorder(
          listOf(
            OsdElement.Rec(),
            OsdElement.Counter(withFrames = true),
            OsdElement.Tape(TapeSpeed.SP),
            OsdElement.Clock(),
          ),
        ),
        FRAME_RATE,
      )
    val BLINK_ON = 250.milliseconds
    val BEFORE_SECOND = 1_900.milliseconds
    val AFTER_SECOND = 2_100.milliseconds
    val SPAN = 4_000.milliseconds
    const val WIDTH = 640
    const val HEIGHT = 360
    const val CHANNELS = 4
    const val RGB = 3
    const val FULL = 255.0
    const val HIDDEN = 0
    const val TOLERANCE = 1e-4

    // The Core Image kernel's limit: the GPU samples the frame at float precision, and a hard edge between the display
    // and the ramp turns that into up to this much off VhsSignal.
    const val KERNEL_HARD_EDGE = 9.3e-4
    const val MOVED_X = 40.0
    const val MOVED_Y = 25.0
    const val RAMP_LOW = 0.25f
    const val RAMP_MID = 0.5f
    const val RAMP_SPAN = 0.4f
  }
}
