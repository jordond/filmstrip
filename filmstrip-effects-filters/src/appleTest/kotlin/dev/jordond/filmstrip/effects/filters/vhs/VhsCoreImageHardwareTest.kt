@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.tape.TAPE_PICTURE_KERNEL_LIMIT
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureFrame
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.tape.tapePictureKernelLimit
import dev.jordond.filmstrip.effects.filters.tape.tapePictureResolution
import dev.jordond.filmstrip.effects.filters.tape.tapePictureStep
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernel's own output, with no encoder in the way, against [TapePictureSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
class VhsCoreImageHardwareTest {
  @Test
  fun `the kernel lands on the signal at a mid intensity`() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = drawn(vhs, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    READ_ROWS.forEach { y -> assertRowMatches(frame, vhs, y, HEIGHT, WIDTH, ::rampTexel) }
  }

  @Test
  fun `a smaller frame draws the same tape at its own rows`() {
    val vhs = Vhs(intensity = 0.5f)
    val height = HEIGHT / 3
    val frame = drawn(vhs, ramp(WIDTH, height), WIDTH, height)

    listOf(
      height / 4,
      height / 2,
      height - 1,
    ).forEach { y -> assertRowMatches(frame, vhs, y, height, WIDTH, ::rampTexel) }
  }

