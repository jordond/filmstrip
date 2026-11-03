package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.greySignal
import dev.jordond.filmstrip.test.sampleOfCode
import dev.jordond.filmstrip.test.straightSampleTolerance
import dev.jordond.filmstrip.test.straightSignalTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test

/**
 * How the browser's text raster stores a translucent plate.
 *
 * The raster lays the plate's straight color into sixteen-bit samples, so every plate is read against the
 * [straightSampleTolerance] bound at color equal to its alpha and at color apart from it. An eight-bit canvas, the
 * store the raster drew to before, is read the same way and falls outside it at color apart from its alpha.
 */
@OptIn(ExperimentalWasmJsInterop::class)
class WebTextPlateTest {
  @Test
  fun aTranslucentPlateStoresItsAuthoredStraightColor() {
    for (plate in PLAN_PLATES) assertStoredStraight(plate)
  }

  @Test
  fun greyPlatesAcrossTheRangeStoreTheirAuthoredStraightColor() {
    val alphas = SWEEP_STEP..BYTE step SWEEP_STEP
    val plates = (0..BYTE step SWEEP_STEP).flatMap { code -> alphas.map { greyAt(code, it) } }

    for (plate in plates + PLAN_PLATES) assertStoredStraight(plate)
  }

  @Test
  fun aTranslucentPlateKeepsItsColorsSignalOnAnHdrFrame() {
    for (plate in PLAN_PLATES) {
      val raster = drawnText("H", TextStyle(color = GREY, backgroundColor = plate), FRAME)
      val code = codeOf(plate, RED_SHIFT)
      val alpha = codeOf(plate, ALPHA_SHIFT)
      val stored = (raster.samples[0].toInt() and SAMPLE_MASK).toFloat()

      HdrTransfer.entries.forEach { transfer ->
        val moved = signalMoved(code, stored, transfer)
        val bound = straightSignalTolerance(alpha, code, transfer)

        withClue("the $transfer ${plate.hex()} plate stored $stored and moved $moved past $bound") {
          (moved <= bound) shouldBe true
        }
      }
    }
  }

  // The canvas stores eight-bit codes premultiplied and reads them back divided out, which is what the raster drew to
  // before it laid the plate itself.
  @Test
  fun anEightBitCanvasPlateFallsOutsideTheSixteenBitBound() {
    for (plate in EIGHT_BIT_MISSES) {
      val read = eightBitCanvasRead(plate)
      val authored = sampleOfCode(codeOf(plate, RED_SHIFT))
      val bound = straightSampleTolerance(codeOf(plate, ALPHA_SHIFT))

      withClue("the ${plate.hex()} plate read $read on eight bits, inside $bound") {
        (abs(read - authored) > bound) shouldBe true
      }
    }
  }

  @Test
  fun anEightBitCanvasPlateFallsOutsideTheHdrSignalBound() {
    for (plate in EIGHT_BIT_MISSES) {
      val code = codeOf(plate, RED_SHIFT)
      val read = eightBitCanvasRead(plate).toFloat()

      HdrTransfer.entries.forEach { transfer ->
        val moved = signalMoved(code, read, transfer)
        val bound = straightSignalTolerance(codeOf(plate, ALPHA_SHIFT), code, transfer)

        withClue("the $transfer ${plate.hex()} plate moved $moved on eight bits, inside $bound") {
          (moved > bound) shouldBe true
        }
      }
    }
  }

  @Test
  fun translucentTextIsDrawnAtItsAlphaWhereItsGlyphsCoverWholly() {
    val raster = drawnText("H", TextStyle(fontSize = BIG, color = withAlpha(GREY, HALF)), FRAME)

    val alphas = (raster.samples.indices step CHANNELS).map { raster.samples[it + ALPHA].toInt() and SAMPLE_MASK }
    withClue("the most covered texel of half alpha text") { alphas.max() shouldBe sampleOfCode(HALF) }
  }

  @Test
  fun theResolvedOverlayCarriesTheRastersSamplesAndNoImage() {
    val style = TextStyle(color = GREY, backgroundColor = PLAN_PLATES.last())
    val raster = drawnText("H", style, FRAME)

    val resolved = BuiltInEffectResolver().resolve(TextOverlay("H", style), TEXT_CAPABILITIES, TEXT_ATTRIBUTES)

    val overlay = (resolved.shouldBeInstanceOf<EffectResolution.Resolved>().effect.overlay).shouldNotBeNull()
    overlay.size shouldBe raster.size
    overlay.source.shouldBeInstanceOf<OverlaySource.Samples>().samples shouldBe raster.samples
  }

