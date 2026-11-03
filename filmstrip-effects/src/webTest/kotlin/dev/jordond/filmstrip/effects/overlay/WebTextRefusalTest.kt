@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The browser text a raster refuses to draw past the picture ceiling or past a texture's side, and how the resolver
 * reports it.
 */
class WebTextRefusalTest {
  @Test
  fun aRasterPastTheCeilingIsRefusedBeforeItsCanvasIsMade() {
    val style = TextStyle(color = GREY, backgroundColor = GREY)
    val size = drawnText("H", style, FRAME).size
    val texels = size.width.toLong() * size.height
    val copy = texels * FLOAT_TEXEL_BYTES
    val peak = picturePeakBytes(texels * TEXT_TEXEL_BYTES, copy, texels * FLOAT_TEXEL_BYTES).shouldNotBeNull()

    val refused =
      rasterizeText(
        "H",
        style,
        FRAME,
        MAX_SIDE,
        ceiling = peak - 1,
      ).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, peak - 1)

    val drawn =
      rasterizeText(
        "H",
        style,
        FRAME,
        MAX_SIDE,
        ceiling = peak,
      ).shouldBeInstanceOf<TextRaster.Drawn<LaidOutText>>()
    val samples = drawn.pixels.samples
    withClue("the samples hold more than their texels") { samples.size shouldBe size.width * size.height * CHANNELS }
  }

  @Test
  fun textPastThePictureCeilingIsRefusedWithTheReasonItsRasterGives() {
    val raster = rasterizeText(TALL_TEXT, TALL_STYLE, FRAME, MAX_SIDE).shouldBeInstanceOf<TextRaster.Refused>()

    val overlay = TextOverlay(TALL_TEXT, TALL_STYLE)
    val resolved = BuiltInEffectResolver().resolve(overlay, TEXT_CAPABILITIES, TEXT_ATTRIBUTES)

    val refused =
      withClue("the tall text resolved as $resolved") {
        resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe raster.reason
    ("too large to draw" in refused.message && "$PICTURE_PEAK_CEILING bytes" in refused.message) shouldBe true
  }

  @Test
  fun coverageAsLongAsATextureSideIsDrawnAndOnePastIsRefused() {
    for ((width, height) in listOf(MAX_SIDE to 1, 1 to MAX_SIDE)) {
      val drawn = textCoverage(width.toLong(), height.toLong(), GREY, MAX_SIDE) { _, _ -> }
      withClue("$width by $height") { drawn.shouldBeInstanceOf<TextRaster.Drawn<ByteArray>>() }
    }
    for ((width, height) in listOf(MAX_SIDE + 1 to 1, 1 to MAX_SIDE + 1)) {
      val past = textCoverage(width.toLong(), height.toLong(), GREY, MAX_SIDE) { _, _ -> }
      val refused = withClue("$width by $height") { past.shouldBeInstanceOf<TextRaster.Refused>() }
      refused.reason shouldBe textPastTextureSide(width, height, MAX_SIDE, WEBGL)
    }
  }

  @Test
  fun textPastTheTextureSideResolvesUnsupportedAndAtItResolves() {
    val style = TextStyle(color = GREY)
    val size = drawnText("H", style, FRAME).size
    val longest = maxOf(size.width, size.height)
    val overlay = TextOverlay("H", style)

    val fits = BuiltInEffectResolver().resolve(overlay, textCapabilities(longest), TEXT_ATTRIBUTES)
    withClue("the text resolved as $fits at a $longest pixel side") {
      fits.shouldBeInstanceOf<EffectResolution.Resolved>()
    }

    val past = BuiltInEffectResolver().resolve(overlay, textCapabilities(longest - 1), TEXT_ATTRIBUTES)
    val refused =
      withClue("the text resolved as $past past a ${longest - 1} side") {
        past.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe textPastTextureSide(size.width, size.height, longest - 1, WEBGL)
  }

  // The plate's padding saturates at Int.MAX_VALUE here, so its sides are counted past what an Int holds.
  @Test
  fun aHugeFontOnAPlateIsRefusedRatherThanDrawnOrThrown() {
    val style = TextStyle(fontSize = HUGE_FONT, backgroundColor = GREY)
    style.platePaddingOn(FRAME) shouldBe Int.MAX_VALUE

    val measured = withClue("the huge text measured nothing") { measuredText("H", style, FRAME).shouldNotBeNull() }
    val padding = style.platePaddingOn(FRAME)

    val refused = rasterizeText("H", style, FRAME, MAX_SIDE).shouldBeInstanceOf<TextRaster.Refused>()
    val (wide, tall) = plateSide(measured.width, padding) to plateSide(measured.height, padding)
    refused.reason shouldBe textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES)

    val resolved = BuiltInEffectResolver().resolve(TextOverlay("H", style), TEXT_CAPABILITIES, TEXT_ATTRIBUTES)
    resolved.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe refused.reason
  }

  @Test
  fun aTileCanvasWithNoContextRefusesTheWholeRaster() {
    val (width, height) = SMALL_TILE * 3 to SMALL_TILE * 2
    val canvases = mutableListOf<OffscreenCanvas>()
    // A canvas that has handed out another kind of context gives no 2D one, which the second tile's does.
    val refusing = { tileWidth: Int, tileHeight: Int ->
      val canvas = OffscreenCanvas(tileWidth, tileHeight)
      if (canvases.isNotEmpty()) canvas.getContext(BITMAP_RENDERER)
      canvases += canvas
      canvas
    }
    val (wide, tall) = width.toLong() to height.toLong()
    val drawn = textCoverage(wide, tall, GREY, MAX_SIDE, tileSide = SMALL_TILE, tileCanvas = refusing) { _, _ -> }

    val refused = drawn.shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textAllocationFailed(width, height)
    withClue("the coverage asked for ${canvases.size} tiles") { canvases.size shouldBe 2 }
    withClue("a tile canvas still holds pixels") { canvases.all { it.width == 0 && it.height == 0 } shouldBe true }
  }

  @Test
  fun anArrayTheBrowserCannotAllocateReadsAsNone() {
    allocatedOrNull { Int8Array(-1) }.shouldBeNull()
    allocatedOrNull { ByteArray(CHANNELS) }?.size shouldBe CHANNELS
  }

  @Test
  fun anythingElseThrownWhileAllocatingIsThrownOn() {
    assertFailsWith<IllegalStateException> { allocatedOrNull { error("not an allocation") } }
  }

  private companion object {
    val FRAME = Size(1280, 720)
    val MAX_SIDE = TEXT_CAPABILITIES.maxTextureSize
    const val GREY = 0xFF808080.toInt()

    // Half the frame tall a capital and wrapped narrow, a hundred words stand one letter to a line many frames tall.
    val TALL_STYLE = TextStyle(fontSize = 0.5f, maxWidth = 0.1f)
    val TALL_TEXT = List(100) { "word" }.joinToString(" ")

    const val HUGE_FONT = 1e7f
    const val SMALL_TILE = 5
    const val BITMAP_RENDERER = "bitmaprenderer"

    const val CHANNELS = 4
  }
}

// A JavaScript typed array, which throws a RangeError for a length it cannot allocate.
private external class Int8Array(
  length: Int,
) : JsAny
