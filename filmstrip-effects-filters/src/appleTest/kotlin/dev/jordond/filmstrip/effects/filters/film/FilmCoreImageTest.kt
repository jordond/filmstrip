package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
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
 * Reads the kernels' own output, with no encoder in the way, against [FilmSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
class FilmCoreImageTest {
  @Test
  fun `core image's blur lands within two codes of the plan's kernel`() {
    val plan = FilmSignal.halationBlurPlan(BLUR_WIDTH)
    val patch = imageOf(BLUR_WIDTH, BLUR_HEIGHT) { x, y -> if (inPatch(x, y)) WHITE else CLEAR_BLACK }
    val small = CGRectMake(0.0, 0.0, BLUR_WIDTH.toDouble(), BLUR_HEIGHT.toDouble())
    val frame = rendered(blurredGlow(patch, plan.sigmaPx, small), BLUR_WIDTH, BLUR_HEIGHT)

    var worst = 0.0
    for (y in 0 until BLUR_HEIGHT step READ_STEP) {
      for (x in 0 until BLUR_WIDTH step READ_STEP) {
        val expected = planBlur(plan, x, y)
        worst = maxOf(worst, abs(frame[(y * BLUR_WIDTH + x) * CHANNELS].toDouble() - expected))
      }
    }
    withClue("the worst read landed ${worst * FULL} codes off") { (worst * FULL < BLUR_CODES) shouldBe true }
  }

  @Test
  fun `grain alone lands on the signal at a mid strength`() {
    val film = Film(grain = 0.5f, halation = 0f)
    val params = FilmParams.of(film)
    val frame = drawn(film, WIDTH, HEIGHT)

    assertFrameMatches(frame, WIDTH, HEIGHT, TOLERANCE) { x, y, codes ->
      FilmSignal.grained(
        codes[0],
        codes[1],
        codes[2],
        FilmSignal.grainCell(x, y, WIDTH, HEIGHT),
        FilmSignal.grainTickAt(SECONDS),
        params,
      )
    }
  }

  @Test
  fun `halation alone lands on the signal at a mid strength`() {
    val film = Film(grain = 0f, halation = 0.5f)
    val params = FilmParams.of(film)
    val glow = glowOf(WIDTH, HEIGHT)
    val frame = drawn(film, WIDTH, HEIGHT)

    assertFrameMatches(frame, WIDTH, HEIGHT, chainTolerance(params)) { x, y, codes ->
      FilmSignal.halated(codes[0], codes[1], codes[2], glow.at(x, y), params)
    }
  }

  @Test
  fun `the whole chain lands on the signal at a mid strength`() {
    assertChainMatches(WIDTH, HEIGHT)
  }

