package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.PngColor
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.render.straightCodesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Animated pictures drawn into a real browser export and preview, and the stills whose alpha the straight read keeps.
 *
 * Every expected frame is the one the file's own [FrameTimeline] puts on the slot, read off the squares each counter
 * frame writes its index in. Bleed is read off the preview, which composites through the export's own compositor before
 * any encode, so flat frames need no tolerance for it.
 */
class BrowserAnimatedPictureMediaTest {
  @Test
  fun everyExportedFrameOfAGifShowsTheFrameTheTimelineNames() =
    runTest { everyExportedFrameShowsItsFrame(AnimatedGifs.bytes(CANVAS, counters(), loops = 0)) }

  @Test
  fun everyExportedFrameOfAnApngShowsTheFrameTheTimelineNames() =
    runTest { everyExportedFrameShowsItsFrame(AnimatedApngs.bytes(CANVAS, counters())) }

  @Test
  fun everyExportedFrameOfAWebpShowsTheFrameTheTimelineNames() =
    runTest { everyExportedFrameShowsItsFrame(AnimatedWebps.bytes(CANVAS, counters())) }

  // The default image is a flat magenta no frame holds. Outside the counter tile frame 0 is transparent, so the clip
  // shows through there unless the default image is drawn.
  @Test
  fun anApngShowsItsFirstFrameAndNotItsDefaultImage() =
    runTest {
      val bytes = AnimatedApngs.bytes(CANVAS, counters(), defaultIsFrame = false)
      val overlay = overlayOf(bytes)
      val preview = previewOf(overlay)
      try {
        val frame = withClue("no preview frame") { preview.frameAt(outputSlotTime(0, FRAME_RATE)).shouldNotBeNull() }
        val rect = placedRect(overlay, frame.size)
        indexIn(frame, rect) shouldBe 0
        val canvas = AnimatedPictures.COUNTER_CANVAS
        val outside =
          frame.at(
            rect.left + rect.width * OUTSIDE_X / canvas.width,
            rect.top + rect.height * OUTSIDE_Y / canvas.height,
          )
        withClue(
          "the clip should show at $outside",
        ) { outside.isNear(frame.at(FAR, FAR), BLEND_TOLERANCE) shouldBe true }
      } finally {
        preview.release()
      }
    }

  // The preview is seeked forward and then back to slots it showed before, which are drawn from the composition time
  // alone, so each shows the frame the timeline names for that slot and not the one shown last.
  @Test
  fun aPreviewSeekedBackShowsTheFrameTheTimelineNamesForThatSlot() =
    runTest {
      val bytes = AnimatedApngs.bytes(CANVAS, counters())
      val overlay = overlayOf(bytes)
      val timeline = timelineOf(bytes)
      val drawnStart = drawnStartOf(overlay)
      val preview = previewOf(overlay)
      try {
        val seen = mutableListOf<Int>()
        for (slot in listOf(40L, 3L, 29L, 12L, 0L, 33L, 7L)) {
          val frame =
            withClue("no preview frame at slot $slot") {
              preview.frameAt(outputSlotTime(slot, FRAME_RATE)).shouldNotBeNull()
            }
          val expected = timeline.frameOnSlot(drawnStart, slot, FRAME_RATE)
          withClue("slot $slot") { indexIn(frame, placedRect(overlay, frame.size)) shouldBe expected }
          seen += expected
        }
        withClue("the seeks land on more than one frame") { (seen.toSet().size > 1) shouldBe true }
      } finally {
        preview.release()
      }
    }

  // The frames are flat colors out to their edges, so a texel drawn from the frame beside one is a color it does not
  // hold. The atlas is drawn between whole pixels, which spreads a bilinear read of the next cell over the pixels in
  // from the edge, and once on whole pixels, which a read a quarter texel in blends too if it is not clamped.
  @Test
  fun aFrameDrawnBetweenWholePixelsHoldsNoColorFromTheFrameBesideIt() =
    runTest { assertNoForeignTexels(BETWEEN_SCALE, BETWEEN_MARGIN) }

  @Test
  fun aFrameDrawnOnWholePixelsHoldsNoColorFromTheFrameBesideIt() =
    runTest { assertNoForeignTexels(WHOLE_SCALE, WHOLE_MARGIN) }

