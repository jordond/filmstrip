package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Film] drawn through the real browser compositor, read against [FilmSignal].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Film], so what the fixture's encoder and the decoder did to it is in
 * the expected value as well.
 */
class BrowserFilmMediaTest {
  @Test
  fun aCompositionFilmLandsOnTheSignalAtAMidStrength() =
    runTest {
      val film = Film()
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipFilmLandsOnTheSignalAtAMidStrength() =
    runTest {
      val film = Film()
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(film))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun grainAloneLandsOnTheSignalOverTheWholePicture() =
    runTest {
      val film = Film(grain = MID, halation = 0f)
      val clip = greyClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      withClue("the grain has to move the frame for this to tell") {
        (frame.pixels.contentEquals(picture.pixels)) shouldBe false
      }
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun halationAloneGlowsPastTheBrightEdgeAndStopsWithItsSigma() =
    runTest {
      val film = Film(grain = 0f, halation = MID)
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      val y = frame.size.height / 2
      val edge = frame.size.width / 2
      val sigma = FilmSignal.halationBlurPlan(frame.size.width).sigmaPx
      val lift = { x: Int -> frame.pixelAt(x, y).red - picture.pixelAt(x, y).red }
      withClue("one sigma off the edge reads ${lift(edge + sigma.toInt())}") {
        lift(edge + sigma.toInt()).toDouble() shouldBeGreaterThan GLOW_CODES
      }
      withClue("the far end of the dark side reads ${lift(frame.size.width - 1)}") {
        lift(frame.size.width - 1) shouldBe 0
      }
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameLookAtItsOwnSize() =
    runTest {
      val film = Film()
      val clip = edgeClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(film)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aLargeFrameBlursAtThePlansSmallerSize() =
    runTest {
      val film = Film()
      val clip = edgeClip(LARGE_WIDTH, LARGE_HEIGHT)

      val frame = drawn(edit(Clip(clip), listOf(film)))
      val picture = drawn(edit(Clip(clip)))

      withClue("the plan has to scale for this to tell") {
        (FilmSignal.halationBlurPlan(frame.size.width).factor > 1) shouldBe true
      }
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun a1080pFrameBlursAtAFactorPastTwo() =
    runTest {
      withClue("the plan has to scale past two for this to tell") {
        (FilmSignal.halationBlurPlan(FULL_HD_WIDTH).factor > 2) shouldBe true
      }
      assertGlowLands(FULL_HD_WIDTH, FULL_HD_HEIGHT)
    }

  @Test
  fun aFrameItsFactorDoesNotDivideBlursAtThePlansSize() =
    runTest {
      withClue("the factor has to leave a remainder for this to tell") {
        (UNEVEN_WIDTH % FilmSignal.halationBlurPlan(UNEVEN_WIDTH).factor != 0) shouldBe true
      }
      assertGlowLands(UNEVEN_WIDTH, UNEVEN_HEIGHT)
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() =
    runTest {
      val composition = edit(Clip(edgeClip()), listOf(Film()))
      val preview = previewOf(composition, SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        withClue("the grain moves between the two times") {
          elsewhere.pixels.contentEquals(first.pixels) shouldBe false
        }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun anExportWritesTheGlowAPreviewDraws() =
    runTest {
      val film = Film(grain = 0f, halation = 1f)
      val composition = edit(Clip(edgeClip()), listOf(film))
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          filters()
        }

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()
      val success = statuses.filterIsInstance<ExportStatus.Success>().single()
      val written = decodeFrames(outputOf(success))[READ_SLOT.toInt()]
      val drawn = drawn(composition)

      val y = (written.height / 2 + 0.5) / written.height
      val x = (written.width / 2 + GLOW_READ_COLUMNS + 0.5) / written.width
      abs(written.at(x, y).red - drawn.at(x, y).red).toDouble() shouldBeLessThan EXPORT_CODES
    }

  @Test
  fun aClipFilmReadsAToneMappedFrame() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest
      val film = Film(grain = MID, halation = 0f)
      val clip = makeHdrClip(HdrTransfer.Pq, HDR_WIDTH, HDR_HEIGHT, FRAMES, FRAME_RATE) { HDR_NITS }
      val source = MediaSource.Bytes(clip)
      val toneMap = ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.ToneMapToSdr)

      val frame = drawn(edit(Clip(source, effects = listOf(film))), toneMap)
      val picture = drawn(edit(Clip(source)), toneMap)

      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aCompositionFilmReachesTheBars() =
    runTest {
      val film = Film(grain = 1f, halation = 0f)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val fill = Fill.Solid(SLATE_ARGB)

      val frame = drawn(edit(Clip(greyClip()), listOf(square, film), fill))
      val picture = drawn(edit(Clip(greyClip()), listOf(square), fill))

      val row = (BAR_ROW_OF_HEIGHT * frame.size.height).toInt()
      withClue("the grain lands on the bar") {
        (0 until frame.size.width).any { frame.pixelAt(it, row).red != picture.pixelAt(it, row).red } shouldBe true
      }
      worstCodes(frame, picture, film, rows = listOf(row)) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aCompositionFilmGlowsFromThePictureIntoABar() =
    runTest {
      val film = Film(grain = 0f, halation = 1f)
      val wide = Crop(AspectRatio(21, 9), Fit.Contain)
      val bright = brightClip()
      val fill = Fill.Solid(BLACK_ARGB)

      val frame = drawn(edit(Clip(bright), listOf(wide, film), fill))
      val picture = drawn(edit(Clip(bright), listOf(wide), fill))

      withClue("the frame has to have bars on its sides for this to tell") {
        picture.pixelAt(0, frame.size.height / 2).red shouldBe 0
      }
      val y = frame.size.height / 2
      val pictureLeft = (0 until frame.size.width).first { picture.pixelAt(it, y).red > BAR_BLACK_CODES }
      val plan = FilmSignal.halationBlurPlan(frame.size.width)
      val inBar = pictureLeft - (plan.sigmaPx * plan.factor).toInt()
      withClue("one sigma into the bar reads ${frame.pixelAt(inBar, y).red}") {
        frame.pixelAt(inBar, y).red.toDouble() shouldBeGreaterThan GLOW_CODES
      }
      worstCodes(frame, picture, film, rows = listOf(y), columns = listOf(inBar)) shouldBeLessThan SIGNAL_CODES
      worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipFilmLeavesACanvasBarClearOverTheFill() =
    runTest {
      val film = Film(grain = 1f, halation = 1f)
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> BRIGHT })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, film)), fill = Fill.Solid(SLATE_ARGB)))

      val width = frame.size.width
      val height = frame.size.height
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val justOutside = (contentRight * width).toInt() + 2
      withClue("a bar two pixels off a bright picture holds the fill, with no glow and no grain") {
        frame.pixelAt(justOutside, height / 2).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("the far end of the bar holds the fill") {
        frame.pixelAt(width - 1, height / 4).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
    }

  @Test
  fun aClipFilmLeavesABlurredFillsCanvasBarsBlack() =
    runTest {
      val film = Film(grain = 1f, halation = 1f)
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> BRIGHT })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, film)), fill = Fill.Blurred()))

      val width = frame.size.width
      val height = frame.size.height
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val justOutside = (contentRight * width).toInt() + 2
      frame.pixelAt(justOutside, height / 2).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val frame = drawn(edit(Clip(greyClip()), listOf(swatch(), Film(grain = 1f, halation = 1f))))

      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  @Test
  fun aClipOverlayUnderACompositionFilmIsBurned() =
    runTest {
      val film = Film(grain = 1f, halation = 0f)
      val frame = drawn(edit(Clip(greyClip(), effects = listOf(swatch())), listOf(film)))

      val width = frame.size.width
      val height = frame.size.height
      val x = (SWATCH_X * width).toInt()
      val y = (SWATCH_Y * height).toInt()
      val code = SWATCH_CODE / FULL
      val expected =
        FilmSignal.grained(code, code, code, FilmSignal.grainCell(x, y, width, height), TICK, FilmParams.of(film))

      abs(frame.pixelAt(x, y).red / FULL - expected[0]) * FULL shouldBeLessThan SIGNAL_CODES
    }

  private suspend fun assertGlowLands(
    width: Int,
    height: Int,
  ) {
    val film = Film(grain = 0f, halation = MID)
    val clip = edgeClip(width, height)

    val frame = drawn(edit(Clip(clip), listOf(film)))
    val picture = drawn(edit(Clip(clip)))

    frame.size.width shouldBe width
    worstCodes(frame, picture, film) shouldBeLessThan SIGNAL_CODES
  }

  private suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(READ_TIME).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(
    composition: Composition,
    spec: ExportSpec,
  ): BrowserPreview =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(FilterEffectResolver())
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, spec)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private fun edit(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(BLACK_ARGB),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = fill,
    audio = AudioSpec.Remove,
  )

  private suspend fun edgeClip(
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(width, height, FRAMES, FRAME_RATE) { _, x, _ -> if (x < width / 2) BRIGHT else GREY },
    )

  private suspend fun greyClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })

  private suspend fun brightClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> BRIGHT })

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // The largest gap between the frame and the signal across rows and columns from edge to edge of the picture, in eight
  // bit codes, with the glow worked out from the picture with no Film on it.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    film: Film,
    rows: List<Int>? = null,
    columns: List<Int>? = null,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = FilmParams.of(film)
    val glow = HalationGlow.of(width, height) { x, y -> picture.pixelAt(x, y).channels() }
    var worst = 0.0
    (rows ?: listOf(height / 4, height / 2, height * 5 / 8, height - 1)).forEach { y ->
      val across = (0 until COLUMN_READS).map { it * (width - 1) / (COLUMN_READS - 1) }
      val nearEdge = (-EDGE_READS..EDGE_READS).map { width / 2 + it * EDGE_READ_STEP }
      (columns ?: (across + nearEdge)).forEach { x ->
        val shown = picture.pixelAt(x, y).channels()
        val cell = FilmSignal.grainCell(x, y, width, height)
        val expected = FilmSignal.pixelAt(shown[0], shown[1], shown[2], glow.at(x, y), cell, TICK, params)
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + 0.5) / size.width, (y + 0.5) / size.height)

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)
private val BRIGHT = Rgb(235, 225, 215)
private val SLATE = Rgb(0x20, 0x40, 0x60)
private val HDR_NITS = floatArrayOf(120f, 180f, 75f)

private const val MID = 0.5f
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SQUARE = 180
private const val LARGE_WIDTH = 1280
private const val LARGE_HEIGHT = 720
private const val FULL_HD_WIDTH = 1920
private const val FULL_HD_HEIGHT = 1080
private const val UNEVEN_WIDTH = 1366
private const val UNEVEN_HEIGHT = 768
private const val HDR_WIDTH = 128
private const val HDR_HEIGHT = 96
private const val SMALL_HEIGHT = 120
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val TICK: Int = FilmSignal.grainTickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
private const val COLUMN_READS = 9
private const val EDGE_READS = 8
private const val EDGE_READ_STEP = 2
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SLATE_ARGB = 0xFF204060.toInt()
private const val BAR_ROW_OF_HEIGHT = 0.05
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2
private const val GLOW_READ_COLUMNS = 3
private const val BAR_BLACK_CODES = 32

// A read that stands clear of rounding one sigma past a bright edge at a mid strength.
private const val GLOW_CODES = 2.0

// A read and the picture it is held against each round to one eight bit code, and the blur targets each hold eight
// bits under the gain. Measured at 0.99.
private const val SIGNAL_CODES = 1.5

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1

// What the encoder does to a soft glow.
private const val EXPORT_CODES = 8.0
