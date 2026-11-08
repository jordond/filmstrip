package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.picturePeakBytes
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.greySignal
import dev.jordond.filmstrip.test.sampleOfCode
import dev.jordond.filmstrip.test.straightSampleTolerance
import dev.jordond.filmstrip.test.straightSignalTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How Android's text raster stores a translucent plate and translucent text.
 *
 * The raster lays the plate and the text's straight colors into sixteen-bit samples by the glyphs' coverage, so every
 * plate texel is read against the [straightSampleTolerance] bound at color equal to its alpha and at color apart
 * from it. Translucent text is read against the shared blend, [platedSamples] of the coverage the same text draws at
 * full alpha in its own color.
 */
class TextPlateRasterTest {
  @Test
  fun aTranslucentPlateStoresItsAuthoredStraightColor() {
    for (plate in PLAN_PLATES) {
      val corner = texel(drawnText(TextStyle(color = TEXT, backgroundColor = plate)).samples, 0)
      val alpha = codeOf(plate, ALPHA_SHIFT)

      val authored = channelsOf(plate)
      // The alpha is stored as it is, the way an opaque texel's color is, so it never divides by itself.
      val tolerances =
        authored.indices.map { channel ->
          straightSampleTolerance(if (channel == ALPHA) OPAQUE else alpha)
        }
      withClue("the ${plate.hex()} plate's corner stored $corner and was authored as $authored, within $tolerances") {
        corner.indices.all { abs(corner[it] - authored[it]) <= tolerances[it] } shouldBe true
      }
    }
  }

  @Test
  fun partlyCoveredTextKeepsItsStraightColorOverAPlateAndOverNone() {
    val solid = drawnText(TextStyle(fontSize = BIG, color = TEXT))
    val covered = (0 until solid.size.width * solid.size.height).filter { coverageAt(solid, it) in 1 until OPAQUE }
    withClue("no texel of the text is partly covered") { covered.isNotEmpty() shouldBe true }

    for (textAlpha in TEXT_ALPHAS) {
      for (plate in listOf(PLATE, null)) {
        val style = TextStyle(fontSize = BIG, color = withAlpha(TEXT, textAlpha), backgroundColor = plate)
        val padding = style.platePaddingOn(FRAME)
        val drawn = drawnText(style)
        withClue("the plate moved the glyphs") { drawn.size.width shouldBe solid.size.width + 2 * padding }

        val expected = platedSamples(glyphBytes(solid, TEXT, padding), withAlpha(TEXT, textAlpha), plate)
        withClue("${textAlpha.toString(16)} text over ${plate?.hex()}") { drawn.samples shouldBe expected }
      }
    }
  }

  // Android shapes the coverage of a glyph it draws from a mask by the color it is drawn in, so text is covered in its
  // own color, apart from white's, and laid at its alpha by that coverage. A device that stops keying the mask skips
  // here, and that skip is what to look for.
  @Test
  fun translucentTextIsCoveredInItsOwnColor() {
    val white = alphasOf(drawnText(TextStyle(fontSize = SMALL, color = WHITE), WORDS))

    for (ink in INKS) {
      val solid = drawnText(TextStyle(fontSize = SMALL, color = ink), WORDS)
      assumeTrue("${ink.hex()} text is covered apart from white text", white != alphasOf(solid))

      val half = drawnText(TextStyle(fontSize = SMALL, color = withAlpha(ink, HALF)), WORDS)
      val expected = platedSamples(glyphBytes(solid, ink), withAlpha(ink, HALF), plate = null)
      withClue("${ink.hex()} text at half alpha") { half.samples shouldBe expected }
    }
  }

  @Test
  fun aTranslucentPlateKeepsItsColorsSignalOnAnHdrFrame() {
    for (plate in PLAN_PLATES) {
      val code = codeOf(plate, RED_SHIFT)
      val alpha = codeOf(plate, ALPHA_SHIFT)
      val stored = texel(drawnText(TextStyle(color = TEXT, backgroundColor = plate)).samples, 0)[0].toFloat()

      HdrTransfer.entries.forEach { transfer ->
        val authored = greySignal(sampleOfCode(code).toFloat(), transfer)
        val read = greySignal(stored, transfer)
        val moved = (0 until 3).maxOf { abs(read[it] - authored[it]) }
        val bound = straightSignalTolerance(alpha, code, transfer)

        withClue("the $transfer ${plate.hex()} plate stored $stored and moved $moved past $bound") {
          (moved <= bound) shouldBe true
        }
      }
    }
  }