  @Test
  fun `a hi8 dropout lands on the signal in the middle of its fall`() {
    val (hi8, y, column) = midFall(HEIGHT) { seed -> Hi8(intensity = 0.6f, seed = seed) }
    val frame = drawn(hi8, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    assertRowMatches(frame, hi8, y, HEIGHT, WIDTH, ::rampTexel, READ_COLUMNS + column)
  }

  @Test
  fun `a smaller frame draws the same hi8 dropout at its own rows`() {
    val height = HEIGHT / 3
    val (hi8, y, column) = midFall(height) { seed -> Hi8(intensity = 0.6f, seed = seed) }
    val frame = drawn(hi8, ramp(WIDTH, height), WIDTH, height)

    assertRowMatches(frame, hi8, y, height, WIDTH, ::rampTexel, READ_COLUMNS + column)
    listOf(height / 4, height / 2, height - 1).forEach { row ->
      assertRowMatches(frame, hi8, row, height, WIDTH, ::rampTexel)
    }
  }

  @Test
  fun `a betamax lands on the signal at a mid intensity`() {
    val betamax = Betamax(intensity = 0.6f)
    val frame = drawn(betamax, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    READ_ROWS.forEach { y -> assertRowMatches(frame, betamax, y, HEIGHT, WIDTH, ::rampTexel) }
  }

  @Test
  fun `snow in the middle of a rolling band lands on the signal`() {
    val frame = drawn(SNOWY, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val row = TapePictureSignal.virtualRow(HEIGHT / 2, HEIGHT)

    TapePictureSignal
      .snowAt(
        SNOWY.seed,
        TapePictureSignal.tickAt(SECONDS),
        row,
        0.5,
        TapePictureParams.of(SNOWY),
      ).weight shouldBeGreaterThan
      0.25
    listOf(HEIGHT * 2 / 5, HEIGHT / 2, HEIGHT * 3 / 5).forEach { y ->
      assertRowMatches(frame, SNOWY, y, HEIGHT, WIDTH, ::rampTexel)
    }
  }

  @Test
  fun `a smaller frame draws the same snow at its own rows`() {
    val height = HEIGHT / 3
    val frame = drawn(SNOWY, ramp(WIDTH, height), WIDTH, height)

    listOf(height * 2 / 5, height / 2, height * 3 / 5).forEach { y ->
      assertRowMatches(frame, SNOWY, y, height, WIDTH, ::rampTexel)
    }
  }

  // At 1080 every ninth row lands on an exact multiple of the virtual rows, which a float's rounding can land under.
  @Test
  fun `a row on an exact multiple of the virtual rows lands on the signal`() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = drawn(vhs, ramp(WIDTH, TALL_HEIGHT), WIDTH, TALL_HEIGHT)

    (0 until TALL_HEIGHT step EXACT_ROW_STEP).forEach { y ->
      assertRowMatches(frame, vhs, y, TALL_HEIGHT, WIDTH, ::rampTexel)
    }
  }

  @Test
  fun `a chroma edge smears as the signal says`() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = drawn(vhs, imageOf(WIDTH, HEIGHT, ::edge), WIDTH, HEIGHT)

    assertRowMatches(frame, vhs, HEIGHT / 2, HEIGHT, WIDTH, ::edgeTexel)
  }

  @Test
  fun `the same time draws the same picture after a seek`() {
    val vhs = Vhs(intensity = 0.5f)
    val first = drawn(vhs, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val elsewhere = drawn(vhs, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, SECONDS / 2)
    val again = drawn(vhs, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    again.contentEquals(first) shouldBe true
    elsewhere.contentEquals(first) shouldBe false
  }

  @Test
  fun `a frame away from the origin draws the same picture`() {
    val vhs = Vhs(intensity = 0.5f)
    val moved =
      ramp(WIDTH, HEIGHT).imageByApplyingTransform(CGAffineTransformMakeTranslation(MOVED_X, MOVED_Y))
    val step = tapePictureStep(vhs)
    val output = step.apply(moved, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val back = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(-MOVED_X, -MOVED_Y))
    val frame = rendered(back, WIDTH, HEIGHT)
    val still = drawn(vhs, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT)

    READ_ROWS.forEach { y ->
      READ_COLUMNS.forEach { column ->
        val at = (y * WIDTH + column) * CHANNELS
        withClue("row $y column $column") {
          frame[at].toDouble() shouldBe (still[at].toDouble() plusOrMinus TOLERANCE)
        }
      }
    }
  }

  // Straight colour, opaque on the left half and half alpha on the right, handed over premultiplied the way Core Image
  // holds it. A read between the two halves mixes them on Core Image's own weight, so the columns either side of the
  // step land within SAMPLER_WEIGHT_STEP and the rest of the row at the tolerance.
  @Test
  fun `a half alpha step lands on the signal in straight colour`() {
    val vhs = Vhs(intensity = 0.5f)
    val params = TapePictureParams.of(vhs)
    val tick = TapePictureSignal.tickAt(SECONDS)
    val straight = { column: Int -> rampTexel(column) + if (column < STEP_COLUMN) 1.0 else HALF_ALPHA }
    val image =
      imageOf(WIDTH, HEIGHT) { x, _ ->
        val texel = straight(x)
        FloatArray(CHANNELS) { (if (it == 3) texel[3] else texel[it] * texel[3]).toFloat() }
      }
    val frame = drawn(vhs, image, WIDTH, HEIGHT)

    READ_ROWS.forEach { y ->
      val row = TapePictureSignal.virtualRow(y, HEIGHT)
      // The column whose read lands between the last opaque column and the first half alpha one.
      val straddles = ceil(STEP_COLUMN - 1 + TapePictureSignal.rowShift(vhs.seed, tick, row, params) * WIDTH).toInt()
      val step = straddles - STEP_READS..straddles + STEP_READS
      (READ_COLUMNS.filter { it !in step } + step).forEach { column ->
        val wanted = TapePictureSignal.pixelAt((column + 0.5) / WIDTH, row, tick, vhs.seed, WIDTH, params, straight)
        val at = (y * WIDTH + column) * CHANNELS
        val within = if (column in step) SAMPLER_WEIGHT_STEP else TOLERANCE
        withClue("row $y column $column alpha") { frame[at + 3].toDouble() shouldBe (wanted[3] plusOrMinus within) }
        for (channel in 0 until RGB) {
          withClue("row $y column $column channel $channel premultiplied") {
            frame[at + channel].toDouble() shouldBe (wanted[channel] * wanted[3] plusOrMinus within)
          }
        }
      }
    }
  }

  @Test
  fun `alpha moves with the row and colour never raises it`() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = drawn(vhs, imageOf(WIDTH, HEIGHT, ::canvas), WIDTH, HEIGHT)
    val y = HEIGHT - 1
    val row = TapePictureSignal.virtualRow(y, HEIGHT)
    val shift =
      TapePictureSignal.rowShift(vhs.seed, TapePictureSignal.tickAt(SECONDS), row, TapePictureParams.of(vhs)) * WIDTH
    val alpha = { column: Double -> frame[(y * WIDTH + column.toInt()) * CHANNELS + 3].toDouble() }
    val red = { column: Double -> frame[(y * WIDTH + column.toInt()) * CHANNELS].toDouble() }

    withClue("picture moved into the right bar") {
      alpha(CANVAS_RIGHT + shift / 2) shouldBe (1.0 plusOrMinus TOLERANCE)
    }
    withClue("past the moved picture") {
      alpha(CANVAS_RIGHT + shift + BAR_MARGIN) shouldBe (0.0 plusOrMinus TOLERANCE)
      red(CANVAS_RIGHT + shift + BAR_MARGIN) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("the start of the picture's own rect") {
      alpha(CANVAS_LEFT + shift / 2) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("a bar the lift and the noise would have coloured") {
      alpha(BAR_MARGIN.toDouble()) shouldBe (0.0 plusOrMinus TOLERANCE)
      red(BAR_MARGIN.toDouble()) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
  }

  // An 8 bit target rounds the sum of the picture, the noise and the grade, so noise under half a code still tips the
  // codes it reaches. Rows of a picture with no change down the column differ by their grain and their jitter. A slope
  // keeps a fraction near every rounding, where a flat picture could sit out of the noise's reach.
  @Test
  fun `luma grain under half a code tips the rounded sum`() {
    val code = { column: Int -> (GRAIN_RAMP_LOW + column / GRAIN_RAMP_PIXELS_PER_CODE) / FULL_CODE }
    val image = imageOf(WIDTH, HEIGHT) { x, _ -> floatArrayOf(code(x), code(x), code(x), 1f) }
    val source = { column: Int -> code(column).toDouble().let { doubleArrayOf(it, it, it) } }
    listOf(0.25f, 0.4f).forEach { intensity ->
      val vhs = Vhs(intensity = intensity)
      val params = TapePictureParams.of(vhs)
      val tick = TapePictureSignal.tickAt(SECONDS)
      val output = tapePictureStep(vhs).apply(image, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
      val frame = renderedBytes(output, WIDTH, HEIGHT)
      val lumaOf = { pixel: DoubleArray -> pixel[0] * KR + pixel[1] * (1 - KR - KB) + pixel[2] * KB }
      val wantAt = { column: Int, y: Int ->
        val x = (column + 0.5) / WIDTH
        val row = TapePictureSignal.virtualRow(y, HEIGHT)
        FULL_CODE * lumaOf(TapePictureSignal.pixelAt(x, row, tick, vhs.seed, WIDTH, params, source))
      }
      val readAt = { column: Int, y: Int ->
        val at = (y * WIDTH + column) * CHANNELS
        lumaOf(
          DoubleArray(RGB) {
            frame[at + it].toUByte().toDouble()
          },
        )
      }
      val want = mutableListOf<Double>()
      val read = mutableListOf<Double>()
      (GRAIN_ROWS step 2).forEach { y ->
        GRAIN_COLUMNS.forEach { column ->
          val wanted = wantAt(column, y) - wantAt(column, y + 1)
          if (kotlin.math.abs(wanted) < GRAIN_MOST_CODES) {
            want += wanted
            read += readAt(column, y) - readAt(column, y + 1)
          }
        }
      }
      withClue("intensity $intensity") {
        correlationOf(want, read) shouldBeGreaterThan GRAIN_CORRELATION
      }
    }
  }

  // Core Image runs a kernel over anything a later composite asks for, whatever extent it was given.
  @Test
  fun `nothing is drawn past the frame`() {
    val output =
      tapePictureStep(
        Vhs(intensity = 0.5f),
      ).apply(ramp(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val moved = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(0.0, MOVED_Y))
    val taller = HEIGHT + 2 * MOVED_Y.toInt()
    val black =
      CIImage(
        color = CIColor.blackColor,
      ).imageByCroppingToRect(CGRectMake(0.0, 0.0, WIDTH.toDouble(), taller.toDouble()))
    val frame = rendered(moved.imageByCompositingOverImage(black), WIDTH, taller)

    listOf(MOVED_Y.toInt() / 2, taller - MOVED_Y.toInt() / 2).forEach { y ->
      withClue("row $y, outside the frame") {
        frame[(y * WIDTH + WIDTH / 2) * CHANNELS].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
      }
    }
  }

  @Test
  fun `this device compiles the kernel`() {
    tapePictureKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device with no kernels is refused by name`() {
    tapePictureKernelLimit(kernelsExist = false) shouldBe TAPE_PICTURE_KERNEL_LIMIT
    tapePictureKernelLimit(kernelsExist = true, compiles = { false }) shouldBe TAPE_PICTURE_KERNEL_LIMIT

    val refused =
      tapePictureResolution(
        Vhs(),
        TAPE_PICTURE_KERNEL_LIMIT,
      ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.specId shouldBe Vhs.ID
    refused.message shouldBe TAPE_PICTURE_KERNEL_LIMIT
  }

  @Test
  fun `a refused hi8 is named by its own id`() {
    val refused =
      tapePictureResolution(Hi8(), TAPE_PICTURE_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.specId shouldBe Hi8.ID
  }

  // The first seed, row and column where a dropout of the look [lookOf] builds is in the middle of its fall at the read
  // time, on a frame [height] tall.
  private fun midFall(
    height: Int,
    lookOf: (Int) -> TapeLook,
  ): Triple<TapeLook, Int, Int> {
    val tick = TapePictureSignal.tickAt(SECONDS)
    for (seed in 1..SEEDS) {
      val look = lookOf(seed)
      val params = TapePictureParams.of(look)
      for (y in 0 until height) {
        val row = TapePictureSignal.virtualRow(y, height)
        for (column in 1 until WIDTH) {
          val here = TapePictureSignal.dropout(seed, tick, row, (column + 0.5) / WIDTH, params)
          val before = TapePictureSignal.dropout(seed, tick, row, (column - 0.5) / WIDTH, params)
          if (here in MID_FALL && before > here) return Triple(look, y, column)
        }
      }
    }
    error("no dropout in the middle of its fall in $SEEDS seeds")
  }

  private fun renderedBytes(
    image: CIImage,
    width: Int,
    height: Int,
  ): ByteArray {
    val values = ByteArray(width * height * CHANNELS)
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      values.usePinned { pinned ->
        CIContext().render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (width * CHANNELS).toLong(),
          bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
          format = kCIFormatRGBA8,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return values
  }

  @Suppress("UNUSED_PARAMETER")
  private fun edge(
    column: Int,
    y: Int,
  ): FloatArray = if (column < WIDTH / 2) EDGE_LEFT else EDGE_RIGHT

  private fun edgeTexel(column: Int): DoubleArray =
    DoubleArray(RGB) { (if (column < WIDTH / 2) EDGE_LEFT else EDGE_RIGHT)[it].toDouble() }

  // Picture between the two canvas edges, clear either side.
  @Suppress("UNUSED_PARAMETER")
  private fun canvas(
    column: Int,
    y: Int,
  ): FloatArray =
    if (column in CANVAS_LEFT until CANVAS_RIGHT) rampPixel(column, WIDTH) else floatArrayOf(0f, 0f, 0f, 0f)

  private fun correlationOf(
    a: List<Double>,
    b: List<Double>,
  ): Double {
    val meanA = a.average()
    val meanB = b.average()
    val cross = a.indices.sumOf { (a[it] - meanA) * (b[it] - meanB) }
    return cross / sqrt(a.sumOf { (it - meanA).pow(2) } * b.sumOf { (it - meanB).pow(2) })
  }

  private companion object {
    // Mid snow in a tall band that rolls through the frame once every two read times, so it sits mid frame at the read.
    val SNOWY = Vhs(tracking = Tracking.Rolling((2 * SECONDS).seconds, height = 0.3f, snow = 0.5f))

    const val SEEDS = 200
    val MID_FALL = 0.3..0.7
    const val HALF_ALPHA = 0.5
    const val STEP_COLUMN = WIDTH / 2
    const val STEP_READS = 2
    const val MOVED_X = 40.0
    const val MOVED_Y = 25.0
    const val TALL_HEIGHT = 1080
    const val EXACT_ROW_STEP = 9
    val EDGE_LEFT = floatArrayOf(0.5f, 0.3f, 0.2f, 1f)
    val EDGE_RIGHT = floatArrayOf(0.25f, 0.5f, 0.6f, 1f)
    const val CANVAS_LEFT = 160
    const val CANVAS_RIGHT = 480
    const val BAR_MARGIN = 8
    const val FULL_CODE = 255f
    const val GRAIN_RAMP_LOW = 40
    const val GRAIN_RAMP_PIXELS_PER_CODE = 4
    val GRAIN_ROWS = 60 until 200
    val GRAIN_COLUMNS = 40 until 600
    const val GRAIN_MOST_CODES = 3.0
    const val GRAIN_CORRELATION = 0.5
  }
}
