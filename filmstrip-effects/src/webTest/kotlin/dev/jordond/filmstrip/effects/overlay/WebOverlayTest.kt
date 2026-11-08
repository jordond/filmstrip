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
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.png.pngExtent
import dev.jordond.filmstrip.picture.png.pngSamples
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WebOverlayTest {
  @Test
  fun textWiderThanTheWrapWidthBreaksAtTheSpace() {
    val hello = drawnText("Hello", WIDE, FRAME).size
    val whole = drawnText(HELLO_WORLD, WIDE, FRAME).size

    val style = TextStyle(maxWidth = (hello.width + whole.width) / 2f / FRAME.width)
    val wrap = style.wrapWidthOn(FRAME)
    withClue("wrap $wrap between ${hello.width} and ${whole.width}") {
      (wrap in hello.width until whole.width) shouldBe true
    }

    val raster = drawnText(HELLO_WORLD, style, FRAME)

    raster.lines shouldBe listOf("Hello", "world")
    withClue("a ${raster.size.width} wide raster outgrew its $wrap wrap") { (raster.size.width <= wrap) shouldBe true }
    // The block is rounded up once rather than per line.
    withClue("two lines stand ${raster.size}") {
      (raster.size.height in 2 * hello.height - 1..2 * hello.height) shouldBe true
    }
  }

  @Test
  fun aWordWiderThanTheLineSplitsBetweenGraphemesAndANewlineBreaks() {
    // A base letter and a combining accent, two code units drawn as one grapheme.
    val accented = "e\u0301"
    val word = accented.repeat(3)

    val lines = wrapLines("$word\nok", width = 2 * accented.length) { it.length.toDouble() }

    lines shouldBe listOf(accented.repeat(2), accented, "ok")
  }

  @Test
  fun aPlateWidensTheRasterByItsPaddingOnEverySide() {
    val plated = TextStyle(backgroundColor = BLACK)

    val without = drawnText(HELLO_WORLD, TextStyle(), FRAME).size
    val with = drawnText(HELLO_WORLD, plated, FRAME).size

    val padding = plated.platePaddingOn(FRAME)
    (padding > 0) shouldBe true
    with shouldBe Size(without.width + 2 * padding, without.height + 2 * padding)
  }

  @Test
  fun capitalsStandAtTheStylesCapHeight() {
    val style = TextStyle()

    val raster = drawnText("H", style, FRAME)

    val cap = style.capHeightOn(FRAME)
    withClue("capitals stand ${raster.capHeight} for $cap") { (raster.capHeight in cap - 1f..cap + 1f) shouldBe true }
  }

  @Test
  fun aFamilyThatIsNoCssIdentifierStillParses() {
    val style = TextStyle(resolvedFontFamily = ODD_FAMILY)

    BuiltInEffectResolver()
      .resolve(
        TextOverlay(HELLO_WORLD, style),
        CAPABILITIES,
        ATTRIBUTES,
      ).shouldBeInstanceOf<EffectResolution.Resolved>()
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

    resolver.resolve(text, NO_TEXT_CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Unsupported>()
    resolver.resolve(text, CAPABILITIES, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Resolved>()
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

      resolver.prepare(compositionOf(image))
      val overlay = overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))

      overlay.size shouldBe PNG
      val base = image.placedOn(FRAME, overlay.size)
      assertDrawsAcrossTheFade(image, overlay) { frame -> base.animatedBy(frame).rectOn(FRAME) }
    }

  @Test
  fun aPreloadedUriImageResolvesAtItsOwnSize() =
    runTest {
      val uri = "data:image/png;base64," + Base64.encode(pngOf(PNG))
      val image = ImageOverlay(ImageSource.ofUri(uri), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.prepare(compositionOf(image))

      overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).size shouldBe PNG
    }

  @Test
  fun aPictureAsLongAsATextureSideResolvesAndOnePastIsRefused() =
    runTest {
      for (size in listOf(PNG, Size(PNG.height, PNG.width))) {
        val image = ImageOverlay(ImageSource.ofBytes(pngOf(size)), Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        val longest = maxOf(size.width, size.height)
        resolver.prepare(compositionOf(image))

        overlayOf(resolver.resolve(image, textCapabilities(longest), ATTRIBUTES)).size shouldBe size
        val past = resolver.resolve(image, textCapabilities(longest - 1), ATTRIBUTES)
        val refused =
          withClue("the $size picture resolved as $past") {
            past.shouldBeInstanceOf<EffectResolution.Unsupported>()
          }
        refused.message shouldBe picturePastTextureSide(size.width, size.height, longest - 1, WEBGL)
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
      resolver.prepare(compositionOf(image))

      overlayOf(resolver.resolve(image, textCapabilities(longest), ATTRIBUTES)).size shouldBe JPEG_SIZE
      val past = resolver.resolve(image, textCapabilities(longest - 1), ATTRIBUTES)
      val refused =
        withClue("the turned picture resolved as $past") {
          past.shouldBeInstanceOf<EffectResolution.Unsupported>()
        }
      refused.message shouldBe picturePastTextureSide(shown.width, shown.height, longest - 1, WEBGL)
    }

  // The bitmap keeps the stored codes, so the compositor needs the color the picture's own bytes describe.
  @Test
  fun aTaggedImageCarriesTheColorItsBytesDescribe() =
    runTest {
      val bytes = pngOf(PNG, chunk(CICP, CICP_DISPLAY_P3))
      val uri = "data:image/png;base64," + Base64.encode(bytes)
      val expected = pictureColor(bytes).shouldNotBeNull()

      for (source in listOf(ImageSource.ofBytes(bytes), ImageSource.ofUri(uri))) {
        val image = ImageOverlay(source, Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        resolver.prepare(compositionOf(image))

        withClue("$source") { overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).color shouldBe expected }
      }
    }

  @Test
  fun anUntaggedImageCarriesNoColor() =
    runTest {
      val bytes = pngOf(PNG)
      val uri = "data:image/png;base64," + Base64.encode(bytes)

      for (source in listOf(ImageSource.ofBytes(bytes), ImageSource.ofUri(uri))) {
        val image = ImageOverlay(source, Corner.TopEnd)
        val resolver = BuiltInEffectResolver()
        resolver.prepare(compositionOf(image))

        withClue("$source") { overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES)).color.shouldBeNull() }
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
        resolver.prepare(compositionOf(image))

        val samples = samplesOf(overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))).shouldNotBeNull()
        samples shouldBe pngSamples(bytes).shouldNotBeNull().rgba
      }
      val sixteenBit = pngSamples(pngOf(PNG, sixteenBitSample = SIXTEEN_BIT_SAMPLE)).shouldNotBeNull()
      (sixteenBit.rgba.first().toInt() and 0xFFFF) shouldBe SIXTEEN_BIT_SAMPLE
    }

  // The browser decodes a picture with no orientation applied. A PNG's samples are laid out the way core reads its
  // eXIf, and the resolver places the picture at the size that shows it.
  @Test
  fun aPngWithAnExifOrientationCarriesItsSamplesLaidOutTheWayCoreReadsIt() =
    runTest {
      val stored = pngSamples(OrientedPictures.png()).shouldNotBeNull()
      val storedSize = Size(stored.width, stored.height)
      for (orientation in PictureOrientation.entries) {
        val bytes = OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif)
        val overlay = resolvedOverlay(ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd))

        withClue("$orientation") { overlay.size shouldBe orientation.orientedSize(storedSize) }
        withClue("$orientation") { overlay.orientation shouldBe PictureOrientation.Normal }
        val samples = withClue("$orientation") { samplesOf(overlay).shouldNotBeNull() }
        withClue("$orientation") { samples shouldBe orientedSamples(orientation, stored.rgba, storedSize) }
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

        withClue("$orientation") { overlay.size shouldBe JPEG_SIZE }
        withClue("$orientation") { overlay.orientation shouldBe orientation }
        val expected = image.placedOn(FRAME, orientation.orientedSize(JPEG_SIZE)).rectOn(FRAME)
        withClue("$orientation") { overlay.drawAt(Duration.ZERO).shouldNotBeNull().rect shouldBe expected }
      }
    }

  // Chrome turns a JPEG by its tag even with imageOrientation none, so a bitmap at the stored size on every
  // orientation means the browser was handed the tag written as 1.
  @Test
  fun theBrowserDecodesEveryTaggedPictureAsStored() =
    runTest {
      val png = pngSamples(OrientedPictures.png()).shouldNotBeNull()
      val pngSize = Size(png.width, png.height)
      for (orientation in PictureOrientation.entries) {
        val pictures =
          listOf(
            Triple("PNG", OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif), pngSize),
            Triple("JPEG", OrientedPictures.taggedJpeg(JPEG, orientation.exif), JPEG_SIZE),
          )
        for ((name, tagged, stored) in pictures) {
          val bitmap = bitmapOf(tagged)

          withClue("$name $orientation") { Size(bitmap.width, bitmap.height) shouldBe stored }
        }
      }
    }

  private suspend fun resolvedOverlay(image: ImageOverlay): WebGlOverlay {
    val resolver = BuiltInEffectResolver()
    resolver.prepare(compositionOf(image))
    return overlayOf(resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
  }

  // The rows, samples and bitmap a PNG holds are counted before anything is decoded, so one just past the ceiling is
  // refused by its size and one at it is drawn.
  @Test
  fun aPngWhosePeakPassesTheCeilingIsRefusedNamingItsSize() =
    runTest {
      val bytes = pngOf(PNG)
      val extent = pngExtent(bytes).shouldNotBeNull()
      val peak = pngPeakBytes(extent).shouldNotBeNull()
      val source = ImageSource.ofBytes(bytes)
      val past = OverlayImages(ceiling = peak - 1).apply { preload(source) }[source]
      val at = OverlayImages(ceiling = peak).apply { preload(source) }[source]

      past.shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe tooLargeToDecode(extent, peak, peak - 1)
      at.shouldBeInstanceOf<OverlayDecode.Decoded>()
    }

  // Only the header is written, which is all the count reads, so a picture this large costs nothing to refuse.
  @Test
  fun theCeilingHoldsA48MpPngAtEitherDepthAndRefusesA64MpSixteenBitOne() =
    runTest {
      for (depth in listOf(8, 16)) {
        val peak = pngPeakBytes(pngExtent(pngHeaderOnly(8_000, 6_000, depth)).shouldNotBeNull()).shouldNotBeNull()
        withClue("a 48 MP $depth-bit PNG peaks at $peak") { (peak <= PICTURE_PEAK_CEILING) shouldBe true }
      }
      val large = pngHeaderOnly(8_000, 8_000, 16)
      val extent = pngExtent(large).shouldNotBeNull()
      val source = ImageSource.ofBytes(large)
      val refused = OverlayImages().apply { preload(source) }[source]

      refused.shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe
        tooLargeToDecode(extent, pngPeakBytes(extent), PICTURE_PEAK_CEILING)
      pngPeakBytes(extent) shouldBe 1_280_008_000L
    }

  @Test
  fun aCorruptImageResolvesUnsupportedNamingIt() =
    runTest {
      val bytes = pngOf(PNG).copyOf(CORRUPT_LENGTH)
      val image = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.prepare(compositionOf(image))

      assertUnsupportedNaming("$CORRUPT_LENGTH-byte", resolver.resolve(image, CAPABILITIES, ATTRIBUTES))
    }

  @Test
  fun anImageAtAPathResolvesUnsupportedNamingIt() =
    runTest {
      val path = "/sdcard/watermark.png"
      val image = ImageOverlay(ImageSource.Path(path), Corner.TopEnd)
      val resolver = BuiltInEffectResolver()

      resolver.prepare(compositionOf(image))

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
      resolver.prepare(compositionOf(image))

      for (transfer in HdrTransfer.entries) {
        for (spec in listOf<EffectSpec>(image, text)) {
          val overlay = overlayOf(resolver.resolve(spec, CAPABILITIES, attributes(hdrTransfer = transfer)))
          withClue("$spec on $transfer is not drawn") { overlay.drawAt(Duration.ZERO).shouldNotBeNull() }
        }
      }
    }

  private fun overlayOf(resolution: EffectResolution?): WebGlOverlay {
    val resolved = resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    return resolved.effect.overlay.shouldNotBeNull()
  }

  private fun samplesOf(overlay: WebGlOverlay): ShortArray? =
    overlay.source.shouldBeInstanceOf<OverlaySource.Image>().samples

  private fun assertDrawsAcrossTheFade(
    spec: OverlayEffect,
    overlay: WebGlOverlay,
    rectOf: (OverlayFrame) -> NormalizedRect,
  ) {
    for (time in listOf(FADE_LENGTH / 4, FADE_LENGTH * 3 / 4)) {
      val frame = spec.frameAt(time, SPAN).shouldNotBeNull()
      val draw = withClue("drawn at $time") { overlay.drawAt(time).shouldNotBeNull() }

      withClue("opacity at $time") { draw.opacity shouldBe frame.opacity }
      withClue("rect at $time") { draw.rect shouldBe rectOf(frame) }
    }
  }

  private fun assertUnsupportedNaming(
    name: String,
    resolution: EffectResolution?,
  ) {
    val unsupported = resolution.shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(unsupported.message) { unsupported.message.contains(name) shouldBe true }
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
    withClue("capitals stand ${raster.capHeight} for $cap") { (raster.capHeight in cap - 1f..cap + 1f) shouldBe true }
    val context = OffscreenCanvas(1, 1).getContext(CONTEXT_2D).shouldNotBeNull()
    context.font = raster.font
    val parsed = context.font
    familyParts.forEach {
      withClue("the font ${raster.font} parsed as $parsed, without $it") {
        (it in parsed) shouldBe true
      }
    }
  }

  private fun compositionOf(effect: EffectSpec): EditComposition =
    EditComposition(listOf(Track(listOf(Clip(MediaSource.ofUri("blob:clip"), effects = listOf(effect))))))

  private companion object {
    val FRAME = Size(1280, 720)
    val PNG = Size(5, 3)
    val SPAN = TimeRange.of(Duration.ZERO, 10.seconds)
    val FADE_LENGTH = 2.seconds
    val FADE = fade(inDuration = FADE_LENGTH) + slideIn(OverlayOffset(-0.4f, 0f), FADE_LENGTH)
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