  @Test
  fun `a smaller frame draws the same look at its own size`() {
    assertChainMatches(WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun `a large frame blurs at the plan's smaller size`() {
    (FilmSignal.halationBlurPlan(LARGE_WIDTH).factor > 1) shouldBe true
    assertChainMatches(LARGE_WIDTH, LARGE_HEIGHT)
  }

  @Test
  fun `a 1080p frame blurs at a factor past two`() {
    (FilmSignal.halationBlurPlan(FULL_HD_WIDTH).factor > 2) shouldBe true
    assertChainMatches(FULL_HD_WIDTH, FULL_HD_HEIGHT)
  }

  @Test
  fun `a frame its factor does not divide blurs at the plan's size`() {
    (UNEVEN_WIDTH % FilmSignal.halationBlurPlan(UNEVEN_WIDTH).factor != 0) shouldBe true
    assertChainMatches(UNEVEN_WIDTH, UNEVEN_HEIGHT)
  }

  @Test
  fun `the same time draws the same picture after a seek`() {
    val film = Film()
    val first = drawn(film, WIDTH, HEIGHT)
    val elsewhere = drawn(film, WIDTH, HEIGHT, seconds = SECONDS + 1.0)
    val again = drawn(film, WIDTH, HEIGHT)

    withClue("the grain moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun `a bar keeps its alpha and stays clear`() {
    val film = Film(grain = 1f, halation = 1f)
    val picture = floatArrayOf(PATCH[0].toFloat(), PATCH[1].toFloat(), PATCH[2].toFloat(), 1f)
    val canvas =
      imageOf(WIDTH, HEIGHT) { x, _ -> if (x in CANVAS_LEFT until CANVAS_RIGHT) picture else CLEAR_BLACK }
    val step = filmStep(film)
    val frame = rendered(step.apply(canvas, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds)), WIDTH, HEIGHT)

    val y = HEIGHT / 2
    val at = { x: Int, channel: Int -> frame[(y * WIDTH + x) * CHANNELS + channel].toDouble() }
    withClue("a bar stays clear") {
      at(CANVAS_LEFT / 2, ALPHA) shouldBe (0.0 plusOrMinus TOLERANCE)
      at(CANVAS_LEFT / 2, 0) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("the picture stays opaque") { at(WIDTH / 2, ALPHA) shouldBe (1.0 plusOrMinus TOLERANCE) }
  }

  @Test
  fun `no kernel refuses a film by name`() {
    filmKernelLimit().shouldBeNull()
    filmKernelLimit(kernelsExist = false) shouldBe FILM_KERNEL_LIMIT
    filmKernelLimit(kernelsExist = true, compiles = { false }) shouldBe FILM_KERNEL_LIMIT
    filmResolution(Film(), FILM_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
  }

  private fun assertChainMatches(
    width: Int,
    height: Int,
  ) {
    val film = Film()
    val params = FilmParams.of(film)
    val glow = glowOf(width, height)
    val frame = drawn(film, width, height)

    assertFrameMatches(frame, width, height, chainTolerance(params)) { x, y, codes ->
      FilmSignal.pixelAt(
        codes[0],
        codes[1],
        codes[2],
        glow.at(x, y),
        FilmSignal.grainCell(x, y, width, height),
        FilmSignal.grainTickAt(SECONDS),
        params,
      )
    }
  }

  // The blur's limit reaches the frame through the gain, on top of the float reads' own rounding.
  private fun chainTolerance(params: FilmParams): Double = BLUR_CODES * params.halationGain / FULL + TOLERANCE

  private fun assertFrameMatches(
    frame: FloatArray,
    width: Int,
    height: Int,
    tolerance: Double,
    expected: (x: Int, y: Int, codes: DoubleArray) -> DoubleArray,
  ) {
    val columns = listOf(width / 8, width / 2 - 1, width / 2 + 2, width / 2 + width / 16, width - 2)
    listOf(height / 8, height / 3, height / 2 + height / 16, height - 2).forEach { y ->
      columns.forEach { x ->
        val wanted = expected(x, y, codesAt(x, y, width, height))
        val at = (y * width + x) * CHANNELS
        wanted.forEachIndexed { channel, value ->
          withClue("row $y column $x channel $channel of a ${width}x$height frame") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
          }
        }
      }
    }
  }

  private fun glowOf(
    width: Int,
    height: Int,
  ) = HalationGlow.of(width, height) { x, y -> codesAt(x, y, width, height) }

  // A bright patch in the upper left, which sits on the middle of the response's ramp, over a midtone.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray = if (x < width / 2 && y < height / 2) PATCH.copyOf() else GROUND.copyOf()

  private fun inPatch(
    x: Int,
    y: Int,
  ) = x in BLUR_LEFT until BLUR_RIGHT && y in BLUR_TOP until BLUR_BOTTOM

  private fun planBlur(
    plan: WideBlurPlan,
    x: Int,
    y: Int,
  ): Double {
    val weight = { d: Int -> if (abs(d) > plan.taps) 0.0 else plan.weights[abs(d)] }
    var sum = 0.0
    for (py in BLUR_TOP until BLUR_BOTTOM) {
      for (px in BLUR_LEFT until BLUR_RIGHT) sum += weight(x - px) * weight(y - py)
    }
    return sum
  }

  private fun drawn(
    film: Film,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
  ): FloatArray {
    val image =
      imageOf(width, height) { x, y ->
        val codes = codesAt(x, y, width, height)
        floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
      }
    return rendered(filmStep(film).apply(image, FrameInfo(attributes(width, height), seconds.seconds)), width, height)
  }

  private fun attributes(
    width: Int,
    height: Int,
  ) = Attributes(
    inputSize = Size(width, height),
    outputSize = Size(width, height),
    layoutSize = Size(width, height),
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = TimeRange(0.seconds, 4.seconds),
  )

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

  // Core Image works in half floats unless told otherwise, whose rounding around 0.5 is wider than a read of 1e-4.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
    return CIContext.contextWithOptions(options as Map<Any?, *>)
  }

  // Rows of the bitmap run from the top, where [pixel] counts its y.
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

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val BLUR_WIDTH = 512
    const val BLUR_HEIGHT = 288
    const val BLUR_LEFT = 200
    const val BLUR_RIGHT = 232
    const val BLUR_TOP = 120
    const val BLUR_BOTTOM = 152
    const val READ_STEP = 7
    const val CHANNELS = 4
    const val ALPHA = 3
    const val FULL = 255.0
    const val SECONDS = 25.0
    const val TOLERANCE = 1e-4
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384

    // Core Image's Gaussian is Core Image's own limit. It lands about one code off the plan's kernel on a patch.
    const val BLUR_CODES = 2.0
    val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
  }
}
