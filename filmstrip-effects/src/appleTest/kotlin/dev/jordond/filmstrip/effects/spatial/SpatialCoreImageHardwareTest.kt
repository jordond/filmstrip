@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.sdrSignalFromHlgSignal
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A blur and a vignette drawn through Core Image, each read against the shared function it lowers.
 *
 * On SDR a frame goes in and comes out in the codes of the BT.709 buffer. On a kept grade it is linear BT.2020 light
 * with reference white at one, and each read is carried to the SDR signal [sdrSignalFromNits] gives it, which is the
 * domain both effects run in.
 */
@OptIn(ExperimentalForeignApi::class)
class SpatialCoreImageHardwareTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `a blur at export width matches the shared plan`() = assertBlurMatches(Size(WIDTH, HEIGHT), transfer = null)

  @Test
  fun `a blur at preview width matches the shared plan`() =
    assertBlurMatches(Size(WIDTH / 2, HEIGHT / 2), transfer = null)

  @Test
  fun `a blur on a width its factor does not divide matches the shared plan`() {
    WIDTH % BlurPlan.of(UNEVEN_SIGMA.toDouble(), WIDTH).factor shouldBeGreaterThan 0

    assertBlurMatches(Size(WIDTH, HEIGHT), transfer = null, sigma = UNEVEN_SIGMA)
  }

  @Test
  fun `a blur across a horizontal edge matches the shared plan`() =
    assertBlurMatches(Size(WIDTH, HEIGHT), transfer = null, across = true)

  @Test
  fun `a blur on a kept pq grade matches the shared plan on the sdr signal`() =
    assertBlurMatches(Size(WIDTH, HEIGHT), HdrTransfer.Pq)

  @Test
  fun `a blur on a kept hlg grade matches the shared plan on the sdr signal`() =
    assertBlurMatches(Size(WIDTH, HEIGHT), HdrTransfer.Hlg, across = true)

  // Core Image decodes a colored HLG pixel through an opto-optical transfer that reads all three channels at once, so
  // its light is not a power of the signal the blur runs on. Only a colored edge tells the two apart, and the middle of
  // the edge is where each side counts for most.
  @Test
  fun `a blur across a colored hlg edge matches the shared plan on the sdr signal`() {
    val size = Size(WIDTH, HEIGHT)
    val frame = hlgFrameOf(size) { x, _ -> if (x < WIDTH / 2) HLG_WARM else HLG_COOL }
    val drawn =
      hlgCodesOf(stepFor(GaussianBlur(MID_SIGMA), HdrTransfer.Hlg).apply(frame, frameInfo(size, HdrTransfer.Hlg)), size)
    val plain = hlgCodesOf(frame, size)
    val pixels = WIDTH * HEIGHT
    val edge = HEIGHT / 2 * WIDTH + WIDTH / 2

    repeat(COLORS) { channel ->
      val signal = DoubleArray(pixels) { sdrSignalFromHlgSignal(plain[it * CHANNELS + channel].toDouble()) }
      val expected = blurPlane(signal, DoubleArray(pixels) { 1.0 }, WIDTH, HEIGHT, MID_SIGMA.toDouble())
      val read = DoubleArray(pixels) { sdrSignalFromHlgSignal(drawn[it * CHANNELS + channel].toDouble()) }

      withClue("channel $channel of the reference blur moves the edge by ${expected[edge] - signal[edge]}") {
        abs(expected[edge] - signal[edge]) shouldBeGreaterThan SHARP_SPREAD / 2
      }
      withClue("channel $channel read ${read[edge]} at the middle of the edge and the shared plan ${expected[edge]}") {
        abs(read[edge] - expected[edge]) * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
      val worst = (0 until pixels).maxOf { abs(read[it] - expected[it]) }
      withClue("channel $channel of the drawn blur is ${worst * FULL} codes off the shared reference at its worst") {
        worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
      }
    }
  }

  @Test
  fun `a blur keeps clear bars clear and the picture's level at its edge`() {
    val size = Size(WIDTH, HEIGHT)
    val alpha = { _: Int, y: Int -> if (y in BAR until HEIGHT - BAR) 1f else 0f }
    val frame = frameOf(size, transfer = null, alpha = alpha) { x, _ -> if (x < WIDTH / 2) SDR_LIGHT else SDR_DARK }
    val drawn = pixelsOf(stepFor(GaussianBlur(MID_SIGMA), null).apply(frame, frameInfo(size, null)), size, null)
    val plain = pixelsOf(frame, size, null)
    val mask = DoubleArray(WIDTH * HEIGHT) { alpha(it % WIDTH, it / WIDTH).toDouble() }
    val expected =
      blurPlane(DoubleArray(mask.size) { plain.red[it].toDouble() }, mask, WIDTH, HEIGHT, MID_SIGMA.toDouble())

    var worst = 0.0
    for (index in expected.indices) {
      if (mask[index] == 0.0) {
        withClue("bar pixel ${index % WIDTH} ${index / WIDTH}") {
          drawn.alpha[index] shouldBe 0f
          drawn.red[index] shouldBe 0f
        }
      } else {
        worst = maxOf(worst, abs(drawn.red[index] - expected[index]))
      }
    }
    // The picture's first row, beside the bar, reads as its middle does, since every row of it is the same.
    val edge = BAR * WIDTH + WIDTH / 4
    val middle = HEIGHT / 2 * WIDTH + WIDTH / 4
    withClue("the picture's edge read ${drawn.red[edge]} and its middle ${drawn.red[middle]}") {
      abs(drawn.red[edge] - drawn.red[middle]) * FULL shouldBeLessThanOrEqualTo BLUR_CODES
    }
    withClue("the drawn blur is ${worst * FULL} codes off the shared reference at its worst picture pixel") {
      worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
    }
  }

  @Test
  fun `a vignette follows the shared gain along its ramp`() =
    assertVignetteMatches(Size(WIDTH, HEIGHT), transfer = null)

  @Test
  fun `a vignette at preview height follows the shared gain`() =
    assertVignetteMatches(Size(WIDTH / 2, HEIGHT / 2), transfer = null)

  @Test
  fun `a vignette on a kept grade takes the shared gain on the sdr signal`() =
    assertVignetteMatches(Size(WIDTH, HEIGHT), HdrTransfer.Pq)

  @Test
  fun `no kernel refuses a blur and a vignette by name`() {
    val limit = spatialKernelLimit(kernelsExist = false)
    val blur = GaussianBlur(MID_SIGMA).toStep(limit).shouldBeInstanceOf<EffectResolution.Unsupported>()
    val vignette = Vignette().toStep(limit).shouldBeInstanceOf<EffectResolution.Unsupported>()

    blur.message shouldContain "Metal kernel"
    vignette.message shouldContain "Metal kernel"
    spatialKernelLimit(kernelsExist = true) { false }.shouldNotBeNull()
    spatialKernelLimit(kernelsExist = true) { true } shouldBe null
  }

  // Reads a blur of an edge down the frame, or across it with [across], against the shared reference run on the frame
  // without it, in the domain the effects run in.
  private fun assertBlurMatches(
    size: Size,
    transfer: HdrTransfer?,
    sigma: Float = MID_SIGMA,
    across: Boolean = false,
  ) {
    val (light, dark) = if (transfer == null) SDR_LIGHT to SDR_DARK else GRADE_LIGHT to GRADE_DARK
    val frame =
      frameOf(size, transfer) { x, y -> if (if (across) y < size.height / 2 else x < size.width / 2) light else dark }
    val drawn = pixelsOf(stepFor(GaussianBlur(sigma), transfer).apply(frame, frameInfo(size, transfer)), size, transfer)
    val plain = pixelsOf(frame, size, transfer)
    val pixels = size.width * size.height
    val signal = DoubleArray(pixels) { signalOf(plain.red[it], transfer) }
    val expected = blurPlane(signal, DoubleArray(pixels) { 1.0 }, size.width, size.height, sigma.toDouble())

    var worst = 0.0
    var spread = 0.0
    for (index in 0 until pixels) {
      worst = maxOf(worst, abs(signalOf(drawn.red[index], transfer) - expected[index]))
      spread = maxOf(spread, abs(expected[index] - signal[index]))
    }
    withClue("the reference blur moves the edge by $spread, so the read is not of a sharp frame") {
      spread shouldBeGreaterThan SHARP_SPREAD
    }
    withClue("the drawn blur is ${worst * FULL} codes off the shared reference at its worst pixel") {
      worst * FULL shouldBeLessThanOrEqualTo BLUR_CODES
    }
  }

  private fun assertVignetteMatches(
    size: Size,
    transfer: HdrTransfer?,
  ) {
    val vignette = Vignette()
    val level = if (transfer == null) SDR_GREY else GRADE_GREY
    val frame = frameOf(size, transfer) { _, _ -> level }
    val drawn = pixelsOf(stepFor(vignette, transfer).apply(frame, frameInfo(size, transfer)), size, transfer)
    val plain = signalOf(level, transfer)

    for ((x, y) in VIGNETTE_POINTS) {
      val column = (x * size.width).toInt()
      val row = (y * size.height).toInt()
      val gain =
        vignetteGain(
          (column + 0.5) / size.width,
          (row + 0.5) / size.height,
          size.width.toDouble() / size.height,
          vignette.strength.toDouble(),
          vignette.radius.toDouble(),
          vignette.softness.toDouble(),
        )
      val read = signalOf(drawn.red[row * size.width + column], transfer)
      withClue("at $x $y the vignette read $read and the shared gain $gain puts it at ${plain * gain}") {
        abs(read - plain * gain) * FULL shouldBeLessThanOrEqualTo VIGNETTE_CODES
      }
    }
  }

  private fun stepFor(
    spec: EffectSpec,
    transfer: HdrTransfer?,
  ) = resolver
    .resolve(spec, CAPABILITIES, attributes(Size(WIDTH, HEIGHT), transfer))
    .shouldBeInstanceOf<EffectResolution.Resolved>()
    .effect.step

  // The red channel and the alpha of every pixel of [image], rows from the top, in BT.709 codes on SDR and in light on
  // a grade, premultiplied as Core Image renders them.
  private fun pixelsOf(
    image: CIImage,
    size: Size,
    transfer: HdrTransfer?,
  ): Pixels {
    val values = FloatArray(size.width * size.height * CHANNELS)
    val space = spaceOf(transfer)
    try {
      values.usePinned { pinned ->
        renderingContext(floatFormat = true).render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (size.width * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    val pixels = size.width * size.height
    return Pixels(FloatArray(pixels) { values[it * CHANNELS] }, FloatArray(pixels) { values[it * CHANNELS + 3] })
  }

  // A grey frame whose level at each pixel is [level] and whose alpha is [alpha], in BT.709 codes on SDR and in light
  // on a grade.
  private fun frameOf(
    size: Size,
    transfer: HdrTransfer?,
    alpha: (Int, Int) -> Float = { _, _ -> 1f },
    level: (Int, Int) -> Float,
  ): CIImage {
    val values = FloatArray(size.width * size.height * CHANNELS)
    for (y in 0 until size.height) {
      for (x in 0 until size.width) {
        val at = (y * size.width + x) * CHANNELS
        val opacity = alpha(x, y)
        val value = level(x, y) * opacity
        values[at] = value
        values[at + 1] = value
        values[at + 2] = value
        values[at + 3] = opacity
      }
    }
    val space = spaceOf(transfer)
    try {
      val data =
        values.usePinned { pinned ->
          NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
        }
      return CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (size.width * CHANNELS * Float.SIZE_BYTES).convert(),
        size = CGSizeMake(size.width.toDouble(), size.height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  // Every channel of every pixel of [image] as HLG codes, rows from the top.
  private fun hlgCodesOf(
    image: CIImage,
    size: Size,
  ): FloatArray {
    val values = FloatArray(size.width * size.height * CHANNELS)
    val space = CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG)
    try {
      values.usePinned { pinned ->
        renderingContext(floatFormat = true).render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (size.width * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return values
  }

  // An opaque frame whose red, green and blue HLG codes at each pixel are [codes].
  private fun hlgFrameOf(
    size: Size,
    codes: (Int, Int) -> List<Float>,
  ): CIImage {
    val values = FloatArray(size.width * size.height * CHANNELS)
    for (y in 0 until size.height) {
      for (x in 0 until size.width) {
        val at = (y * size.width + x) * CHANNELS
        codes(x, y).forEachIndexed { channel, code -> values[at + channel] = code }
        values[at + 3] = 1f
      }
    }
    val space = CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG)
    try {
      val data =
        values.usePinned { pinned ->
          NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
        }
      return CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (size.width * CHANNELS * Float.SIZE_BYTES).convert(),
        size = CGSizeMake(size.width.toDouble(), size.height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  private fun spaceOf(transfer: HdrTransfer?): CGColorSpaceRef? =
    if (transfer == null) {
      ColorSpace.Bt709.createCodeSpace()
    } else {
      CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020)
    }

  // A code on SDR, and on a grade the SDR signal the light at reference white one encodes to.
  private fun signalOf(
    value: Float,
    transfer: HdrTransfer?,
  ): Double = if (transfer == null) value.toDouble() else sdrSignalFromNits(value * HDR_REFERENCE_WHITE_NITS).toDouble()

  private fun frameInfo(
    size: Size,
    transfer: HdrTransfer?,
  ) = FrameInfo(attributes(size, transfer), Duration.ZERO)

  private fun attributes(
    size: Size,
    transfer: HdrTransfer?,
  ) = Attributes(
    inputSize = size,
    outputSize = size,
    layoutSize = size,
    colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
    hdrTransfer = transfer,
    frameRate = 30f,
    span = TimeRange.of(Duration.ZERO, 4.seconds),
  )

  // Premultiplied red and alpha, rows from the top.
  private class Pixels(
    val red: FloatArray,
    val alpha: FloatArray,
  )

  private companion object {
    // A sigma of 0.05 boxes this down by two, which divides it, and a sigma of 0.07 by three, which does not.
    const val WIDTH = 320
    const val HEIGHT = 180
    const val CHANNELS = 4
    const val COLORS = 3
    const val MID_SIGMA = 0.05f
    const val UNEVEN_SIGMA = 0.07f

    // The rows of clear bar above and below the picture in the frame with bars.
    const val BAR = 40
    const val FULL = 255.0

    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = true,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 8_192,
        features = emptySet(),
      )

    // Mid codes and, on a grade, light above white on one side and well below it on the other.
    const val SDR_LIGHT = 0.85f
    const val SDR_DARK = 0.15f
    const val SDR_GREY = 0.6f
    const val GRADE_LIGHT = 1.6f
    const val GRADE_DARK = 0.1f
    const val GRADE_GREY = 1.3f

    // Two colored HLG pixels, each channel a long way from the same channel on the other side.
    val HLG_WARM = listOf(0.75f, 0.45f, 0.2f)
    val HLG_COOL = listOf(0.2f, 0.3f, 0.75f)

    // Core Image's Gaussian against the plan's box, taps and linear read back up.
    const val BLUR_CODES = 2.0

    // A float render of a per pixel gain, so only the kernel's own rounding.
    const val VIGNETTE_CODES = 0.5

    // A blur of an edge 0.7 codes tall at a sigma of 16 pixels moves the columns beside it by far more than this.
    const val SHARP_SPREAD = 0.15

    // The centre, a point part way down the ramp, one near the radius on the diagonal and one near a corner.
    val VIGNETTE_POINTS = listOf(0.5 to 0.5, 0.15 to 0.5, 0.2 to 0.2, 0.03 to 0.05)
  }
}