  // The codes media3 draws a plate from on an HDR frame are the ones the shared mapping gives its authored color.
  @Test
  fun aTranslucentPlateReachesMedia3AtItsAuthoredColorsHdrCodes() {
    for (plate in PLAN_PLATES) {
      val plated = drawnText(TextStyle(color = TEXT, backgroundColor = plate))
      HdrTransfer.entries.forEach { transfer ->
        val expected = media3HdrTexels(intArrayOf(plate), transfer)
        val drawn = plated.rasterFor(transfer).shouldBeInstanceOf<TextRaster.Drawn<Raster>>().pixels
        val corner = drawn.bitmap.reading { it.getPixel(0, 0) }

        withClue("the $transfer ${plate.hex()} plate") { corner.hex() shouldBe expected.argb[0].hex() }
        withClue("the $transfer ${plate.hex()} plate") { drawn.hdrLuminance shouldBe expected.hdrLuminance }
      }
    }
  }

  @Test
  fun aRasterPastTheCeilingIsRefusedBeforeItsBitmapIsMade() {
    val style = TextStyle(color = TEXT, backgroundColor = PLATE)
    val size = drawnText(style).size
    val texels = size.width.toLong() * size.height
    val band = textBandBytes(size.width.toLong(), size.height.toLong())
    val peak = picturePeakBytes(texels * TEXT_TEXEL_BYTES, band, texels * FLOAT_TEXEL_BYTES).shouldNotBeNull()
    var made = false
    val counted = { width: Int, height: Int ->
      made = true
      textBitmap(width, height)
    }

    val refused =
      rasterizeText(
        "H",
        style,
        FRAME,
        ANY_SIDE,
        ceiling = peak - 1,
        bitmapOf = counted,
      ).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, peak - 1)
    withClue("the bitmap was made before the raster was refused") { made shouldBe false }