  // A lossless WebP is read through a bitmap decoded with no premultiplying, so its codes come back as stored at an
  // alpha of 1 as at 254. The same bitmap is then drawn, which must blend the stored color and not a premultiplied one.
  @Test
  fun aLosslessWebpStillKeepsItsColorAtEveryAlpha() =
    runTest {
      for (alpha in ALPHAS) {
        val argb = (alpha shl ALPHA_SHIFT) or STORED
        val overlay = webGlOverlayOf(flatLosslessWebp(STILL, argb))
        val image = overlay.source.shouldBeInstanceOf<OverlaySource.Image>()

        val codes = straightCodesOf(image.image, overlay.size)

        withClue("alpha $alpha") {
          codes.take(4).map { it.toInt() and BYTE } shouldBe
            listOf(STORED shr 16 and BYTE, STORED shr 8 and BYTE, STORED and BYTE, alpha)
        }
        assertBlend(flatLosslessWebp(STILL, argb), argb, alpha)
      }
    }

  @Test
  fun aTaggedPngStillDrawsItsStoredColorAtEveryAlpha() =
    runTest {
      for (alpha in ALPHAS) {
        val argb = (alpha shl ALPHA_SHIFT) or STORED
        assertBlend(OverlayPixels.pngOf(STILL, argb, color = PngColor(srgbIntent = 0)), argb, alpha)
      }
    }

  private suspend fun everyExportedFrameShowsItsFrame(bytes: ByteArray) {
    val overlay = overlayOf(bytes)
    val frames = exported(compositionOf(overlay))
    val timeline = timelineOf(bytes)
    val drawnStart = drawnStartOf(overlay)
    val seen = mutableSetOf<Int>()
    withClue("the file holds frames") { frames.isNotEmpty() shouldBe true }
    for ((slot, frame) in frames.withIndex()) {
      val expected = timeline.frameOnSlot(drawnStart, slot.toLong(), FRAME_RATE)
      val rect = placedRect(overlay, Size(frame.width, frame.height))
      val read =
        Pixels(frame.width, frame.height) { x, y ->
          frame.at(
            (x + HALF_PIXEL) / frame.width,
            (y + HALF_PIXEL) / frame.height,
          )
        }
      withClue("slot $slot") { indexIn(read, rect) shouldBe expected }
      seen += expected
    }
    withClue("the clip outlasts one pass, so a later frame is read after the wrap") { (seen.size > 1) shouldBe true }
  }

  private suspend fun assertNoForeignTexels(
    scale: Float,
    margin: Float,
  ) {
    val first = FLAT_FIRST
    val second = FLAT_SECOND
    val overlay =
      ImageOverlay(
        ImageSource.ofBytes(OverlayPixels.twoFrameGifOf(FLAT_CANVAS, first, second)),
        Corner.TopStart,
        margin = margin,
        scale = scale,
      )
    val preview = previewOf(overlay)
    try {
      for ((slot, colour) in listOf(0L to first, LATE_SLOT to second)) {
        val frame =
          withClue("no preview frame at slot $slot") {
            preview.frameAt(outputSlotTime(slot, FRAME_RATE)).shouldNotBeNull()
          }
        val rect = placedRect(overlay, frame.size, FLAT_CANVAS)
        val region = frame.regionIn(rect)
        val foreign =
          region.count { texel ->
            channelsOf(texel).zip(channelsOf(colour)).any { (read, own) ->
              abs(read - own) >
                FLAT_TOLERANCE
            }
          }
        withClue("slot $slot over $rect") {
          region.isNotEmpty() shouldBe true
          foreign shouldBe 0
        }
      }
    } finally {
      preview.release()
    }
  }

