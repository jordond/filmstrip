package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
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
import dev.jordond.filmstrip.webcodecs.internal.clipFrameError
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.stageFrameOn
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Vhs] drawn through the real browser compositor, read against [VhsSignal].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Vhs], so what the fixture's encoder and the decoder did to it is in
 * the expected value as well. Bars and swatches read the mean of the channels, since chroma noise moves one channel by
 * more than the lift a read looks for.
 */
class BrowserVhsMediaTest {
  @Test
  fun aCompositionVhsLandsOnTheSignalAtAMidIntensity() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(vhs)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, vhs) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipVhsLandsOnTheSignalAtAMidIntensity() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(vhs))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, vhs) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameTapeAtItsOwnVirtualRows() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val clip = rampClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(vhs)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, vhs) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun snowInTheMiddleOfARollingBandLandsOnTheSignal() =
    runTest {
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(SNOWY)))
      val picture = drawn(edit(Clip(clip)))
      val row = VhsSignal.virtualRow(HEIGHT / 2, HEIGHT)

      VhsSignal.snowAt(SNOWY.seed, TICK, row, MIDDLE, VhsParams.of(SNOWY)).weight shouldBeGreaterThan MID / 2.0
      worstCodes(frame, picture, SNOWY) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameSnowAtItsOwnVirtualRows() =
    runTest {
      val clip = rampClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(SNOWY)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, SNOWY) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() =
    runTest {
      val composition = edit(Clip(rampClip()), listOf(Vhs(intensity = MID)))
      val preview = previewOf(composition, SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        withClue("the tape moves between the two times") { elsewhere.pixels.contentEquals(first.pixels) shouldBe false }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun anExportWritesTheTapeAPreviewDraws() =
    runTest {
      // Full strength, so the lift stands clear of what the encoder does to a black bar.
      val vhs = Vhs()
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val composition = edit(Clip(greyClip()), listOf(square, vhs))
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          filters()
        }

      val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()
      val success = statuses.filterIsInstance<ExportStatus.Success>().single()
      val written = decodeFrames(outputOf(success))[READ_SLOT.toInt()]

      val row = VhsSignal.virtualRow((BAR_ROW_OF_HEIGHT * written.height).toInt(), written.height)
      val black = { _: Int -> doubleArrayOf(0.0, 0.0, 0.0) }
      val expected =
        VhsSignal.pixelAt(MIDDLE, row, TICK, vhs.seed, written.width, VhsParams.of(vhs), black).meanCodes()
      abs(written.at(MIDDLE, BAR_ROW_OF_HEIGHT).mean() - expected) shouldBeLessThan EXPORT_CODES
    }

  @Test
  fun aClipVhsReadsAToneMappedFrame() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest
      val vhs = Vhs(intensity = MID)
      val clip = makeHdrClip(HdrTransfer.Pq, HDR_WIDTH, HDR_HEIGHT, FRAMES, FRAME_RATE) { HDR_NITS }
      val source = MediaSource.Bytes(clip)
      val toneMap = ExportSpec(videoCodec = VideoCodec.H264, hdr = HdrMode.ToneMapToSdr)

      val frame = drawn(edit(Clip(source, effects = listOf(vhs))), toneMap)
      val picture = drawn(edit(Clip(source)), toneMap)

      worstCodes(frame, picture, vhs) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aCompositionVhsReachesTheBars() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val frame = drawn(edit(Clip(greyClip()), listOf(square, vhs), Fill.Solid(BLACK_ARGB)))

      val y = BAR_ROW_OF_HEIGHT
      val row = VhsSignal.virtualRow((y * frame.size.height).toInt(), frame.size.height)
      val black = { _: Int -> doubleArrayOf(0.0, 0.0, 0.0) }
      val expected =
        VhsSignal.pixelAt(MIDDLE, row, TICK, vhs.seed, frame.size.width, VhsParams.of(vhs), black).meanCodes()

      abs(frame.at(MIDDLE, y).mean() - expected) shouldBeLessThan FLAT_CODES
    }

  @Test
  fun aClipVhsLeavesTheCompositionBarsAlone() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val square = Crop(AspectRatio.Square, Fit.Contain)
      val frame = drawn(edit(Clip(greyClip(), effects = listOf(vhs)), listOf(square), Fill.Solid(BLACK_ARGB)))

      frame.at(MIDDLE, BAR_ROW_OF_HEIGHT).mean() shouldBeLessThan FLAT_CODES
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val frame = drawn(edit(Clip(greyClip()), listOf(swatch(), Vhs(intensity = MID))))

      abs(frame.at(SWATCH_X, SWATCH_Y).mean() - SWATCH_CODE) shouldBeLessThan FLAT_CODES
    }

  @Test
  fun aClipOverlayUnderACompositionVhsIsBurned() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val frame = drawn(edit(Clip(greyClip(), effects = listOf(swatch())), listOf(vhs)))

      val row = VhsSignal.virtualRow((SWATCH_Y * frame.size.height).toInt(), frame.size.height)
      val flat = DoubleArray(RGB) { SWATCH_CODE / FULL }
      val expected =
        VhsSignal.pixelAt(SWATCH_X, row, TICK, vhs.seed, frame.size.width, VhsParams.of(vhs)) { flat }.meanCodes()

      abs(frame.at(SWATCH_X, SWATCH_Y).mean() - expected) shouldBeLessThan FLAT_CODES
    }

  @Test
  fun aClipVhsDisplacesPictureIntoABarAndLeavesTheFillElsewhere() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> GREY })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)
      val frame = drawn(edit(Clip(square, effects = listOf(wide, vhs)), fill = Fill.Solid(SLATE_ARGB)))
      val picture = drawn(edit(Clip(square, effects = listOf(wide)), fill = Fill.Solid(SLATE_ARGB)))

      val width = frame.size.width
      val height = frame.size.height
      val params = VhsParams.of(vhs)
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val contentLeft = 1.0 - contentRight
      val bottom = height - 1
      val headRow = VhsSignal.virtualRow(bottom, height)
      val headShift = VhsSignal.rowShift(vhs.seed, TICK, headRow, params)
      val shown = picture.at(MIDDLE, MIDDLE).channels()
      val canvas = { column: Int ->
        if ((column + 0.5) / width in contentLeft..contentRight) shown else doubleArrayOf(0.0, 0.0, 0.0)
      }

      withClue("the head band moves the picture right, so it shows over the right bar") {
        val x = contentRight + headShift / 2.0
        val expected = VhsSignal.pixelAt(x, headRow, TICK, vhs.seed, width, params, canvas).meanCodes()
        abs(frame.at(x, (bottom + 0.5) / height).mean() - expected) shouldBeLessThan FLAT_CODES
      }
      withClue("what the picture left behind on that row holds the fill, noise and all") {
        val x = contentLeft + headShift / 2.0
        frame.at(x, (bottom + 0.5) / height).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("a bar on a row that hardly moves holds the fill") {
        val x = contentRight + headShift / 2.0
        frame.at(x, MIDDLE).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
    }

  @Test
  fun aClipVhsLeavesABlurredFillsCanvasBarsBlackAndMovesPictureOverThem() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> GREY })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)
      val frame = drawn(edit(Clip(square, effects = listOf(wide, vhs)), fill = Fill.Blurred()))
      val picture = drawn(edit(Clip(square, effects = listOf(wide)), fill = Fill.Blurred()))

      val width = frame.size.width
      val height = frame.size.height
      val params = VhsParams.of(vhs)
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val contentLeft = 1.0 - contentRight
      val bottom = height - 1
      val headRow = VhsSignal.virtualRow(bottom, height)
      val headShift = VhsSignal.rowShift(vhs.seed, TICK, headRow, params)
      val shown = picture.at(MIDDLE, MIDDLE).channels()
      val canvas = { column: Int ->
        if ((column + 0.5) / width in contentLeft..contentRight) shown else doubleArrayOf(0.0, 0.0, 0.0)
      }

      withClue("the head band moves the picture right, so it shows over the right bar") {
        val x = contentRight + headShift / 2.0
        val expected = VhsSignal.pixelAt(x, headRow, TICK, vhs.seed, width, params, canvas).meanCodes()
        abs(frame.at(x, (bottom + 0.5) / height).mean() - expected) shouldBeLessThan FLAT_CODES
      }
      withClue("what the picture left behind on that row is a black bar, with no lift or noise") {
        val x = contentLeft + headShift / 2.0
        frame.at(x, (bottom + 0.5) / height).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      }
      withClue("a bar on a row that hardly moves stays black") {
        val x = contentRight + headShift / 2.0
        frame.at(x, MIDDLE).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      }
    }

  @Test
  fun aClipFramePastThePagesTextureSideIsRefusedByName() =
    runTest {
      val wide = MediaSource.Bytes(makeClip(WIDE_CROP_WIDTH, WIDE_CROP_HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })
      val fitted =
        Composition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(rampClip()),
                  Clip(wide, effects = listOf(Scale(HEIGHT, Fit.Crop), Vhs(intensity = MID))),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val resolved = resolvedOf(fitted, SPEC)
      val output = resolved.output.size
      val frame =
        resolved.tracks
          .single()
          .clips
          .last()
          .stageFrameOn(output)

      withClue("the crop has to run the clip's frame past the output for this to tell") {
        (frame.width > output.width) shouldBe true
      }
      clipFrameError(resolved, frame.width) shouldBe null
      clipFrameError(resolved, frame.width - 1)
        .shouldBeInstanceOf<FilmstripError.InvalidComposition>()
        .message shouldContain "clip's own frame"
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
  ): BrowserPreview = resolvedOf(composition, spec).toBrowserPreview(composition)

  private suspend fun resolvedOf(
    composition: Composition,
    spec: ExportSpec,
  ) = webCodecsExportEngine(
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

  private suspend fun rampClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, _ ->
        val step = x * RAMP_CODES / WIDTH
        Rgb(RAMP_FLOOR + step, RAMP_MIDDLE, RAMP_FLOOR + RAMP_CODES - step)
      },
    )

  private suspend fun greyClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // The largest gap between the frame and the signal across rows and columns from edge to edge of the picture and one
  // row inside the head band, in eight bit codes, with the picture bilinear over its columns the way the sampler reads
  // it.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    vhs: Vhs,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = VhsParams.of(vhs)
    var worst = 0.0
    listOf(height / 4, height / 2, height * 5 / 8, height - 1).forEach { y ->
      val row = VhsSignal.virtualRow(y, height)
      val texel = { column: Int -> picture.at((column + 0.5) / width, (y + 0.5) / height).channels() }
      listOf(0, width / 4, width / 2, width * 3 / 4, width - 1).forEach { column ->
        val x = (column + 0.5) / width
        val expected = VhsSignal.pixelAt(x, row, TICK, vhs.seed, width, params, texel)
        val read = frame.at(x, (y + 0.5) / height).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)

  private fun Rgb.mean(): Double = (red + green + blue) / RGB.toDouble()

  private fun DoubleArray.meanCodes(): Double = sum() / size * FULL
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)
private val SLATE = Rgb(0x20, 0x40, 0x60)
private val HDR_NITS = floatArrayOf(120f, 180f, 75f)

