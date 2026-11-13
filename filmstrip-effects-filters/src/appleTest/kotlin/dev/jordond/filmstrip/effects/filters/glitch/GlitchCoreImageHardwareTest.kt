@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
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
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernel's own output, with no encoder in the way, against [GlitchSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top. The seed is the first whose burst is rising at the read, found through the
 * signal.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
class GlitchCoreImageHardwareTest {
  @Test
  fun `the kernel lands on the signal mid attack`() {
    withClue("some row slides at least a pixel") {
      (0 until HEIGHT).any { abs(shiftOf(it, HEIGHT)) * WIDTH > 1.0 } shouldBe true
    }
    assertLandsOnTheSignal(BURSTING, WIDTH, HEIGHT)
  }

  @Test
  fun `a quiet tick splits the colours and moves no row`() = assertLandsOnTheSignal(QUIET, WIDTH, HEIGHT)

  @Test
  fun `a smaller frame draws the same burst at its own virtual rows`() =
    assertLandsOnTheSignal(BURSTING, WIDTH / 2, HEIGHT / 2)

  @Test
  fun `a 1080p frame lands on the signal`() = assertLandsOnTheSignal(BURSTING, FULL_HD_WIDTH, FULL_HD_HEIGHT)

  @Test
  fun `the same time draws the same picture after a seek`() {
    val first = drawn(BURSTING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val elsewhere = drawn(BURSTING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds = SECONDS / 2)
    val again = drawn(BURSTING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    elsewhere.contentEquals(first) shouldBe false
    again.contentEquals(first) shouldBe true
  }

  @Test
  fun `the source region of a tile holds every read under it`() {
    val params = GlitchParams.at(1.0)
    listOf(
      CGRectMake(0.0, 0.0, 1920.0, 1080.0),
      CGRectMake(8.0, 8.0, 24.0, 24.0),
      CGRectMake(1880.0, 1040.0, 40.0, 40.0),
    ).forEach { tile ->
      val (x, w) = tile.useContents { listOf(origin.x, size.width) }
      glitchSourceRegion(tile, 1920.0, params).useContents {
        for (tick in 0 until TICKS) {
          val split = GlitchSignal.channelSplit(BURSTING.seed, tick, params)
          for (row in 0 until GlitchSignal.ROWS) {
            val shift = GlitchSignal.rowShift(BURSTING.seed, tick, row, params)
            listOf(x, x + w).forEach { column ->
              listOf(-split, 0.0, split).forEach { apart ->
                val read = column - (shift + apart) * 1920
                withClue("tick $tick row $row column $column") {
                  (read >= origin.x + 1 && read <= origin.x + size.width - 1) shouldBe true
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun `a corner tile reads the same as the whole frame`() {
    val whole = drawn(BURSTING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val output = glitchStep(BURSTING).apply(ramp(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val tile =
      rendered(output, originX = TILE_X, originY = TILE_Y, boundsWidth = TILE, boundsHeight = TILE)

    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        // A bitmap row runs from the top, of the tile here and of the frame there, and the tile sits at the bottom.
        val wholeAt = ((HEIGHT - TILE_Y - TILE + y) * WIDTH + TILE_X + x) * CHANNELS
        val tileAt = (y * TILE + x) * CHANNELS
        withClue("row $y column $x") {
          tile[tileAt].toDouble() shouldBe (whole[wholeAt].toDouble() plusOrMinus TOLERANCE)
        }
      }
    }
  }

  @Test
  fun `alpha moves with green`() {
    val frame = drawn(BURSTING, canvas(), WIDTH, HEIGHT)
    val y = (0 until HEIGHT).maxBy { abs(shiftOf(it, HEIGHT)) }
    val shift = shiftOf(y, HEIGHT) * WIDTH
    val leading = if (shift > 0) CANVAS_RIGHT else CANVAS_LEFT
    val trailing = if (shift > 0) CANVAS_LEFT else CANVAS_RIGHT
    val alpha = { column: Double -> frame[(y * WIDTH + column.toInt()) * CHANNELS + 3].toDouble() }

    withClue("the row slides far enough to tell") { abs(shift) shouldBeGreaterThan SLIDE_PIXELS }
    withClue("picture moved past its own edge") { alpha(leading + shift / 2) shouldBe (1.0 plusOrMinus TOLERANCE) }
    withClue("the bar the picture left") { alpha(trailing + shift / 2) shouldBe (0.0 plusOrMinus TOLERANCE) }
  }

  @Test
  fun `a clear bar beside the picture takes no colour from the split`() {
    val frame = drawn(BURSTING, canvas(), WIDTH, HEIGHT)
    val split = GlitchSignal.channelSplit(BURSTING.seed, TICK, GlitchParams.of(BURSTING)) * WIDTH
    val y = (0 until HEIGHT).first { shiftOf(it, HEIGHT) == 0.0 }
    val at = { column: Int, channel: Int -> frame[(y * WIDTH + column) * CHANNELS + channel].toDouble() }

    withClue("the split reaches across the edge") { split shouldBeGreaterThan 1.0 }
    withClue("red beside the right edge") { at(CANVAS_RIGHT, 0) shouldBe (0.0 plusOrMinus TOLERANCE) }
    withClue("blue beside the left edge") { at(CANVAS_LEFT - 1, 2) shouldBe (0.0 plusOrMinus TOLERANCE) }
    for (column in 0 until WIDTH) {
      for (channel in 0 until 3) {
        withClue("column $column channel $channel") {
          at(column, channel) shouldBeLessThanOrEqual
            at(column, 3) + TOLERANCE
        }
      }
    }
  }

  @Test
  fun `this device compiles the kernel`() {
    glitchKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device with no kernels is refused by name`() {
    glitchKernelLimit(kernelsExist = false) shouldBe GLITCH_KERNEL_LIMIT
    glitchKernelLimit(kernelsExist = true, compiles = { false }) shouldBe GLITCH_KERNEL_LIMIT

    val refused = glitchResolution(Glitch(), GLITCH_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.specId shouldBe Glitch.ID
    refused.message shouldBe GLITCH_KERNEL_LIMIT
  }

  private fun shiftOf(
    y: Int,
    height: Int,
  ): Double =
    GlitchSignal.rowShift(
      BURSTING.seed,
      GlitchSignal.tickAt(SECONDS),
      GlitchSignal.virtualRow(y, height),
      GlitchParams.of(BURSTING),
    )

  private fun assertLandsOnTheSignal(
    glitch: Glitch,
    width: Int,
    height: Int,
  ) {
    val params = GlitchParams.of(glitch)
    val tick = GlitchSignal.tickAt(SECONDS)
    val frame = drawn(glitch, ramp(width, height), width, height)

    (0 until height step ROW_STEP).forEach { y ->
      val row = GlitchSignal.virtualRow(y, height)
      listOf(0, width / 4, width / 2, width * 3 / 4, width - 1).forEach { x ->
        val expected =
          GlitchSignal.pixelAt(
            (x + 0.5) / width,
            row,
            tick,
            glitch.seed,
            width,
            params,
          ) { rampTexel(it, width) }
        val at = (y * width + x) * CHANNELS
        expected.forEachIndexed { channel, value ->
          withClue("row $y column $x channel $channel of $width by $height") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus TOLERANCE)
          }
        }
      }
    }
  }

  private fun drawn(
    glitch: Glitch,
    image: CIImage,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
  ): FloatArray =
    rendered(
      glitchStep(glitch).apply(image, FrameInfo(attributes(width, height), seconds.seconds)),
      boundsWidth = width,
      boundsHeight = height,
    )

  private fun rendered(
    image: CIImage,
    originX: Int = 0,
    originY: Int = 0,
    boundsWidth: Int,
    boundsHeight: Int,
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

  // Core Image works in half floats unless told otherwise, whose rounding around 0.5 is wider than a read of 1e-4.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(
        NSNumber(int = kCIFormatRGBAf),
        forKey = NSString.create(string = checkNotNull(kCIContextWorkingFormat)),
      )
    return CIContext.contextWithOptions(options)
  }

  // A grey picture between two clear bars, the way a clip's canvas holds it.
  private fun canvas(): CIImage =
    imageOf(WIDTH, HEIGHT) { x, _ ->
      if (x in CANVAS_LEFT until CANVAS_RIGHT) floatArrayOf(GREY, GREY, GREY, 1f) else floatArrayOf(0f, 0f, 0f, 0f)
    }

  private fun ramp(
    width: Int,
    height: Int,
  ): CIImage = imageOf(width, height) { x, _ -> rampPixel(x, width) }

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

  private fun rampPixel(
    column: Int,
    width: Int,
  ): FloatArray {
    val across = column.toFloat() / (width - 1)
    return floatArrayOf(RAMP_RED + RAMP_SPAN * across, RAMP_GREEN - RAMP_SPAN * across, RAMP_BLUE, 1f)
  }

  // Bilinear over a straight ramp, flat past the centres of the two outside texels.
  private fun rampTexel(
    column: Int,
    width: Int,
  ): DoubleArray {
    val across = column.toDouble() / (width - 1)
    return doubleArrayOf(RAMP_RED + RAMP_SPAN * across, RAMP_GREEN - RAMP_SPAN * across, RAMP_BLUE.toDouble())
  }

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
    const val WIDTH = 640
    const val HEIGHT = 360
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val CHANNELS = 4
    const val SECONDS = 25.0
    const val ROW_STEP = 7
    const val TICKS = 600
    const val TILE = 24
    const val TILE_X = 8
    const val TILE_Y = 8
    const val CANVAS_LEFT = 160
    const val CANVAS_RIGHT = 480
    const val GREY = 0.5f
    const val SLIDE_PIXELS = 4.0
    const val RAMP_RED = 0.25f
    const val RAMP_GREEN = 0.6f
    const val RAMP_BLUE = 0.3f
    const val RAMP_SPAN = 0.3f
    const val TOLERANCE = 1e-4
    const val SEED_SEARCH = 100_000
    val MID = GlitchParams.at(0.5)
    val TICK = GlitchSignal.tickAt(SECONDS)

    // The first seed whose burst at the read is still rising, and the first with no burst there.
    val BURSTING =
      Glitch(
        0.5f,
        seed =
          (1..SEED_SEARCH).first { seed ->
            val now = GlitchSignal.burstAt(seed, TICK, MID)?.strength ?: 0.0
            val next = GlitchSignal.burstAt(seed, TICK + 1, MID)?.strength ?: 0.0
            now > 0.0 && next > now
          },
      )
    val QUIET = Glitch(0.5f, seed = (1..SEED_SEARCH).first { GlitchSignal.burstAt(it, TICK, MID) == null })
  }
}
