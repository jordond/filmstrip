@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.crt

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
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
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
 * Reads the kernel's own output, with no encoder in the way, against [CrtSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top, which the patterns read the same from.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
class CrtCoreImageHardwareTest {
  @Test
  fun `the kernel lands on the signal at a mid intensity`() = assertLandsOnTheSignal(WIDTH, HEIGHT)

  @Test
  fun `a 1080p frame lands on the signal`() = assertLandsOnTheSignal(FULL_HD_WIDTH, FULL_HD_HEIGHT)

  @Test
  fun `a frame odd to the patterns lands on the signal`() = assertLandsOnTheSignal(UNEVEN_WIDTH, UNEVEN_HEIGHT)

  @Test
  fun `a tall frame lands on the signal`() = assertLandsOnTheSignal(HEIGHT, WIDTH)

  @Test
  fun `a smaller frame is the larger frame boxed down at even and at wide factors`() {
    assertBoxedDown(LARGE_WIDTH, LARGE_HEIGHT, factor = 2)
    assertBoxedDown(FULL_HD_WIDTH, FULL_HD_HEIGHT, factor = 3)
  }

  @Test
  fun `a frame away from the origin draws the same picture`() {
    val crt = Crt(MID)
    val moved = ramp(WIDTH, HEIGHT).imageByApplyingTransform(CGAffineTransformMakeTranslation(MOVED_X, MOVED_Y))
    val output = crtStep(crt).apply(moved, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val back = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(-MOVED_X, -MOVED_Y))
    val frame = rendered(back, WIDTH, HEIGHT)
    val still = drawn(crt, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    for (y in (0 until HEIGHT) step SPREAD_STRIDE) {
      for (x in (0 until WIDTH) step SPREAD_STRIDE) {
        val at = (y * WIDTH + x) * CHANNELS
        withClue("row $y column $x") { frame[at].toDouble() shouldBe (still[at].toDouble() plusOrMinus TOLERANCE) }
      }
    }
  }

  @Test
  fun `the same picture draws at any time`() {
    val crt = Crt(MID)
    val first = drawn(crt, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val elsewhere =
      rendered(
        crtStep(crt).apply(ramp(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT), (SECONDS / 2).seconds)),
        WIDTH,
        HEIGHT,
      )

    elsewhere.contentEquals(first) shouldBe true
  }

  // Core Image does not hold a small frame to the region it asks for, so the region itself is read here. The curve
  // moves every read outward from the centre, so a tile's reads reach furthest from its corners.
  @Test
  fun `the source region of a tile holds every read under it`() {
    val params = CrtParams.of(Crt(1f))
    listOf(
      CGRectMake(0.0, 0.0, 1920.0, 1080.0),
      CGRectMake(8.0, 8.0, 24.0, 24.0),
      CGRectMake(1880.0, 1040.0, 40.0, 40.0),
    ).forEach { tile ->
      val (x, y, w, h) = tile.useContents { listOf(origin.x, origin.y, size.width, size.height) }
      val region = crtSourceRegion(tile, 1920, 1080, params.barrelK)
      region.useContents {
        listOf(x to y, x + w to y, x to y + h, x + w to y + h).forEach { (cornerX, cornerY) ->
          val source = CrtSignal.barrelMap(cornerX / 1920, cornerY / 1080, 1920, 1080, params.barrelK)
          withClue("$cornerX $cornerY") {
            (source[0] * 1920 >= origin.x && source[0] * 1920 <= origin.x + size.width) shouldBe true
            (source[1] * 1080 >= origin.y && source[1] * 1080 <= origin.y + size.height) shouldBe true
          }
        }
      }
    }
  }

  @Test
  fun `a corner tile reads the same as the whole frame`() {
    val crt = Crt(1f)
    val whole = drawn(crt, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val output = crtStep(crt).apply(ramp(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val tile =
      rendered(output, WIDTH, HEIGHT, originX = TILE_X, originY = TILE_Y, boundsWidth = TILE, boundsHeight = TILE)

    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        // A bitmap row runs from the top, of the tile here and of the frame there, and the tile sits at the bottom.
        val wholeAt = ((HEIGHT - TILE_Y - TILE + y) * WIDTH + TILE_X + x) * CHANNELS
        val tileAt = (y * TILE + x) * CHANNELS
        withClue("row $y column $x") {
          tile[tileAt].toDouble() shouldBe
            (whole[wholeAt].toDouble() plusOrMinus TOLERANCE)
        }
      }
    }
  }

  @Test
  fun `a clear bar stays clear and a pictures edge ends in black with its alpha`() {
    val crt = Crt(1f)
    val params = CrtParams.of(crt)
    val frame =
      drawn(
        crt,
        imageOf(WIDTH, HEIGHT) { x, _ ->
          if (x in CANVAS_LEFT until CANVAS_RIGHT) floatArrayOf(BRIGHT, BRIGHT, BRIGHT, 1f) else FloatArray(CHANNELS)
        },
        WIDTH,
        HEIGHT,
      )

    val bar = (HEIGHT / 2 * WIDTH + CANVAS_LEFT / 2) * CHANNELS
    withClue("the bar") {
      frame[bar].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
      frame[bar + 3].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    val row = (0 until HEIGHT).first { coverage(WIDTH / 2, it, WIDTH, HEIGHT, params) > 0.0 } - 1
    val edge = (row * WIDTH + WIDTH / 2) * CHANNELS
    withClue("past the curve the picture is black, with the alpha of the edge it clamps to") {
      frame[edge].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
      frame[edge + 3].toDouble() shouldBe (1.0 plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `the gain stops at a premultiplied pixels own alpha`() {
    val crt = Crt(1f)
    val params = CrtParams.of(crt)
    val frame =
      drawn(
        crt,
        imageOf(FULL_HD_WIDTH, FULL_HD_HEIGHT) { _, _ -> floatArrayOf(HALF, HALF, HALF, HALF) },
        FULL_HD_WIDTH,
        FULL_HD_HEIGHT,
      )

    val (x, y) =
      (0 until CREST_READS)
        .flatMap { dy -> (0 until CREST_READS).map { dx -> FULL_HD_WIDTH / 2 + dx to FULL_HD_HEIGHT / 2 + dy } }
        .first { (x, y) -> unclamped(x, y, params) > 1.0 + CREST_MARGIN }

    val at = (y * FULL_HD_WIDTH + x) * CHANNELS
    frame[at].toDouble() shouldBe (HALF.toDouble() plusOrMinus TOLERANCE)
    frame[at + 3].toDouble() shouldBe (HALF.toDouble() plusOrMinus TOLERANCE)
  }

  @Test
  fun `this device compiles the kernel`() {
    crtKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device with no kernels is refused by name`() {
    crtKernelLimit(kernelsExist = false) shouldBe CRT_KERNEL_LIMIT
    crtKernelLimit(kernelsExist = true, compiles = { false }) shouldBe CRT_KERNEL_LIMIT

    val refused = crtResolution(Crt(), CRT_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.specId shouldBe Crt.ID
    refused.message shouldBe CRT_KERNEL_LIMIT
  }

  private fun assertLandsOnTheSignal(
    width: Int,
    height: Int,
  ) {
    val crt = Crt(MID)
    val params = CrtParams.of(crt)
    val frame = drawn(crt, ramp(width, height), width, height)

    val rows = readsAcross(height) { coverage(width / 2, it, width, height, params) > 0.0 }
    val columns = readsAcross(width) { coverage(it, height / 2, width, height, params) > 0.0 }
    rows.forEach { y ->
      columns.forEach { x ->
        val expected =
          CrtSignal.pixelAt(
            (x + 0.5) / width,
            (y + 0.5) / height,
            width,
            height,
            params,
          ) { column, _ -> rampTexel(column, width) }
        val tolerance = if (coverage(x, y, width, height, params) in EDGE_RANGE) EDGE_TOLERANCE else TOLERANCE
        val at = (y * width + x) * CHANNELS
        expected.forEachIndexed { channel, value ->
          withClue("row $y column $x channel $channel of $width by $height") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
          }
        }
      }
    }
  }

  private fun assertBoxedDown(
    width: Int,
    height: Int,
    factor: Int,
  ) {
    val crt = Crt(MID)
    val flat = { _: Int, _: Int -> floatArrayOf(GREY, GREY, GREY, 1f) }
    val large = drawn(crt, imageOf(width, height, flat), width, height)
    val smallWidth = width / factor
    val smallHeight = height / factor
    val small = drawn(crt, imageOf(smallWidth, smallHeight, flat), smallWidth, smallHeight)

    var worst = 0.0
    var worstPoint = 0.0
    for (row in 0 until smallHeight) {
      for (column in 0 until smallWidth) {
        for (channel in 0 until RGB) {
          val read = small[(row * smallWidth + column) * CHANNELS + channel].toDouble()
          var sum = 0.0
          for (dy in 0 until factor) {
            for (dx in 0 until factor) {
              sum += large[((row * factor + dy) * width + column * factor + dx) * CHANNELS + channel]
            }
          }
          worst = max(worst, abs(read - sum / (factor * factor)))
          worstPoint = max(worstPoint, abs(read - large[(row * factor * width + column * factor) * CHANNELS + channel]))
        }
      }
    }
    withClue("a nearest read of the large frame has to miss for this to tell, it reads $worstPoint") {
      worstPoint shouldBeGreaterThan PARITY * 2
    }
    withClue("$width by $height at a factor of $factor") { worst shouldBeLessThan PARITY }
  }

  // What the look multiplies red by at a 1080p pixel, before anything stops it.
  private fun unclamped(
    x: Int,
    y: Int,
    params: CrtParams,
  ): Double {
    val u = (x + 0.5) / FULL_HD_WIDTH
    val v = (y + 0.5) / FULL_HD_HEIGHT
    val source = CrtSignal.barrelMap(u, v, FULL_HD_WIDTH, FULL_HD_HEIGHT, params.barrelK)
    return CrtSignal.scanlineAt(source[1], 1.0 / FULL_HD_HEIGHT, params.scanlineDepth) * params.gain *
      CrtSignal.maskAt(source[0], 0, 1.0 / FULL_HD_WIDTH, params.maskDepth) *
      CrtSignal.vignetteAt(u, v, FULL_HD_WIDTH, FULL_HD_HEIGHT, params.vignette)
  }

  private fun coverage(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    params: CrtParams,
  ): Double = CrtSignal.coverageAt((x + 0.5) / width, (y + 0.5) / height, width, height, params.barrelK)

  private fun readsAcross(
    size: Int,
    lit: (Int) -> Boolean,
  ): List<Int> {
    val edge = (0 until size).first(lit)
    val spread = (0 until SPREAD).map { it * (size - 1) / (SPREAD - 1) }
    val atEdge = listOf(edge - 1, edge, edge + 1, size - edge - 2, size - edge - 1, size - edge)
    return (spread + atEdge + (size / 2)).filter { it in 0 until size }.distinct()
  }

  private fun drawn(
    crt: Crt,
    image: CIImage,
    width: Int,
    height: Int,
  ): FloatArray =
    rendered(crtStep(crt).apply(image, FrameInfo(attributes(width, height), SECONDS.seconds)), width, height)

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

  // Core Image works in half floats unless told otherwise, whose rounding around 0.5 is wider than a read of 1e-4.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
    return CIContext.contextWithOptions(options as Map<Any?, *>)
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
    const val MID = 0.5f
    const val WIDTH = 640
    const val HEIGHT = 360
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val CHANNELS = 4
    const val RGB = 3
    const val SECONDS = 1.5
    const val SPREAD = 9
    const val SPREAD_STRIDE = 13
    const val MOVED_X = 40.0
    const val MOVED_Y = 25.0
    const val TILE = 24
    const val TILE_X = 8
    const val TILE_Y = 8
    const val CANVAS_LEFT = 160
    const val CANVAS_RIGHT = 480
    const val GREY = 0.5f
    const val BRIGHT = 0.75f
    const val HALF = 0.5f
    const val CREST_READS = 8
    const val CREST_MARGIN = 0.02
    const val RAMP_RED = 0.25f
    const val RAMP_GREEN = 0.6f
    const val RAMP_BLUE = 0.3f
    const val RAMP_SPAN = 0.3f
    const val TOLERANCE = 1e-4

    // A source position in a 32 bit float is good to about 1e-7, which over a pixel's reach moves the coverage by a few
    // 1e-4. That is the GPU's float, and only a pixel the edge crosses reads it.
    const val EDGE_TOLERANCE = 5e-4
    val EDGE_RANGE = 1e-6..(1.0 - 1e-6)

    // A box and a small frame differ by the footprint's stretch, and by a pattern weighed unevenly where the edge
    // crosses.
    const val PARITY = 0.012
  }
}