private const val MID = 0.5f
private const val MIDDLE = 0.5
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SQUARE = 180
private const val WIDE_CROP_WIDTH = 640
private const val WIDE_CROP_HEIGHT = 120
private const val HDR_WIDTH = 128
private const val HDR_HEIGHT = 96
private const val SMALL_HEIGHT = 120
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val TICK: Int = VhsSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))

// Mid snow in a tall band that rolls through the frame once every two read times, so it sits mid frame at the read.
private val SNOWY = Vhs(tracking = Tracking.Rolling(READ_TIME * 2, height = 0.3f, snow = MID))
private const val RAMP_FLOOR = 64
private const val RAMP_CODES = 128
private const val RAMP_MIDDLE = 128
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SLATE_ARGB = 0xFF204060.toInt()
private const val BAR_ROW_OF_HEIGHT = 0.05
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A read and the picture it is held against each round to one eight bit code. Measured at 0.49.
private const val SIGNAL_CODES = 1.0

// The mean of three rounded channels on a flat area. Measured at up to 0.96.
private const val FLAT_CODES = 1.5

// A fill through an eight bit canvas. Measured at zero codes off in the mean and one in a channel.
private const val CLEAR_CODES = 1

// What the encoder does to a bar lifted by 7.6 codes at full strength, so an unlifted bar still fails. Measured at 3.9.
private const val EXPORT_CODES = 6.0
