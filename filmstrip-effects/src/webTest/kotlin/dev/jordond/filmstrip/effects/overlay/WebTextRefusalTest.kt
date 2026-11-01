@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.style.TextStyle
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The browser text a raster refuses to draw past the picture ceiling or past a texture's side, and how the resolver
 * reports it.
 */
@OptIn(InternalFilmstripApi::class)
class WebTextRefusalTest {
  @Test
  fun aRasterPastTheCeilingIsRefusedBeforeItsCanvasIsMade() {
    val style = TextStyle(color = GREY, backgroundColor = GREY)
    val size = drawnText("H", style, FRAME).size
    val texels = size.width.toLong() * size.height
    val copy = texels * FLOAT_TEXEL_BYTES
    val peak = assertNotNull(picturePeakBytes(texels * TEXT_TEXEL_BYTES, copy, texels * FLOAT_TEXEL_BYTES))

    val refused = assertIs<TextRaster.Refused>(rasterizeText("H", style, FRAME, MAX_SIDE, ceiling = peak - 1))
    assertEquals(
      textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, peak - 1),
      refused.reason,
    )

    val drawn = assertIs<TextRaster.Drawn<LaidOutText>>(rasterizeText("H", style, FRAME, MAX_SIDE, ceiling = peak))
    val samples = drawn.pixels.samples
    assertEquals(size.width * size.height * CHANNELS, samples.size, "the samples hold more than their texels")
  }

  @Test
  fun textPastThePictureCeilingIsRefusedWithTheReasonItsRasterGives() {
    val raster = assertIs<TextRaster.Refused>(rasterizeText(TALL_TEXT, TALL_STYLE, FRAME, MAX_SIDE))

    val overlay = TextOverlay(TALL_TEXT, TALL_STYLE)
    val resolved = BuiltInEffectResolver().resolve(overlay, TEXT_CAPABILITIES, TEXT_ATTRIBUTES)

    val refused = assertIs<EffectResolution.Unsupported>(resolved, "the tall text resolved as $resolved")
    assertEquals(raster.reason, refused.message)
    assertTrue("too large to draw" in refused.message && "$PICTURE_PEAK_CEILING bytes" in refused.message)
  }

  @Test
  fun coverageAsLongAsATextureSideIsDrawnAndOnePastIsRefused() {
    for ((width, height) in listOf(MAX_SIDE to 1, 1 to MAX_SIDE)) {
      val drawn = textCoverage(width.toLong(), height.toLong(), GREY, MAX_SIDE) { _, _ -> }
      assertIs<TextRaster.Drawn<ByteArray>>(drawn, "$width by $height")
    }
    for ((width, height) in listOf(MAX_SIDE + 1 to 1, 1 to MAX_SIDE + 1)) {
      val past = textCoverage(width.toLong(), height.toLong(), GREY, MAX_SIDE) { _, _ -> }
      val refused = assertIs<TextRaster.Refused>(past, "$width by $height")
      assertEquals(textPastTextureSide(width, height, MAX_SIDE, WEBGL), refused.reason)
    }
  }

  @Test
  fun textPastTheTextureSideResolvesUnsupportedAndAtItResolves() {
    val style = TextStyle(color = GREY)
    val size = drawnText("H", style, FRAME).size
    val longest = maxOf(size.width, size.height)
    val overlay = TextOverlay("H", style)

    val fits = BuiltInEffectResolver().resolve(overlay, textCapabilities(longest), TEXT_ATTRIBUTES)
    assertIs<EffectResolution.Resolved>(fits, "the text resolved as $fits at a $longest pixel side")

    val past = BuiltInEffectResolver().resolve(overlay, textCapabilities(longest - 1), TEXT_ATTRIBUTES)
    val refused = assertIs<EffectResolution.Unsupported>(past, "the text resolved as $past past a ${longest - 1} side")
    assertEquals(textPastTextureSide(size.width, size.height, longest - 1, WEBGL), refused.message)
  }

  // The plate's padding saturates at Int.MAX_VALUE here, so its sides are counted past what an Int holds.
  @Test
  fun aHugeFontOnAPlateIsRefusedRatherThanDrawnOrThrown() {
    val style = TextStyle(fontSize = HUGE_FONT, backgroundColor = GREY)
    assertEquals(Int.MAX_VALUE, style.platePaddingOn(FRAME))

    val measured = assertNotNull(measuredText("H", style, FRAME), "the huge text measured nothing")
    val padding = style.platePaddingOn(FRAME)

    val refused = assertIs<TextRaster.Refused>(rasterizeText("H", style, FRAME, MAX_SIDE))
    val (wide, tall) = plateSide(measured.width, padding) to plateSide(measured.height, padding)
    assertEquals(textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES), refused.reason)

    val resolved = BuiltInEffectResolver().resolve(TextOverlay("H", style), TEXT_CAPABILITIES, TEXT_ATTRIBUTES)
    assertEquals(refused.reason, assertIs<EffectResolution.Unsupported>(resolved).message)
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

    val refused = assertIs<TextRaster.Refused>(drawn)
    assertEquals(textAllocationFailed(width, height), refused.reason)
    assertEquals(2, canvases.size, "the coverage asked for ${canvases.size} tiles")
    assertTrue(canvases.all { it.width == 0 && it.height == 0 }, "a tile canvas still holds pixels")
  }

  @Test
  fun anArrayTheBrowserCannotAllocateReadsAsNone() {
    assertNull(allocatedOrNull { Int8Array(-1) })
    assertEquals(CHANNELS, allocatedOrNull { ByteArray(CHANNELS) }?.size)
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

/**
 * Capabilities a browser text overlay resolves under, SDR with text drawing.
 */
internal val TEXT_CAPABILITIES = textCapabilities(4_096)

/**
 * Capabilities a browser text overlay resolves under, SDR with text drawing, holding at most [maxTextureSize] pixels
 * on a texture's side.
 */
internal fun textCapabilities(maxTextureSize: Int): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = false,
    colorSpaces = setOf(ColorSpace.Bt709),
    maxTextureSize = maxTextureSize,
    features = setOf(RenderFeature.TextRendering),
  )

/**
 * Attributes a browser text overlay resolves under, a 1280 by 720 SDR frame laid out at its own size.
 */
internal val TEXT_ATTRIBUTES =
  Attributes(
    inputSize = Size(1280, 720),
    outputSize = Size(1280, 720),
    layoutSize = Size(1280, 720),
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = TimeRange.of(Duration.ZERO, 10.seconds),
  )
