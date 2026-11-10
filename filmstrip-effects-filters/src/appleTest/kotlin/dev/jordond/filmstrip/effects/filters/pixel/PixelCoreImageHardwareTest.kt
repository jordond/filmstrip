package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
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
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernel's own output, with no encoder in the way, against [PixelSignal.pixelAt].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render and of a bitmap both run from the top, the way the signal counts them.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
@Suppress("UNCHECKED_CAST")
class PixelCoreImageHardwareTest {
  @Test
  fun `levels and dither land on the signal at a mid cell count`() = assertLandsOnTheSignal(LEVELS, WIDTH, HEIGHT)

  @Test
  fun `a palette lands on the signal`() = assertLandsOnTheSignal(PALETTE, WIDTH, HEIGHT)

  @Test
  fun `a sixteen colour palette lands on the signal`() = assertLandsOnTheSignal(SIXTEEN, WIDTH, HEIGHT)

  @Test
  fun `a smaller frame lands on the same cells`() {
    PixelSignal.columns(WIDTH / 2, HEIGHT / 2, CELLS) shouldBe PixelSignal.columns(WIDTH, HEIGHT, CELLS)
    assertLandsOnTheSignal(LEVELS, WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun `a frame away from the origin draws the same cells`() {
    val moved = ramps(WIDTH, HEIGHT).imageByApplyingTransform(CGAffineTransformMakeTranslation(MOVED_X, MOVED_Y))
    val output = pixelStep(LEVELS).apply(moved, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val back = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(-MOVED_X, -MOVED_Y))

    val frame = rendered(back, WIDTH, HEIGHT)
    val still = drawn(LEVELS, ramps(WIDTH, HEIGHT), WIDTH, HEIGHT)

    var worst = 0.0
    for (index in frame.indices) worst = max(worst, abs(frame[index] - still[index]).toDouble())
    worst shouldBeLessThan TOLERANCE
  }

  // Core Image does not hold a small frame to the region it asks for, so the region itself is read here. A pixel reads
  // the centre of its own cell, which lands furthest from it on the cells at a tile's edges.
  @Test
  fun `the source region of a tile holds every read under it`() {
    val columns = PixelSignal.columns(FULL_HD_WIDTH, FULL_HD_HEIGHT, CELLS)
    listOf(
      CGRectMake(0.0, 0.0, FULL_HD_WIDTH.toDouble(), FULL_HD_HEIGHT.toDouble()),
      CGRectMake(8.0, 8.0, 24.0, 24.0),
      CGRectMake(1880.0, 1040.0, 40.0, 40.0),
      CGRectMake(905.0, 517.0, 7.0, 5.0),
    ).forEach { tile ->
      val (x, y, w, h) = tile.useContents { listOf(origin.x, origin.y, size.width, size.height) }
      val region = pixelSourceRegion(tile, FULL_HD_WIDTH, FULL_HD_HEIGHT, columns, CELLS)
      region.useContents {
        (x.toInt() until (x + w).toInt()).forEach { column ->
          val read = PixelSignal.centreOf(PixelSignal.cellOf(column, columns, FULL_HD_WIDTH), columns, FULL_HD_WIDTH)
          withClue("column $column") {
            (read >= origin.x && read + 1 <= origin.x + size.width) shouldBe true
          }
        }
        // Core Image counts rows up from the bottom and the signal from the top.
        (y.toInt() until (y + h).toInt()).forEach { row ->
          val fromTop = FULL_HD_HEIGHT - 1 - row
          val read =
            FULL_HD_HEIGHT - 1 -
              PixelSignal.centreOf(PixelSignal.cellOf(fromTop, CELLS, FULL_HD_HEIGHT), CELLS, FULL_HD_HEIGHT)
          withClue("row $row") {
            (read >= origin.y && read + 1 <= origin.y + size.height) shouldBe true
          }
        }
      }
    }
  }

  @Test
  fun `a corner tile reads the same as the whole frame`() {
    val whole = drawn(LEVELS, ramps(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val output = pixelStep(LEVELS).apply(ramps(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val tile =
      rendered(output, WIDTH, HEIGHT, originX = TILE_X, originY = TILE_Y, boundsWidth = TILE, boundsHeight = TILE)

    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        // A bitmap row runs from the top, of the tile here and of the frame there, and the tile sits at the bottom.
        val wholeAt = ((HEIGHT - TILE_Y - TILE + y) * WIDTH + TILE_X + x) * CHANNELS
        val tileAt = (y * TILE + x) * CHANNELS
        for (channel in 0 until RGB) {
          withClue("row $y column $x channel $channel") {
            tile[tileAt + channel].toDouble() shouldBe (whole[wholeAt + channel].toDouble() plusOrMinus TOLERANCE)
          }
        }
      }
    }
  }

  @Test
  fun `a clear bar stays clear and a part alpha is cut on its straight codes`() {
    val twoLevels = Pixel(cells = CELLS, levels = 2)
    val image =
      imageOf(WIDTH, HEIGHT) { column, _ ->
        if (column < BAR) FloatArray(CHANNELS) else floatArrayOf(QUARTER, QUARTER, QUARTER, HALF)
      }

    val frame = drawn(twoLevels, image, WIDTH, HEIGHT)

    pixel(frame, WIDTH, 0, HEIGHT / 2).toList() shouldBe listOf(0f, 0f, 0f, 0f)
    withClue("a straight half rounds up to full, then takes the half alpha back") {
      pixel(frame, WIDTH, WIDTH / 2, HEIGHT / 2).map { abs(it - HALF).toDouble() }.max() shouldBeLessThan TOLERANCE
    }
  }

  @Test
  fun `this device compiles the kernel`() {
    pixelKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device with no kernels is refused by name`() {
    pixelKernelLimit(kernelsExist = false) shouldBe PIXEL_KERNEL_LIMIT
    pixelKernelLimit(kernelsExist = true, compiles = { false }) shouldBe PIXEL_KERNEL_LIMIT

    val refused = pixelResolution(Pixel(), PIXEL_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.specId shouldBe Pixel.ID
    refused.message shouldBe PIXEL_KERNEL_LIMIT
  }

  private fun assertLandsOnTheSignal(
    look: Pixel,
    width: Int,
    height: Int,
  ) {
    val frame = drawn(look, ramps(width, height), width, height)
    val picture = { x: Int, y: Int -> rampsAt(x, y).take(RGB).map(Float::toDouble).toDoubleArray() }

    var worst = 0.0
    reads(height).forEach { y ->
      reads(width).forEach { x ->
        val wanted = PixelSignal.pixelAt(x, y, width, height, look, picture)
        val read = pixel(frame, width, x, y)
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - wanted[channel]))
      }
    }
    withClue("$width by $height") { worst shouldBeLessThan TOLERANCE }
  }

  // Every pixel through the middle, so each cell edge there is crossed, plus a spread out to both sides.
  private fun reads(size: Int): List<Int> =
    ((size / 2 - MIDDLE_READS until size / 2 + MIDDLE_READS) + (0 until size step SPREAD_STRIDE) + (size - 1))
      .distinct()

  private fun pixel(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): FloatArray = FloatArray(CHANNELS) { frame[(y * width + x) * CHANNELS + it] }

  private fun drawn(
    look: Pixel,
    image: CIImage,
    width: Int,
    height: Int,
  ): FloatArray =
    rendered(pixelStep(look).apply(image, FrameInfo(attributes(width, height), SECONDS.seconds)), width, height)

  private fun rendered(
    image: CIImage,
    width: Int,
    height: Int,
    originX: Int = 0,
    originY: Int = 0,
    boundsWidth: Int = width,
    boundsHeight: Int = height,
  ): FloatArray {
    val values = FloatArray(boundsWidth * boundsHeight * CHANNELS)
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      values.usePinned { pinned ->
        floatContext().render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (boundsWidth * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(originX.toDouble(), originY.toDouble(), boundsWidth.toDouble(), boundsHeight.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return values
  }

  // Core Image works in half floats unless told otherwise, which would round a code before the cut reads it.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
    return CIContext.contextWithOptions(options as Map<Any?, *>)
  }

  private fun ramps(
    width: Int,
    height: Int,
  ): CIImage = imageOf(width, height, ::rampsAt)

  private fun imageOf(
    width: Int,
    height: Int,
    pixel: (Int, Int) -> FloatArray,
  ): CIImage {
    val values = FloatArray(width * height * CHANNELS)
    for (y in 0 until height) {
      for (x in 0 until width) {
        pixel(x, y).copyInto(values, (y * width + x) * CHANNELS)
      }
    }
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

  // Red runs across and green runs down, each on a step a float holds exactly.
  private fun rampsAt(
    x: Int,
    y: Int,
  ): FloatArray = floatArrayOf(RAMP_START + x / RAMP_STEPS, RAMP_START + y / RAMP_STEPS, BLUE, 1f)

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
      frameRate = 30f,
      span = TimeRange(0.seconds, 4.seconds),
    )
  }

  private companion object {
    const val CELLS = 90
    val LEVELS = Pixel(cells = CELLS, levels = 6, dither = 0.5f)
    val PALETTE = Pixel(cells = CELLS, dither = 0.5f, palette = PixelPalette.GameBoy)

    // A full palette on a diagonal, so the ramps cross many of its entries.
    val SIXTEEN =
      Pixel(
        cells = CELLS,
        dither = 0.5f,
        palette = PixelPalette(List(PixelPalette.MAX_COLORS) { (it * 0x0F0B07 + 0x081018) and 0xFFFFFF }),
      )
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val TILE = 24
    const val TILE_X = 8
    const val TILE_Y = 8
    const val CHANNELS = 4
    const val RGB = 3
    const val SECONDS = 1.5
    const val MIDDLE_READS = 24
    const val SPREAD_STRIDE = 37
    const val MOVED_X = 40.0
    const val MOVED_Y = 25.0
    const val BAR = 160
    const val RAMP_START = 0.03125f
    const val RAMP_STEPS = 2048f
    const val BLUE = 0.375f
    const val QUARTER = 0.25f
    const val HALF = 0.5f

    // A step or a palette entry is the same code on the GPU, so only the target's own float is left.
    const val TOLERANCE = 1e-5
  }
}
