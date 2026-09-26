package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WebOverlayTest {
  @Test
  fun textWiderThanTheWrapWidthBreaksAtTheSpace() {
    val hello = assertNotNull(rasterizeText("Hello", WIDE, FRAME)).size
    val whole = assertNotNull(rasterizeText(HELLO_WORLD, WIDE, FRAME)).size

    val style = TextStyle(maxWidth = (hello.width + whole.width) / 2f / FRAME.width)
    val wrap = style.wrapWidthOn(FRAME)
    assertTrue(wrap in hello.width until whole.width, "wrap $wrap between ${hello.width} and ${whole.width}")

    val raster = assertNotNull(rasterizeText(HELLO_WORLD, style, FRAME))

    assertEquals(listOf("Hello", "world"), raster.lines)
    assertTrue(raster.size.width <= wrap, "a ${raster.size.width} wide raster outgrew its $wrap wrap")
    // The block is rounded up once rather than per line.
    assertTrue(raster.size.height in 2 * hello.height - 1..2 * hello.height, "two lines stand ${raster.size}")
  }

  @Test
  fun aWordWiderThanTheLineSplitsBetweenGraphemesAndANewlineBreaks() {
    // A base letter and a combining accent, two code units drawn as one grapheme.
    val accented = "e\u0301"
    val word = accented.repeat(3)

    val lines = wrapLines("$word\nok", width = 2 * accented.length) { it.length.toDouble() }

    assertEquals(listOf(accented.repeat(2), accented, "ok"), lines)
  }

  @Test
  fun aPlateWidensTheRasterByItsPaddingOnEverySide() {
    val plated = TextStyle(backgroundColor = BLACK)

    val without = assertNotNull(rasterizeText(HELLO_WORLD, TextStyle(), FRAME)).size
    val with = assertNotNull(rasterizeText(HELLO_WORLD, plated, FRAME)).size

    val padding = plated.platePaddingOn(FRAME)
    assertTrue(padding > 0)
    assertEquals(Size(without.width + 2 * padding, without.height + 2 * padding), with)
  }

  @Test
  fun capitalsStandAtTheStylesCapHeight() {
    val style = TextStyle()

    val raster = assertNotNull(rasterizeText("H", style, FRAME))

    val cap = style.capHeightOn(FRAME)
    assertTrue(raster.capHeight in cap - 1f..cap + 1f, "capitals stand ${raster.capHeight} for $cap")
  }

  @Test
  fun aFamilyThatIsNoCssIdentifierIsStillTheOneDrawn() {
    val style = TextStyle(resolvedFontFamily = ODD_FAMILY)

    assertIs<EffectResolution.Resolved>(
      BuiltInEffectResolver().resolve(TextOverlay(HELLO_WORLD, style), CAPABILITIES, ATTRIBUTES),
    )
    assertTheFamilyIsDrawn(style, "1 Weird, ", "Font")
  }

  @Test
  fun aFamilyHoldingANewlineIsStillTheOneDrawn() {
    assertTheFamilyIsDrawn(TextStyle(resolvedFontFamily = "Line\nBreak"), "Line", "Break")
  }

  @Test
  fun textResolvesOnlyWhereTextRenderingIsAdvertised() {
    val text = TextOverlay(HELLO_WORLD)
    val resolver = BuiltInEffectResolver()

    assertIs<EffectResolution.Unsupported>(resolver.resolve(text, NO_TEXT_CAPABILITIES, ATTRIBUTES))
    assertIs<EffectResolution.Resolved>(resolver.resolve(text, CAPABILITIES, ATTRIBUTES))
  }

  @Test
  fun textDrawsAtWhatTheSharedFunctionsAnswerAcrossAFade() {
    val text = TextOverlay(HELLO_WORLD, animation = FADE)

    val overlay = overlayOf(BuiltInEffectResolver().resolve(text, CAPABILITIES, ATTRIBUTES))
    val base = text.placedOn(ATTRIBUTES.drawnTextSize(overlay.size))

    assertDrawsAcrossTheFade(text, overlay) { frame -> base.animatedBy(frame).rectOn(FRAME) }
  }

  @Test
  fun aPreloadedImageResolvesAtItsOwnSizeAndDrawsAcrossAFade() =
    runTest {
      val image = ImageOverlay(ImageSource.ofBytes(pngOf(PNG)), Corner.TopEnd, animation = FADE)
      val resolver = BuiltInEffectResolver()

      resolver.preload(compositionOf(image))
      val overlay = overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))

      assertEquals(PNG, overlay.size)
      val base = image.placedOn(FRAME, overlay.size)
      assertDrawsAcrossTheFade(image, overlay) { frame -> base.animatedBy(frame).rectOn(FRAME) }
    }

  @Test
  fun aPreloadedUriImageResolvesAtItsOwnSize() =
    runTest {
      val uri = "data:image/png;base64," + Base64.encode(pngOf(PNG))
      val image = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.preload(compositionOf(image))

      assertEquals(PNG, overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).size)
    }

  @Test
  fun aCorruptImageResolvesUnsupportedNamingIt() =
    runTest {
      val bytes = pngOf(PNG).copyOf(CORRUPT_LENGTH)
      val image = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.preload(compositionOf(image))

      assertUnsupportedNaming("$CORRUPT_LENGTH-byte", resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
    }

  @Test
  fun anImageAtAPathResolvesUnsupportedNamingIt() =
    runTest {
      val path = "/sdcard/watermark.png"
      val image = ImageOverlay(ImageSource.Path(path), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.preload(compositionOf(image))

      assertUnsupportedNaming(path, resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
    }

  @Test
  fun anImageNeverPreloadedResolvesUnsupportedNamingIt() {
    val uri = "https://example.invalid/watermark.png"
    val image = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)

    assertUnsupportedNaming(uri, BuiltInEffectResolver().resolve(image, CAPABILITIES, ATTRIBUTES))
  }

  @Test
  fun overlaysOnAnHdrGradeResolveUnsupported() =
    runTest {
      val image = ImageOverlay(ImageSource.ofBytes(pngOf(PNG)), Corner.TopEnd)
      val text = TextOverlay(HELLO_WORLD)
      val resolver = BuiltInEffectResolver()
      resolver.preload(compositionOf(image))

      for (spec in listOf<EffectSpec>(image, text)) {
        val resolved = resolver.resolve(spec, CAPABILITIES, HDR_ATTRIBUTES)
        assertIs<EffectResolution.Unsupported>(resolved, "$spec on an HDR grade")
        assertTrue(resolved.message.contains("HDR"), resolved.message)
      }
    }

  private fun overlayOf(resolution: EffectResolution?): WebGlOverlay {
    val resolved = assertIs<EffectResolution.Resolved>(resolution)
    return assertNotNull(resolved.effect.overlay)
  }

  private fun assertDrawsAcrossTheFade(
    spec: OverlayEffect,
    overlay: WebGlOverlay,
    rectOf: (OverlayFrame) -> NormalizedRect,
  ) {
    for (time in listOf(FADE_LENGTH / 4, FADE_LENGTH * 3 / 4)) {
      val frame = assertNotNull(spec.frameAt(time, SPAN))
      val draw = assertNotNull(overlay.drawAt(time), "drawn at $time")

      assertEquals(frame.opacity, draw.opacity, "opacity at $time")
      assertEquals(rectOf(frame), draw.rect, "rect at $time")
    }
  }

  private fun assertUnsupportedNaming(
    name: String,
    resolution: EffectResolution?,
  ) {
    val unsupported = assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue(unsupported.message.contains(name), unsupported.message)
  }

  // A shorthand the canvas cannot parse leaves the context on its 10px default, which names no such
  // family and stands a capital far below the style's cap height.
  private fun assertTheFamilyIsDrawn(
    style: TextStyle,
    vararg parts: String,
  ) {
    val raster = assertNotNull(rasterizeText(HELLO_WORLD, style, FRAME))
    val font = assertNotNull(raster.canvas.getContext("2d")).font

    assertTrue(parts.all { it in font }, font)
    val cap = style.capHeightOn(FRAME)
    assertTrue(raster.capHeight in cap - 1f..cap + 1f, "capitals stand ${raster.capHeight} for $cap in $font")
  }

  private fun compositionOf(effect: EffectSpec): EditComposition =
    EditComposition(listOf(Track(listOf(Clip(MediaSource.ofUri("blob:clip"), effects = listOf(effect))))))

  private companion object {
    val FRAME = Size(1280, 720)
    val PNG = Size(5, 3)
    val SPAN = TimeRange.of(Duration.ZERO, 10.seconds)
    val FADE_LENGTH = 2.seconds
    val FADE = fadeIn(FADE_LENGTH) + slideIn(OverlayOffset(-0.4f, 0f), FADE_LENGTH)
    val WIDE = TextStyle(maxWidth = 1f)

    const val HELLO_WORLD = "Hello world"
    const val ODD_FAMILY = "1 Weird, \"Font\""
    const val BLACK = 0xFF000000.toInt()
    const val CORRUPT_LENGTH = 40

    val ATTRIBUTES = attributes(hdrTransfer = null)
    val HDR_ATTRIBUTES = attributes(hdrTransfer = HdrTransfer.Pq)

    val CAPABILITIES = capabilities(setOf(RenderFeature.TextRendering))
    val NO_TEXT_CAPABILITIES = capabilities(emptySet())

    fun capabilities(features: Set<RenderFeature>) =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = features,
      )

    fun attributes(hdrTransfer: HdrTransfer?) =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = hdrTransfer,
        frameRate = 30f,
        span = SPAN,
      )
  }
}

