package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.pqSignalFromNits
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the Apple lowering of a colour matrix draws, read off the pixels.
 *
 * A flat frame is rendered twice, once untouched and once through the resolved step, and the second
 * reading is checked against the first pushed through [transform]. Nothing here carries a colour of
 * its own: whatever the untouched render came back with is what the matrix is asked to move, so the
 * two renders share whichever encoding Core Image put them in.
 *
 * The tolerance is a code value or two rather than an encoder's worth, since no encoder runs. Every
 * case works on the middle of the range, where an offset and a gain disagree.
 *
 * On a kept grade the frame is built and read back as float light in linear BT.2020, the primaries
 * an HDR frame arrives in and the ones the other backends mix in, and checked against
 * [transformNits] with reference white at one. The cases there sit above white on one channel and
 * below it on another, which is where the encoded reading and a bare linear one part company.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalFilmstripApi::class)
class CoreImageColorTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `a contrast draws what the shared matrix says`() {
    val context = renderingContext() ?: return

    assertDraws(Contrast(FIRMER), context)
  }

  @Test
  fun `a saturation draws what the shared matrix says`() {
    val context = renderingContext() ?: return

    assertDraws(Saturation(MUTED), context)
  }

  // A matrix that mixes the channels and carries a bias on each row, which is the arm the named
  // effects only ever reach one half of at a time.
  @Test
  fun `a hand-written matrix draws what the shared matrix says`() {
    val context = renderingContext() ?: return

    assertDraws(MIXED, context)
  }

  // The offset rides in the fourth component of each row vector rather than in the bias vector, so
  // it is multiplied by alpha. Core Image holds a frame premultiplied and a transparent pixel that
  // gained a colour here would reach the fill blend as a real one.
  @Test
  fun `a bias leaves a transparent pixel black`() {
    val context = renderingContext() ?: return

    val drawn = stepFor(Contrast(0f)).apply(CIImage.emptyImage().imageByCroppingToRect(RECT), FRAME)

    assertEquals(listOf(0, 0, 0, 0), read(drawn, context), "an offset reached a pixel with no alpha")
  }

  @Test
  fun `a contrast on a grade draws what the shared reading says in light`() {
    val context = renderingContext() ?: return

    assertDrawsOnGrade(Contrast(FIRMER), HdrTransfer.Pq, context)
    assertDrawsOnGrade(Contrast(FIRMER), HdrTransfer.Hlg, context)
  }

  @Test
  fun `a saturation on a grade draws what the shared reading says in light`() {
    val context = renderingContext() ?: return

    assertDrawsOnGrade(Saturation(MUTED), HdrTransfer.Pq, context)
    assertDrawsOnGrade(Saturation(MUTED), HdrTransfer.Hlg, context)
  }

  // A matrix that scales all three channels alike takes the cheap lowering, which multiplies the
  // light rather than moving the frame into the encoded signal and back. It has to land where the
  // shared reading says all the same, whichever type wrote it.
  @Test
  fun `a plain scale on a grade draws what the shared reading says in light`() {
    val context = renderingContext() ?: return

    assertDrawsOnGrade(Brightness(FIRMER), HdrTransfer.Pq, context)
    assertDrawsOnGrade(Brightness(FIRMER), HdrTransfer.Hlg, context)
    assertDrawsOnGrade(ColorMatrix(rr = MUTED, gg = MUTED, bb = MUTED), HdrTransfer.Pq, context)
  }

  @Test
  fun `a hand-written matrix on a grade draws what the shared reading says in light`() {
    val context = renderingContext() ?: return

    assertDrawsOnGrade(MIXED, HdrTransfer.Pq, context)
    assertDrawsOnGrade(MIXED, HdrTransfer.Hlg, context)
  }

  // A channel the matrix pushes past the format's peak lands on that peak, and one it pushes below
  // zero lands on black rather than on whatever a power of a negative comes to. HLG runs out first,
  // so it is the arm that shows the ceiling is the transfer's and not white.
  @Test
  fun `a grade clamps at the format's peak and at black`() {
    val context = renderingContext() ?: return

    val step = stepFor(Contrast(HARSH), HdrTransfer.Hlg)
    val drawn =
      readLight(step.apply(extremes(), frameOn(HdrTransfer.Hlg)), context, kCGColorSpaceExtendedLinearITUR_2020)

    val peak = HLG_NOMINAL_PEAK_NITS / HDR_REFERENCE_WHITE_NITS
    assertEquals(peak, drawn[0], peak * LIGHT_DRIFT, "red was not held at the format's peak: ${drawn.toList()}")
    assertEquals(0f, drawn[2], LIGHT_DRIFT, "blue was not floored at black: ${drawn.toList()}")
  }

  // What pins the reading above. A colour named in the PQ colour space at reference white comes
  // through the context at one, the HLG signal for the same light does too, and PQ's own peak lands
  // where dividing by reference white puts it, so the SDR signal is a bare power of what the frame
  // holds with no scale in front of it.
  @Test
  fun `reference white in either transfer reads as one`() {
    val context = renderingContext() ?: return

    val pq = readLight(grey(pqSignalFromNits(HDR_REFERENCE_WHITE_NITS), kCGColorSpaceITUR_2100_PQ), context)
    val hlgWhite = hlgSignalFromScene(hlgSceneFromDisplayNits(HDR_REFERENCE_WHITE_NITS))
    val hlg = readLight(grey(hlgWhite, kCGColorSpaceITUR_2100_HLG), context)
    val peak = readLight(grey(pqSignalFromNits(PQ_PEAK_NITS), kCGColorSpaceITUR_2100_PQ), context)

    repeat(CHANNELS) { channel ->
      assertEquals(1f, pq[channel], WHITE_DRIFT, "PQ reference white read ${pq.toList()}")
      assertEquals(1f, hlg[channel], WHITE_DRIFT, "HLG reference white read ${hlg.toList()}")
    }
    val expectedPeak = PQ_PEAK_NITS / HDR_REFERENCE_WHITE_NITS
    assertEquals(expectedPeak, peak[0], expectedPeak * WHITE_DRIFT, "PQ peak read ${peak.toList()}")
  }

  /**
   * Runs [spec]'s step over a flat frame and checks every channel landed where its matrix says.
   */
  private fun assertDraws(
    spec: EffectSpec,
    context: CIContext,
  ) {
    val plain = read(flat(), context)
    val drawn = read(stepFor(spec).apply(flat(), FRAME), context)

    val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} is not a colour matrix" }
    val moved = matrix.transform(plain[0] / FULL, plain[1] / FULL, plain[2] / FULL)
    val expected = List(CHANNELS) { channel -> (moved[channel] * FULL).roundToInt() }

    assertTrue(
      expected.indices.sumOf { abs(expected[it] - plain[it]) } > CHANNELS * DRIFT,
      "${spec.id} asks for $expected from a plain $plain, which is inside the tolerance",
    )
    assertTrue(
      expected.indices.all { abs(drawn[it] - expected[it]) <= DRIFT },
      "${spec.id} drew $drawn and the matrix asks for $expected from a plain $plain",
    )
  }

  /**
   * Runs [spec]'s step over a flat frame of light on a kept grade and checks every channel landed
   * where [transformNits] says, in units of reference white.
   */
  private fun assertDrawsOnGrade(
    spec: EffectSpec,
    transfer: HdrTransfer,
    context: CIContext,
  ) {
    val plain = readLight(lit(), context, kCGColorSpaceExtendedLinearITUR_2020)
    val drawn =
      readLight(stepFor(spec, transfer).apply(lit(), frameOn(transfer)), context, kCGColorSpaceExtendedLinearITUR_2020)

    val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} is not a colour matrix" }
    val moved =
      matrix.transformNits(
        plain[0] * HDR_REFERENCE_WHITE_NITS,
        plain[1] * HDR_REFERENCE_WHITE_NITS,
        plain[2] * HDR_REFERENCE_WHITE_NITS,
        transfer,
      )
    val expected = FloatArray(CHANNELS) { moved[it] / HDR_REFERENCE_WHITE_NITS }

    assertTrue(
      expected.indices.sumOf { abs(expected[it] - plain[it]).toDouble() } > CHANNELS * LIGHT_DRIFT,
      "${spec.id} asks for ${expected.toList()} from a plain ${plain.toList()}, which is inside the tolerance",
    )
    expected.indices.forEach { channel ->
      assertEquals(
        expected[channel],
        drawn[channel],
        maxOf(expected[channel], 1f) * LIGHT_DRIFT,
        "${spec.id} on $transfer drew ${drawn.toList()} and the reading asks for ${expected.toList()} " +
          "from ${plain.toList()}",
      )
    }
  }

  private fun stepFor(
    spec: EffectSpec,
    transfer: HdrTransfer? = null,
  ) = assertIs<EffectResolution.Resolved>(resolver.resolve(spec, CAPABILITIES, attributes(transfer))).effect.step

  private fun flat(): CIImage = CIImage(color = COLOR).imageByCroppingToRect(RECT)

  // Above white on red and green and below it on blue, all inside HLG's ceiling under every case
  // but the one that goes looking for it.
  private fun lit(): CIImage = light(LIT_RED, LIT_GREEN, LIT_BLUE)

  // Red well past where a harsh contrast can stay under the ceiling, blue low enough that the same
  // contrast takes it below zero.
  private fun extremes(): CIImage = light(EXTREME_RED, 1f, EXTREME_BLUE)

  private fun light(
    red: Float,
    green: Float,
    blue: Float,
  ): CIImage = flatIn(red, green, blue, kCGColorSpaceExtendedLinearITUR_2020)

  private fun grey(
    signal: Float,
    spaceName: CFStringRef?,
  ): CIImage = flatIn(signal, signal, signal, spaceName)

  private fun flatIn(
    red: Float,
    green: Float,
    blue: Float,
    spaceName: CFStringRef?,
  ): CIImage {
    val space = CGColorSpaceCreateWithName(spaceName)
    val color =
      checkNotNull(
        CIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha = 1.0, colorSpace = space),
      ) { "no colour in the $spaceName colour space" }
    CGColorSpaceRelease(space)

    return CIImage(color = color).imageByCroppingToRect(RECT)
  }

  private fun frameOn(transfer: HdrTransfer): FrameInfo = FrameInfo(attributes(transfer), Duration.ZERO)

  /**
   * A context to read pixels through, or null when this process cannot build one.
   */
  private fun renderingContext(): CIContext? =
    try {
      CIContext()
    } catch (absent: NullPointerException) {
      null
    }

  /**
   * The first pixel of [image], as red, green, blue and alpha in the range zero to 255.
   */
  private fun read(
    image: CIImage,
    context: CIContext,
  ): List<Int> =
    memScoped {
      val rowBytes = FRAME_SIZE.width * BYTES_PER_PIXEL
      val pixels = allocArray<UByteVar>(rowBytes * FRAME_SIZE.height)
      val space = CGColorSpaceCreateDeviceRGB()
      context.render(
        image = image,
        toBitmap = pixels,
        rowBytes = rowBytes.toLong(),
        bounds = RECT,
        format = kCIFormatRGBA8,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)

      List(BYTES_PER_PIXEL) { channel -> pixels[channel].toInt() }
    }

  /**
   * The first pixel of [image] as float red, green and blue, in the linear space named by
   * [spaceName] or in the context's working space when that is null. Either way a value of one is
   * reference white.
   */
  private fun readLight(
    image: CIImage,
    context: CIContext,
    spaceName: CFStringRef? = null,
  ): FloatArray =
    memScoped {
      val rowBytes = FRAME_SIZE.width * BYTES_PER_PIXEL * Float.SIZE_BYTES
      val pixels = allocArray<FloatVar>(FRAME_SIZE.width * FRAME_SIZE.height * BYTES_PER_PIXEL)
      val space = spaceName?.let { CGColorSpaceCreateWithName(it) }
      context.render(
        image = image,
        toBitmap = pixels,
        rowBytes = rowBytes.toLong(),
        bounds = RECT,
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)

      FloatArray(CHANNELS) { channel -> pixels[channel] }
    }

  private fun attributes(transfer: HdrTransfer?): Attributes =
    Attributes(
      inputSize = FRAME_SIZE,
      outputSize = FRAME_SIZE,
      layoutSize = FRAME_SIZE,
      colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = 30f,
      span = SPAN,
    )

  private companion object {
    val FRAME_SIZE = Size(16, 16)
    val RECT = CGRectMake(0.0, 0.0, FRAME_SIZE.width.toDouble(), FRAME_SIZE.height.toDouble())
    val SPAN = TimeRange.of(Duration.ZERO, 4.seconds)
    val FRAME =
      FrameInfo(
        Attributes(
          inputSize = FRAME_SIZE,
          outputSize = FRAME_SIZE,
          layoutSize = FRAME_SIZE,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = null,
          frameRate = 30f,
          span = SPAN,
        ),
        Duration.ZERO,
      )

    // Mid-range on all three channels with no two alike, so a matrix that mixes the channels is
    // told from one that scales them, and no channel runs out of range under any case here.
    val COLOR = CIColor.colorWithRed(0.4, 0.6, 0.8)

    val MIXED =
      ColorMatrix(
        rr = 0.6f,
        rg = 0.3f,
        rBias = 0.05f,
        gg = 0.7f,
        gb = 0.2f,
        gBias = -0.05f,
        br = 0.25f,
        bb = 0.5f,
        bBias = 0.1f,
      )

    const val FIRMER = 1.5f
    const val HARSH = 4f
    const val MUTED = 0.5f

    const val LIT_RED = 1.8f
    const val LIT_GREEN = 1.2f
    const val LIT_BLUE = 0.4f
    const val EXTREME_RED = 3f
    const val EXTREME_BLUE = 0.05f

    const val FULL = 255f
    const val CHANNELS = 3
    const val BYTES_PER_PIXEL = 4

    // Rounding through eight-bit output and back, twice over. Nothing encodes a frame here.
    const val DRIFT = 2

    // What a half float working format leaves on a value, as a fraction of it.
    const val LIGHT_DRIFT = 0.01f
    const val WHITE_DRIFT = 0.005f

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
  }
}
