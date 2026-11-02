package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pngExtent
import dev.jordond.filmstrip.internal.pngSamples
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.orientedSamples
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.OrientedPictures
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WebOverlayTest {
  @Test
  fun textWiderThanTheWrapWidthBreaksAtTheSpace() {
    val hello = drawnText("Hello", WIDE, FRAME).size
    val whole = drawnText(HELLO_WORLD, WIDE, FRAME).size

    val style = TextStyle(maxWidth = (hello.width + whole.width) / 2f / FRAME.width)
    val wrap = style.wrapWidthOn(FRAME)
    assertTrue(wrap in hello.width until whole.width, "wrap $wrap between ${hello.width} and ${whole.width}")

    val raster = drawnText(HELLO_WORLD, style, FRAME)

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

    val without = drawnText(HELLO_WORLD, TextStyle(), FRAME).size
    val with = drawnText(HELLO_WORLD, plated, FRAME).size

    val padding = plated.platePaddingOn(FRAME)
    assertTrue(padding > 0)
    assertEquals(Size(without.width + 2 * padding, without.height + 2 * padding), with)
  }

  @Test
  fun capitalsStandAtTheStylesCapHeight() {
    val style = TextStyle()

    val raster = drawnText("H", style, FRAME)

    val cap = style.capHeightOn(FRAME)
    assertTrue(raster.capHeight in cap - 1f..cap + 1f, "capitals stand ${raster.capHeight} for $cap")
  }

  @Test
  fun aFamilyThatIsNoCssIdentifierStillParses() {
    val style = TextStyle(resolvedFontFamily = ODD_FAMILY)

    assertIs<EffectResolution.Resolved>(
      BuiltInEffectResolver().resolve(TextOverlay(HELLO_WORLD, style), CAPABILITIES, ATTRIBUTES),
    )
    assertTheFontParses(style, "1 Weird, ", "Font")
  }

  @Test
  fun aFamilyHoldingANewlineStillParses() {
    assertTheFontParses(TextStyle(resolvedFontFamily = "Line\nBreak"), "Line", "Break")
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
  fun aPictureAsLongAsATextureSideResolvesAndOnePastIsRefused() =
    runTest {
      for (size in listOf(PNG, Size(PNG.height, PNG.width))) {
        val image = ImageOverlay(ImageSource.ofBytes(pngOf(size)), Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        val longest = maxOf(size.width, size.height)
        resolver.preload(compositionOf(image))

        assertEquals(size, overlayOf(resolver.resolve(image, textCapabilities(longest), ATTRIBUTES)).size)
        val past = resolver.resolve(image, textCapabilities(longest - 1), ATTRIBUTES)
        val refused = assertIs<EffectResolution.Unsupported>(past, "the $size picture resolved as $past")
        assertEquals(picturePastTextureSide(size.width, size.height, longest - 1, WEBGL), refused.message)
      }
    }

  // A turned picture is refused with the size it is shown at, the size every other part of the resolver places.
  @Test
  fun aTurnedPictureIsRefusedAtTheSizeItIsShown() =
    runTest {
      val turn = PictureOrientation.Rotate90
      val image = ImageOverlay(ImageSource.ofBytes(OrientedPictures.taggedJpeg(JPEG, turn.exif)), Corner.TopEnd)
      val shown = turn.orientedSize(JPEG_SIZE)
      val longest = maxOf(shown.width, shown.height)
      val resolver = BuiltInEffectResolver()
      resolver.preload(compositionOf(image))

      assertEquals(JPEG_SIZE, overlayOf(resolver.resolve(image, textCapabilities(longest), ATTRIBUTES)).size)
      val past = resolver.resolve(image, textCapabilities(longest - 1), ATTRIBUTES)
      val refused = assertIs<EffectResolution.Unsupported>(past, "the turned picture resolved as $past")
      assertEquals(picturePastTextureSide(shown.width, shown.height, longest - 1, WEBGL), refused.message)
    }

  // The bitmap keeps the stored codes, so the compositor needs the colour the picture's own bytes describe.
  @Test
  fun aTaggedImageCarriesTheColourItsBytesDescribe() =
    runTest {
      val bytes = pngOf(PNG, chunk(CICP, CICP_DISPLAY_P3))
      val uri = "data:image/png;base64," + Base64.encode(bytes)
      val expected = assertNotNull(pictureColour(bytes))

      for (source in listOf(ImageSource.ofBytes(bytes), ImageSource.ofUri(uri))) {
        val image = ImageOverlay(source, Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        resolver.preload(compositionOf(image))

        assertEquals(expected, overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).colour, "$source")
      }
    }

  @Test
  fun anUntaggedImageCarriesNoColour() =
    runTest {
      val bytes = pngOf(PNG)
      val uri = "data:image/png;base64," + Base64.encode(bytes)

      for (source in listOf(ImageSource.ofBytes(bytes), ImageSource.ofUri(uri))) {
        val image = ImageOverlay(source, Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        resolver.preload(compositionOf(image))

        assertNull(overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).colour, "$source")
      }
    }

  // The compositor converts a PNG from the samples it stores, so a sixteen-bit one keeps the low byte of each sample
  // that a bitmap drops.
  @Test
  fun aPngCarriesTheSamplesItStores() =
    runTest {
      for (bytes in listOf(pngOf(PNG), pngOf(PNG, sixteenBitSample = SIXTEEN_BIT_SAMPLE))) {
        val image = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        resolver.preload(compositionOf(image))

        val samples = assertNotNull(samplesOf(overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))))
        assertContentEquals(assertNotNull(pngSamples(bytes)).rgba, samples)
      }
      val sixteenBit = assertNotNull(pngSamples(pngOf(PNG, sixteenBitSample = SIXTEEN_BIT_SAMPLE)))
      assertEquals(SIXTEEN_BIT_SAMPLE, sixteenBit.rgba.first().toInt() and 0xFFFF)
    }

  // The browser decodes a picture with no orientation applied. A PNG's samples are laid out the way core reads its
  // eXIf, and the resolver places the picture at the size that shows it.
  @Test
  fun aPngWithAnExifOrientationCarriesItsSamplesLaidOutTheWayCoreReadsIt() =
    runTest {
      val stored = assertNotNull(pngSamples(OrientedPictures.png()))
      val storedSize = Size(stored.width, stored.height)
      for (orientation in PictureOrientation.entries) {
        val bytes = OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif)
        val overlay = resolvedOverlay(ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd))

        assertEquals(orientation.orientedSize(storedSize), overlay.size, "$orientation")
        assertEquals(PictureOrientation.Normal, overlay.orientation, "$orientation")
        val samples = assertNotNull(samplesOf(overlay), "$orientation")
        assertContentEquals(orientedSamples(orientation, stored.rgba, storedSize), samples, "$orientation")
      }
    }

  // A JPEG reaches the compositor as the bitmap the browser decodes it to, as stored, with the turn the compositor
  // lays it out by and placed at the size that turn shows it at.
  @Test
  fun aJpegWithAnExifOrientationIsUploadedAsStoredAndPlacedTurned() =
    runTest {
      for (orientation in PictureOrientation.entries) {
        val tagged = OrientedPictures.taggedJpeg(JPEG, orientation.exif)
        val image = ImageOverlay(ImageSource.ofBytes(tagged), Corner.TopEnd)
        val overlay = resolvedOverlay(image)

        assertEquals(JPEG_SIZE, overlay.size, "$orientation")
        assertEquals(orientation, overlay.orientation, "$orientation")
        val expected = image.placedOn(FRAME, orientation.orientedSize(JPEG_SIZE)).rectOn(FRAME)
        assertEquals(expected, assertNotNull(overlay.drawAt(Duration.ZERO)).rect, "$orientation")
      }
    }

  // Chrome turns a JPEG by its tag even with imageOrientation none, so a bitmap at the stored size on every
  // orientation means the browser was handed the tag written as 1.
  @Test
  fun theBrowserDecodesEveryTaggedPictureAsStored() =
    runTest {
      val png = assertNotNull(pngSamples(OrientedPictures.png()))
      val pngSize = Size(png.width, png.height)
      for (orientation in PictureOrientation.entries) {
        val pictures =
          listOf(
            Triple("PNG", OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif), pngSize),
            Triple("JPEG", OrientedPictures.taggedJpeg(JPEG, orientation.exif), JPEG_SIZE),
          )
        for ((name, tagged, stored) in pictures) {
          val bitmap = bitmapOf(tagged)

          assertEquals(stored, Size(bitmap.width, bitmap.height), "$name $orientation")
        }
      }
    }

  private suspend fun resolvedOverlay(image: ImageOverlay): WebGlOverlay {
    val resolver = BuiltInEffectResolver()
    resolver.preload(compositionOf(image))
    return overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
  }

  // The rows, samples and bitmap a PNG holds are counted before anything is decoded, so one just past the ceiling is
  // refused by its size and one at it is drawn.
  @Test
  fun aPngWhosePeakPassesTheCeilingIsRefusedNamingItsSize() =
    runTest {
      val bytes = pngOf(PNG)
      val extent = assertNotNull(pngExtent(bytes))
      val peak = assertNotNull(pngPeakBytes(extent))
      val source = ImageSource.ofBytes(bytes)
      val past = OverlayImages(ceiling = peak - 1).apply { preload(source) }[source]
      val at = OverlayImages(ceiling = peak).apply { preload(source) }[source]

      assertEquals(tooLargeToDecode(extent, peak, peak - 1), assertIs<OverlayDecode.Refused>(past).message)
      assertIs<OverlayDecode.Decoded>(at)
    }

  // Only the header is written, which is all the count reads, so a picture this large costs nothing to refuse.
  @Test
  fun theCeilingHoldsA48MpPngAtEitherDepthAndRefusesA64MpSixteenBitOne() =
    runTest {
      for (depth in listOf(8, 16)) {
        val peak = assertNotNull(pngPeakBytes(assertNotNull(pngExtent(pngHeaderOnly(8_000, 6_000, depth)))))
        assertTrue(peak <= PICTURE_PEAK_CEILING, "a 48 MP $depth-bit PNG peaks at $peak")
      }
      val large = pngHeaderOnly(8_000, 8_000, 16)
      val extent = assertNotNull(pngExtent(large))
      val source = ImageSource.ofBytes(large)
      val refused = OverlayImages().apply { preload(source) }[source]

      assertEquals(
        tooLargeToDecode(extent, pngPeakBytes(extent), PICTURE_PEAK_CEILING),
        assertIs<OverlayDecode.Refused>(refused).message,
      )
      assertEquals(1_280_008_000L, pngPeakBytes(extent))
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

  // The compositor maps the raster onto the grade's light itself, so the resolver hands back the same
  // raster whatever the transfer.
  @Test
  fun overlaysOnAnHdrGradeResolveOnBothTransfers() =
    runTest {
      val image = ImageOverlay(ImageSource.ofBytes(pngOf(PNG)), Corner.TopEnd)
      val text = TextOverlay(HELLO_WORLD)
      val resolver = BuiltInEffectResolver()
      resolver.preload(compositionOf(image))

      for (transfer in HdrTransfer.entries) {
        for (spec in listOf<EffectSpec>(image, text)) {
          val overlay = overlayOf(resolver.resolve(spec, CAPABILITIES, attributes(hdrTransfer = transfer)))
          assertNotNull(overlay.drawAt(Duration.ZERO), "$spec on $transfer is not drawn")
        }
      }
    }

  private fun overlayOf(resolution: EffectResolution?): WebGlOverlay {
    val resolved = assertIs<EffectResolution.Resolved>(resolution)
    return assertNotNull(resolved.effect.overlay)
  }

  private fun samplesOf(overlay: WebGlOverlay): ShortArray? = assertIs<OverlaySource.Image>(overlay.source).samples

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

  // A shorthand the canvas cannot parse leaves the context on its 10px default, which stands a capital far below the
  // style's cap height, so capitals at that height mean the shorthand naming the family was the one drawn in. The
  // shorthand read back off a fresh canvas still holds every part of the family's name.
  private fun assertTheFontParses(
    style: TextStyle,
    vararg familyParts: String,
  ) {
    val raster = drawnText(HELLO_WORLD, style, FRAME)

    val cap = style.capHeightOn(FRAME)
    assertTrue(raster.capHeight in cap - 1f..cap + 1f, "capitals stand ${raster.capHeight} for $cap")
    val context = assertNotNull(OffscreenCanvas(1, 1).getContext(CONTEXT_2D))
    context.font = raster.font
    val parsed = context.font
    familyParts.forEach { assertTrue(it in parsed, "the font ${raster.font} parsed as $parsed, without $it") }
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

    // A sample whose low byte is not its high byte, so it is not an eight-bit code times 257.
    const val SIXTEEN_BIT_SAMPLE = 0xA012

    // A picture two texels wide and one tall, red then green.
    // The OrientedPictures quadrants 48 by 32, written by ImageMagick 7 as a JPEG at quality 90 with no metadata.
    val JPEG =
      (
        "ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a070706" +
          "080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb004301030404050405" +
          "09050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414141414141414" +
          "1414141414141414ffc00011080020003003011100021101031101ffc4001800010101010100000000000000000000000008" +
          "070409ffc4001b10000203010101000000000000000000000001020304051121ffc4001a0101010100030100000000000000" +
          "0000000009070203080affc4001a110101010101010100000000000000000000010203211104ffda000c0301000211031100" +
          "3f009f3a1d0d5d5dfa776ed376cdba6c95d7e9d1639d96d926dca7293f5ca4db6db7f5b661dad5ddbad5fb6aa6f1e3cff3f3" +
          "cf1e3999ce649249f2493c9249e4927924739c5dc018117191d00006fa43958b00018117191d00007a867ce63c340002ca30" +
          "a55500011a9baa550000b28c295540007fffd9"
      ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val JPEG_SIZE = Size(48, 32)

    // Display P3 primaries, the sRGB transfer, the identity matrix and full range.
    const val CICP = "cICP"
    val CICP_DISPLAY_P3 = byteArrayOf(12, 13, 0, 1)

    val ATTRIBUTES = attributes(hdrTransfer = null)

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