    val drawn = rasterizeText("H", style, FRAME, ANY_SIDE, ceiling = peak, bitmapOf = counted)
    val samples = drawn.shouldBeInstanceOf<TextRaster.Drawn<TextSamples>>().pixels.samples
    withClue("the samples were miscounted") {
      samples.size.toLong() * Short.SIZE_BYTES shouldBe texels * TEXT_TEXEL_BYTES
    }
  }

  // Text that fits under the picture ceiling but not under its share of the Java heap is refused at that share,
  // Android's own limit, whatever heap the device gives the app.
  @Test
  fun textPastItsShareOfTheHeapButUnderThePictureCeilingIsRefusedAtTheShare() {
    val ceiling = textCeiling(SMALL_HEAP)
    val line = measured("H")
    val lines = (ceiling / (PEAK_TEXEL_BYTES * line.width * line.height) + 2).toInt()
    val text = List(lines) { "H" }.joinToString("\n")
    val size = measured(text)
    val peak = size.width.toLong() * size.height * PEAK_TEXEL_BYTES
    withClue("$lines lines hold $peak bytes against $ceiling") {
      (peak in ceiling + 1..PICTURE_PEAK_CEILING) shouldBe true
    }

    val drawn = rasterizeText(text, TextStyle(), FRAME, ANY_SIDE, ceiling = ceiling, bitmapOf = unmade)
    val refused = drawn.shouldBeInstanceOf<TextRaster.Refused>()

    refused.reason shouldBe textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, ceiling)
  }

  // Android ends the allocation with an error rather than a null, which refuses the text by its own reason.
  @Test
  fun aBitmapAndroidCannotAllocateRefusesTheTextByName() {
    val style = TextStyle(color = TEXT, backgroundColor = PLATE)
    val size = drawnText(style).size
    var asked = false
    val exhausted = { _: Int, _: Int ->
      asked = true
      throw OutOfMemoryError("the heap is spent")
    }

    val refused =
      rasterizeText(
        "H",
        style,
        FRAME,
        ANY_SIDE,
        bitmapOf = exhausted,
      ).shouldBeInstanceOf<TextRaster.Refused>()

    withClue("the text was refused before its bitmap was asked for") { asked shouldBe true }
    refused.reason shouldBe textAllocationFailed(size.width, size.height)
  }

  @Test
  fun theCoverageBitmapIsRecycledOnceItIsRead() {
    var made: Bitmap? = null
    val kept = { width: Int, height: Int -> textBitmap(width, height).also { made = it } }

    val drawn = rasterizeText("H", TextStyle(color = TEXT), FRAME, ANY_SIDE, bitmapOf = kept)

    drawn.shouldBeInstanceOf<TextRaster.Drawn<TextSamples>>()

    withClue("the bitmap outlived its read") {
      withClue("the bitmap was never made") { made.shouldNotBeNull() }.isRecycled shouldBe true
    }
  }

  @Test
  fun aBitmapTheTextFailsToDrawIntoIsRecycled() {
    var made: Bitmap? = null
    // A canvas refuses an immutable bitmap, so drawing the text into one throws.
    val immutable = { width: Int, height: Int ->
      val drawable = textBitmap(width, height)
      drawable.copy(Bitmap.Config.ARGB_8888, false).also {
        drawable.recycle()
        made = it
      }
    }

    assertFailsWith<IllegalStateException> {
      rasterizeText("H", TextStyle(color = TEXT, backgroundColor = PLATE), FRAME, ANY_SIDE, bitmapOf = immutable)
    }

    withClue("the bitmap outlived the failed draw") {
      withClue("the bitmap was never made") { made.shouldNotBeNull() }.isRecycled shouldBe true
    }
  }

  // Thousands of lines lay out to a raster many times the ceiling, which the resolver refuses by name rather than try
  // to allocate.
  @Test
  fun textPastTheCeilingResolvesUnsupportedByName() {
    val style = TextStyle(backgroundColor = PLATE)
    val size = measured(TALL_TEXT, style)

    val resolved = BuiltInEffectResolver().resolve(TextOverlay(TALL_TEXT, style), CAPABILITIES, PREVIEW)

    val refused = resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.message shouldBe
      textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, textCeiling())
  }

  // Preview and export resolve text through the same resolver, so the bitmap media3 is handed here is the one a
  // preview frame draws. It holds straight eight-bit codes, and each lands on the plate's own code.
  @Test
  fun aPreviewDrawsATranslucentPlateAtItsAuthoredCodes() {
    for (plate in PLAN_PLATES) {
      val style = TextStyle(color = TEXT, backgroundColor = plate)

      val resolved = BuiltInEffectResolver().resolve(TextOverlay("H", style), CAPABILITIES, PREVIEW)

      val handle = resolved.shouldBeInstanceOf<EffectResolution.Resolved>().effect.handle
      val overlay = handle.shouldBeInstanceOf<Media3Handle.Overlay>().newOverlay().shouldBeInstanceOf<RasterOverlay>()
      val corner = overlay.getBitmap(0).reading { it.getPixel(0, 0) }
      withClue("the ${plate.hex()} plate drew ${corner.hex()}") { channelsOf(corner) shouldBe channelsOf(plate) }
    }
  }

  private class Unmade : RuntimeException()

  private val unmade = { _: Int, _: Int -> throw Unmade() }

  // The size text lays out to, read off the bitmap it asks for, which is never made.
  private fun measured(
    text: String,
    style: TextStyle = TextStyle(),
  ): Size {
    var asked: Size? = null
    val measure = { width: Int, height: Int ->
      asked = Size(width, height)
      throw Unmade()
    }
    assertFailsWith<Unmade> {
      rasterizeText(text, style, FRAME, ANY_SIDE, ceiling = Long.MAX_VALUE, bitmapOf = measure)
    }
    return withClue("the text laid out to nothing") { asked.shouldNotBeNull() }
  }

  // Glyph bytes in the ink's own codes at the coverage solid text drew, laid padding texels in from every edge.
  // Coverage is drawn at full alpha whatever the text's alpha, so these are the bytes the same text draws at any alpha.
  private fun glyphBytes(
    solid: TextSamples,
    ink: Int,
    padding: Int = 0,
  ): ByteArray {
    val width = solid.size.width + 2 * padding
    val bytes = ByteArray(width * (solid.size.height + 2 * padding) * CHANNELS)
    for (index in 0 until solid.size.width * solid.size.height) {
      val at = ((index / solid.size.width + padding) * width + index % solid.size.width + padding) * CHANNELS
      SHIFTS.take(ALPHA).forEachIndexed { channel, shift -> bytes[at + channel] = codeOf(ink, shift).toByte() }
      bytes[at + ALPHA] = coverageAt(solid, index).toByte()
    }
    return bytes
  }

  private fun drawnText(
    style: TextStyle,
    text: String = "H",
  ): TextSamples =
    withClue("the text drew nothing") {
      rasterizeText(text, style, FRAME, ANY_SIDE).shouldBeInstanceOf<TextRaster.Drawn<TextSamples>>()
    }.pixels

  // How much of a texel of text drawn at full alpha over no plate the glyphs cover, as an eight-bit code.
  private fun coverageAt(
    solid: TextSamples,
    index: Int,
  ): Int {
    val alpha = texel(solid.samples, index)[ALPHA]
    val top = sampleOfCode(OPAQUE)
    check(alpha * OPAQUE % top == 0) { "texel $index stores alpha $alpha, not a widened code" }
    return alpha * OPAQUE / top
  }

  private fun alphasOf(text: TextSamples): List<Int> =
    (0 until text.size.width * text.size.height).map { texel(text.samples, it)[ALPHA] }

  private fun texel(
    samples: ShortArray,
    index: Int,
  ): List<Int> = List(CHANNELS) { samples[index * CHANNELS + it].toUShort().toInt() }

  private fun channelsOf(argb: Int): List<Int> = SHIFTS.map { sampleOfCode(codeOf(argb, it)) }

  private fun codeOf(
    argb: Int,
    shift: Int,
  ): Int = (argb ushr shift) and BYTE

  private fun withAlpha(
    argb: Int,
    alpha: Int,
  ): Int = (argb and RGB_MASK) or (alpha shl ALPHA_SHIFT)

  private fun Int.hex(): String = "0x%08X".format(this)

  private companion object {
    val FRAME = Size(1280, 720)

    // Color equal to alpha, and color apart from it so a swapped channel reads off.
    val PLAN_PLATES = listOf(0x80808080.toInt(), 0x40C8C8C8)
    val PLATE = PLAN_PLATES.last()

    // Text alphas apart from each other and from the ends, so a dropped text alpha moves the blend.
    val TEXT_ALPHAS = listOf(0xFF, 0x80, 0x40)
    val SHIFTS = listOf(16, 8, 0, 24)

    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 8_192,
        features = setOf(RenderFeature.TextRendering),
      )

    val PREVIEW =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )

    val TALL_TEXT = "Filmstrip ".repeat(20_000)

    // A 64 MiB heap, small enough that the text past its share lays out quickly on any device.
    const val SMALL_HEAP = 64L shl 20

    // A texture side no raster here reaches, so only what a test is about refuses it.
    const val ANY_SIDE = Int.MAX_VALUE

    // What textRasterFits counts a texel at, the samples, the band and the floats.
    val PEAK_TEXEL_BYTES = TEXT_TEXEL_BYTES + FLOAT_TEXEL_BYTES + checkNotNull(textBandBytes(1, 1))

    // Black, which a mask keyed to white covers least, and a mid color apart in every channel. Both cover apart from
    // white on API 30 and 36.
    val INKS = listOf(0xFF000000.toInt(), 0xFF3060C0.toInt())

    const val WHITE = 0xFFFFFFFF.toInt()
    const val HALF = 0x80
    const val TEXT = 0xFFC8C8C8.toInt()
    const val BIG = 0.3f

    // Small enough that Android draws the glyphs from masks, where a capital at BIG is drawn from its outline.
    const val SMALL = 0.04f
    const val WORDS = "Hamburgefonts"
    const val CHANNELS = 4
    const val ALPHA = 3
    const val OPAQUE = 255
    const val BYTE = 0xFF
    const val RGB_MASK = 0x00FFFFFF
    const val RED_SHIFT = 16
    const val ALPHA_SHIFT = 24
  }
}

private inline fun <T> Bitmap.reading(read: (Bitmap) -> T): T =
  try {
    read(this)
  } finally {
    recycle()
  }