  // The color may land one sample over its alpha off, and the alpha is laid exactly so it lands within one sample.
  private fun assertStoredStraight(plate: Int) {
    val raster = drawnText("H", TextStyle(color = GREY, backgroundColor = plate), FRAME)
    val alpha = codeOf(plate, ALPHA_SHIFT)

    val corner = texel(raster.samples, 0)
    val authored = channelsOf(plate)
    val tolerances =
      authored.indices.map { channel ->
        straightSampleTolerance(if (channel == ALPHA) OPAQUE else alpha)
      }
    withClue("the ${plate.hex()} plate's corner stored $corner and was authored as $authored, within $tolerances") {
      corner.indices.all { abs(corner[it] - authored[it]) <= tolerances[it] } shouldBe true
    }
  }

  private fun signalMoved(
    code: Int,
    stored: Float,
    transfer: HdrTransfer,
  ): Float {
    val authored = greySignal(sampleOfCode(code).toFloat(), transfer)
    val read = greySignal(stored, transfer)
    return (0 until 3).maxOf { abs(read[it] - authored[it]) }
  }

  // The red of a grey plate filled on an eight-bit canvas and read back, widened to its sample.
  private fun eightBitCanvasRead(plate: Int): Int {
    val context = OffscreenCanvas(1, 1).getContext(CONTEXT_2D).shouldNotBeNull()
    val code = codeOf(plate, RED_SHIFT)
    context.fillStyle = "rgba($code, $code, $code, ${codeOf(plate, ALPHA_SHIFT) / FULL})"
    context.fillRect(0.0, 0.0, 1.0, 1.0)
    val read =
      context
        .getImageData(0, 0, 1, 1)
        .data.buffer
        .toByteArray()
    return sampleOfCode(read[0].toInt() and BYTE)
  }

  private fun texel(
    samples: ShortArray,
    index: Int,
  ): List<Int> = List(CHANNELS) { samples[index * CHANNELS + it].toInt() and SAMPLE_MASK }

  private fun channelsOf(argb: Int): List<Int> = SHIFTS.map { sampleOfCode(codeOf(argb, it)) }

  private fun codeOf(
    argb: Int,
    shift: Int,
  ): Int = (argb ushr shift) and BYTE

  private fun greyAt(
    code: Int,
    alpha: Int,
  ): Int = (alpha shl ALPHA_SHIFT) or (code shl RED_SHIFT) or (code shl GREEN_SHIFT) or code

  private fun withAlpha(
    argb: Int,
    alpha: Int,
  ): Int = (alpha shl ALPHA_SHIFT) or (argb and RGB_MASK)

  private fun Int.hex(): String = toUInt().toString(16)

  private companion object {
    val FRAME = Size(1280, 720)

    const val GREY = 0xFF808080.toInt()

    // Color equal to alpha, and color apart from it so a swapped channel reads off.
    val PLAN_PLATES = listOf(0x80808080.toInt(), 0x40C8C8C8)

    // Chrome's eight-bit canvas stores color 0x80 at alpha 0x80 as 64 and divides it back to 0x80 exactly, so only the
    // color apart from its alpha tells that store from a sixteen-bit one.
    val EIGHT_BIT_MISSES = listOf(0x40C8C8C8)

    // Every seventeenth code, so the sweep takes sixteen colors from 0 to 255 and fifteen alphas from 17 to 255. An
    // alpha of 0 leaves no color to store.
    const val SWEEP_STEP = 17

    const val HALF = 0x80

    // A capital stems many texels wide, so some texels are wholly covered.
    const val BIG = 0.3f

    const val CHANNELS = 4
    const val ALPHA = 3
    const val OPAQUE = 0xFF
    const val BYTE = 0xFF
    const val FULL = 255f
    const val RGB_MASK = 0xFFFFFF
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val ALPHA_SHIFT = 24
    val SHIFTS = listOf(RED_SHIFT, GREEN_SHIFT, 0, ALPHA_SHIFT)
  }
}