/**
 * An opaque RGBA PNG of [size], its deflate stream left stored rather than compressed.
 */
private fun pngOf(size: Size): ByteArray {
  val rows = ByteArray((size.width * 4 + 1) * size.height)
  for (index in rows.indices) {
    if (index % (size.width * 4 + 1) != 0) rows[index] = 0xFF.toByte()
  }

  val header =
    int32(size.width) + int32(size.height) + byteArrayOf(8, 6, 0, 0, 0)
  val zlib =
    byteArrayOf(0x78, 0x01, 0x01) +
      int16LittleEndian(rows.size) +
      int16LittleEndian(rows.size.inv() and 0xFFFF) +
      rows +
      int32(adler32(rows))

  return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
    chunk("IHDR", header) +
    chunk("IDAT", zlib) +
    chunk("IEND", ByteArray(0))
}

private fun chunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val typed = type.encodeToByteArray() + data
  return int32(data.size) + typed + int32(crc32(typed))
}

private fun int32(value: Int): ByteArray =
  byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

private fun int16LittleEndian(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun crc32(bytes: ByteArray): Int {
  var crc = -1
  for (byte in bytes) {
    crc = crc xor (byte.toInt() and 0xFF)
    repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
  }
  return crc.inv()
}

private fun adler32(bytes: ByteArray): Int {
  var a = 1
  var b = 0
  for (byte in bytes) {
    a = (a + (byte.toInt() and 0xFF)) % 65521
    b = (b + a) % 65521
  }
  return (b shl 16) or a
}