  // The still is laid over the blue clip, and every pixel it covers is the color a source over of the stored color at
  // that alpha gives, read off a preview before any encode.
  private suspend fun assertBlend(
    bytes: ByteArray,
    argb: Int,
    alpha: Int,
  ) {
    val overlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, margin = 0f, scale = STILL_SCALE)
    val preview = previewOf(overlay)
    try {
      val frame = preview.frameAt(outputSlotTime(0, FRAME_RATE)).shouldNotBeNull()
      val rect = placedRect(overlay, frame.size, STILL)
      val drawn = frame.at(rect.middleX.toFloat(), rect.middleY.toFloat())
      val under = frame.at(FAR, FAR)
      val over = channelsOf(argb)
      val expected =
        listOf(under.red, under.green, under.blue).mapIndexed { i, back ->
          OverlayPixels.sourceOver(
            back,
            over[i],
            alpha / 255f,
          )
        }
      withClue("alpha $alpha drew $drawn, wanted $expected") {
        listOf(drawn.red, drawn.green, drawn.blue).zip(expected).all { (read, want) ->
          abs(read - want) <=
            BLEND_TOLERANCE
        } shouldBe
          true
      }
    } finally {
      preview.release()
    }
  }

  private suspend fun webGlOverlayOf(bytes: ByteArray): WebGlOverlay {
    val spec = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
    val resolver = BuiltInEffectResolver()
    resolver.prepare(compositionOf(spec))
    val resolution = resolver.resolve(spec, WEBGL, ATTRIBUTES)
    val effect =
      when (resolution) {
        is EffectResolution.Resolved -> resolution.effect
        is EffectResolution.Degraded -> resolution.effect
        else -> throw AssertionError("the overlay did not resolve: $resolution")
      }
    return effect.overlay.shouldNotBeNull()
  }

  private suspend fun exported(composition: Composition): List<OutputFrame> {
    val engine = engine()
    val plan =
      when (val verdict = engine.plan(composition, SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
      }
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the export failed: ${it.error.message}")
    }
    val success = (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
    return decodeFrames(MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri))
  }

  private suspend fun previewOf(overlay: ImageOverlay): BrowserPreview {
    val composition = compositionOf(overlay)
    val resolved = engine().resolve(composition, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    return resolved.toBrowserPreview(composition)
  }

  private suspend fun compositionOf(overlay: ImageOverlay): Composition {
    val clip = makeClip(width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES, frameRate = FRAME_RATE, color = Rgb.Blue)
    return Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip))))),
      effects = listOf(overlay),
      audio = AudioSpec.Remove,
    )
  }

  private fun engine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  private fun overlayOf(bytes: ByteArray): ImageOverlay =
    ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, margin = 0f, scale = SCALE)

  private fun counters(): List<AnimatedPictures.Op> =
    AnimatedPictures.counterOps(COUNTER_FRAMES).mapIndexed { index, op ->
      AnimatedPictures.Op(0, 0, op.tile, delayMillis = DELAYS[index % DELAYS.size])
    }

  // The file's own timeline, which is the one the browser lays its flipbook on.
  private fun timelineOf(bytes: ByteArray): FrameTimeline =
    (animatedPictureOf(bytes) as AnimatedRead.Animated).picture.timeline

  private fun drawnStartOf(overlay: ImageOverlay): Duration =
    overlay.drawnRangeWithin(TimeRange(Duration.ZERO, CLIP_LENGTH)).start

  private fun placedRect(
    overlay: ImageOverlay,
    frame: Size,
    picture: Size = AnimatedPictures.COUNTER_CANVAS,
  ): NormalizedRect = overlay.placedOn(frame, picture).rectOn(frame)

  // The index the squares of the counter drawn in [rect] write, read at the middle of each square and told apart by
  // brightness, which a 4:2:0 encode keeps where it moves the color around them.
  private fun indexIn(
    frame: Pixels,
    rect: NormalizedRect,
  ): Int {
    val canvas = AnimatedPictures.COUNTER_CANVAS
    val stepX = rect.width * frame.width / canvas.width
    val stepY = rect.height * frame.height / canvas.height
    var index = 0
    for (bit in 0 until SQUARES) {
      val x = (rect.left * frame.width + (SQUARE_LEFT + SQUARE_STEP * bit + 1f) * stepX).toInt()
      val y = (rect.top * frame.height + (SQUARE_ROW + 1f) * stepY).toInt()
      val pixel = frame.at(x, y)
      index = (index shl 1) or (if (pixel.red + pixel.green + pixel.blue > WHITE_SUM) 1 else 0)
    }
    return index
  }

  private fun indexIn(
    frame: PreviewFrame,
    rect: NormalizedRect,
  ): Int = indexIn(frame.pixelsRead(), rect)

  // The pixels the rect covers, [EDGE] in from every side. A pixel is covered when its centre is inside the rect.
  private fun PreviewFrame.regionIn(rect: NormalizedRect): IntArray {
    val read = pixelsRead()
    val left = ceil(rect.left * size.width - HALF).toInt() + EDGE
    val top = ceil(rect.top * size.height - HALF).toInt() + EDGE
    val right = ceil(rect.right * size.width - HALF).toInt() - EDGE
    val bottom = ceil(rect.bottom * size.height - HALF).toInt() - EDGE
    return IntArray(maxOf(0, (right - left) * (bottom - top))) { index ->
      val pixel = read.at(left + index % (right - left), top + index / (right - left))
      (pixel.red shl RED_SHIFT) or (pixel.green shl GREEN_SHIFT) or pixel.blue
    }
  }

  private fun PreviewFrame.pixelsRead(): Pixels =
    Pixels(size.width, size.height) { x, y ->
      val offset = (y * size.width + x) * RGBA_CHANNELS
      Rgb(pixels[offset].toInt() and BYTE, pixels[offset + 1].toInt() and BYTE, pixels[offset + 2].toInt() and BYTE)
    }

  private fun PreviewFrame.at(
    x: Float,
    y: Float,
  ): Rgb = pixelsRead().at((x * size.width).toInt(), (y * size.height).toInt())

  private fun channelsOf(argb: Int): List<Int> =
    listOf(
      argb shr RED_SHIFT and BYTE,
      argb shr GREEN_SHIFT and BYTE,
      argb and BYTE,
    )

  private class Pixels(
    val width: Int,
    val height: Int,
    val at: (Int, Int) -> Rgb,
  )

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 256
    const val FRAME_RATE = 25
    const val CLIP_FRAMES = 75
    val CLIP_LENGTH = 3.seconds
    const val SCALE = 0.9f
    const val COUNTER_FRAMES = 12
    val DELAYS = listOf(40, 80, 120, 200)
    val CANVAS = AnimatedPictures.COUNTER_CANVAS

    // A texel of the canvas the first frame's tile does not cover.
    const val OUTSIDE_X = 60f
    const val OUTSIDE_Y = 12f

    // A point past every overlay here, which shows the clip alone however a browser decodes its blue.
    const val FAR = 0.97f

    const val BETWEEN_SCALE = 0.4625f
    const val BETWEEN_MARGIN = 0.1873f
    const val WHOLE_SCALE = 0.5f
    const val WHOLE_MARGIN = 0.1875f
    val FLAT_CANVAS = Size(32, 16)
    const val FLAT_FIRST = 0xFFE02020.toInt()
    const val FLAT_SECOND = 0xFF20C020.toInt()
    const val LATE_SLOT = 5L
    const val FLAT_TOLERANCE = 2

    val STILL = Size(16, 16)
    const val STILL_SCALE = 0.5f
    val ALPHAS = listOf(1, 64, 128, 254)
    const val STORED = 0x00E07020
    const val ALPHA_SHIFT = 24
    const val BLEND_TOLERANCE = 3

    const val EDGE = 1
    const val HALF = 0.5f
    const val HALF_PIXEL = 0.5
    const val SQUARES = 8
    const val SQUARE_LEFT = 2
    const val SQUARE_STEP = 3
    const val SQUARE_ROW = 7
    const val WHITE_SUM = 384
    const val RGBA_CHANNELS = 4
    const val BYTE = 0xFF
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8

    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, frameRate = FRAME_RATE)

    val WEBGL =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = emptySet(),
      )
    val ATTRIBUTES =
      Attributes(
        inputSize = Size(WIDTH, HEIGHT),
        outputSize = Size(WIDTH, HEIGHT),
        layoutSize = Size(WIDTH, HEIGHT),
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = FRAME_RATE.toFloat(),
        span = TimeRange(Duration.ZERO, CLIP_LENGTH),
      )
  }
}
