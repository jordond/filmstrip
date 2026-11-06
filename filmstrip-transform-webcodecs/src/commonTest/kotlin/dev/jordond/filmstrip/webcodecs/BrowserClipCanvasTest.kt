package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.regionAt
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.geometry.CanvasPlacement
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A clip whose letterboxing crop is read by a later effect, drawn through a real browser export.
 *
 * The crop makes a four by five canvas out of a sixteen by nine frame, with the content letterboxed inside it, and a
 * Ken Burns region or a composition turn then reads that canvas, bars included. Every probe is worked back through the
 * effect to a point on the canvas, and from there through the shared [CanvasPlacement.contentInCanvas] to the content
 * or to a bar, so a clip whose content is stretched over the whole canvas reads the content where a bar belongs.
 */
class BrowserClipCanvasTest {
  @Test
  fun aPanOverALetterboxedCanvasShowsTheCanvasBarsInTheFill() =
    runTest {
      val composition = single(listOf(CROP, PAN), Fill.Solid(SLATE_ARGB))
      val written = exported(composition)
      val frame = written.last()
      val placement = placementOf(SOURCE, listOf(listOf(CROP, PAN)), Size(frame.width, frame.height))

      assertCanvas(placement, frame, SLATE) { x, y -> PAN.regionAt(Duration.ZERO, SPAN).pointAt(x, y) }

      val preview = previewOf(composition)
      try {
        val drawn = preview.frameAt(outputSlotTime(written.lastIndex.toLong(), FRAME_RATE)).shouldNotBeNull()
        for ((x, y) in PROBES) {
          assertReads(frame.at(x, y), drawn.at(x, y), "the preview at ($x, $y)")
        }
      } finally {
        preview.release()
      }
    }

  // Under a blurred fill the canvas bars are black, part of the picture the clip's cover is blurred from, the way every
  // backend builds them. The canvas fills the frame here, so nothing of the blur shows.
  @Test
  fun aPanOverALetterboxedCanvasShowsBlackCanvasBarsUnderABlurredFill() =
    runTest {
      val frame = exported(single(listOf(CROP, PAN), Fill.Blurred())).last()
      val placement = placementOf(SOURCE, listOf(listOf(CROP, PAN)), Size(frame.width, frame.height))

      assertCanvas(placement, frame, Rgb.Black) { x, y -> PAN.regionAt(Duration.ZERO, SPAN).pointAt(x, y) }
    }

  // A clip's grade stops at the content, so a bar stays black under a blurred fill.
  @Test
  fun aClipGradeLeavesABlurredFillsCanvasBarsBlack() =
    runTest {
      val frame = exported(single(listOf(CROP, PAN, Invert()), Fill.Blurred())).last()
      val placement = placementOf(SOURCE, listOf(listOf(CROP, PAN)), Size(frame.width, frame.height))
      val canvas = placement.canvases.single().contentInCanvas
      val contentOf = { x: Double, y: Double ->
        val (canvasX, canvasY) = PAN.regionAt(Duration.ZERO, SPAN).pointAt(x, y)
        canvas.contentPointOf(canvasX, canvasY)
      }

      val (bars, content) = PROBES.partition { (x, y) -> contentOf(x, y) == null }
      bars.isEmpty() shouldBe false
      bars.forEach { (x, y) -> assertReads(Rgb.Black, frame.at(x, y), "bar ($x, $y)") }
      content.forEach { (x, y) ->
        val source = quadrantAt(contentOf(x, y).shouldNotBeNull())
        val inverted = Rgb(FULL_CODE - source.red, FULL_CODE - source.green, FULL_CODE - source.blue)
        assertReads(inverted, frame.at(x, y), "content ($x, $y)")
      }
    }

  // The composition's turn runs after the clip's crop, so it turns the canvas and its bars with it. One
  // counter-clockwise quarter turn brings the canvas's right edge to the top.
  @Test
  fun aCompositionTurnTurnsTheCanvasBarsWithTheContent() =
    runTest {
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(quadrantClip()), effects = listOf(CROP))))),
          effects = listOf(Rotate(QUARTER_TURN)),
          audio = AudioSpec.Remove,
          fill = Fill.Solid(SLATE_ARGB),
        )
      val frame = exported(composition).last()
      val stages = listOf(listOf<EffectSpec>(CROP), listOf(Rotate(QUARTER_TURN)))
      val placement = placementOf(SOURCE, stages, Size(frame.width, frame.height))

      assertCanvas(placement, frame, SLATE, TURNED_PROBES) { x, y -> (1.0 - y) to x }
    }

  // An overlay reads the canvas, so one in the corner is drawn on the square canvas, up in its top bar, rather than on
  // the content below it. A pan between the crop and the overlay moves the canvas under it but not the overlay.
  @Test
  fun aClipOverlayAfterALetterboxingCropLandsOnTheCanvasWithOrWithoutAPan() =
    runTest {
      listOf(listOf(SQUARE, MARK), listOf(SQUARE, PAN, MARK)).forEach { effects ->
        val frame = exported(single(effects, Fill.Solid(SLATE_ARGB))).last()
        val size = Size(frame.width, frame.height)
        val marked = MARK.placedOn(size, MARK_PICTURE).rectOn(size)
        val content = placementOf(SOURCE, listOf(listOf(SQUARE)), size).drawnRect
        withClue("the overlay has to sit in the canvas's top bar for this to tell") {
          (marked.bottom < content.top) shouldBe true
        }
        val x = (marked.left + marked.right) / 2.0
        val y = (marked.top + marked.bottom) / 2.0

        assertReads(MARK_RGB, frame.at(x, y), "the overlay at ($x, $y) after ${effects.size - 1} effects")
      }
    }

  // The composition's turn reads the clip's square canvas, and its crop then makes a portrait canvas around the turned
  // square, so the content keeps the source's aspect turned on its side rather than stretching to either canvas.
  @Test
  fun aCanvasTurnedAndCroppedAgainKeepsTheContentsAspect() =
    runTest {
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(quadrantClip()), effects = listOf(SQUARE))))),
          effects = listOf(Rotate(QUARTER_TURN), TALL),
          audio = AudioSpec.Remove,
          fill = Fill.Solid(SLATE_ARGB),
        )
      val frame = exported(composition).last()
      val size = Size(frame.width, frame.height)
      val placement = placementOf(SOURCE, listOf(listOf(SQUARE), listOf(Rotate(QUARTER_TURN), TALL)), size)
      val square = placement.drawnRect
      // Turned, the content's height on the canvas runs across the frame.
      val content = placement.canvases.single().contentInCanvas
      val aspect = content.height * square.width * size.width / (content.width * square.height * size.height)
      withClue("the turned content is ${SOURCE.height} by ${SOURCE.width}, and the placement made it $aspect") {
        abs(aspect - SOURCE.height.toFloat() / SOURCE.width) shouldBeLessThan ASPECT_SLACK
      }

      assertCanvas(placement, frame, SLATE, NESTED_PROBES) { x, y ->
        val u = (x - square.left) / square.width
        val v = (y - square.top) / square.height
        (1.0 - v) to u
      }
    }

  // On a kept grade the composite runs in linear light, so the canvas bars carry the fill's own signal on either
  // transfer.
  @Test
  fun aPanOverALetterboxedCanvasShowsTheCanvasBarsOnAKeptGrade() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        withClue("$transfer") {
          val bytes = makeHdrClip(transfer, SOURCE.width, SOURCE.height, frames = FRAMES) { CONTENT_NITS }
          val composition =
            EditComposition(
              tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(CROP, PAN))))),
              audio = AudioSpec.Remove,
              fill = Fill.Solid(SLATE_ARGB),
            )
          val spec = ExportSpec(bitrate = MEASURING_BITRATE, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
          val frame =
            decodeTenBitFrames(outputOf(exportOf(composition, MediaSink.Uri(""), spec)), transfer)
              .lastOrNull()
              .shouldNotBeNull()
          val placement = placementOf(SOURCE, listOf(listOf(CROP, PAN)), Size(frame.width, frame.height))
          val bar = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(SLATE_ARGB)))[0]
          val content = tenBitCodesFromSignal(transfer.pictureSignalOf(CONTENT_NITS))[0]

          val reads =
            PROBES.map { (x, y) ->
              val (canvasX, canvasY) = PAN.regionAt(Duration.ZERO, SPAN).pointAt(x, y)
              val inBar =
                placement.canvases
                  .single()
                  .contentInCanvas
                  .contentPointOf(canvasX, canvasY) == null
              val expected = if (inBar) bar else content
              val read = frame.lumaAt(x, y)
              val what = if (inBar) "a bar" else "content"
              withClue("luma at ($x, $y) was $read, and the shared placement puts $what there") {
                abs(expected - read) shouldBeLessThanOrEqual LUMA_TOLERANCE
              }
              inBar
            }
          reads shouldContain true
          reads shouldContain false
        }
      }
    }

  // Reads every probe against the shared placement, through [toCanvas], and checks the probes reach a bar and the
  // content both, so a placement that moved them all to one side would fail rather than pass on nothing.
  private fun assertCanvas(
    placement: ClipPlacement,
    frame: OutputFrame,
    bar: Rgb,
    probes: List<Pair<Double, Double>> = PROBES,
    toCanvas: (Double, Double) -> Pair<Double, Double>,
  ) {
    val canvas = placement.canvases.single()
    val reads =
      probes.map { (x, y) ->
        val (canvasX, canvasY) = toCanvas(x, y)
        val content = canvas.contentInCanvas.contentPointOf(canvasX, canvasY)
        assertReads(content?.let(::quadrantAt) ?: bar, frame.at(x, y), "($x, $y), canvas ($canvasX, $canvasY)")
        content == null
      }
    reads shouldContain true
    reads shouldContain false
  }

  private suspend fun exported(composition: EditComposition): List<OutputFrame> =
    decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""))))

  private suspend fun previewOf(composition: EditComposition) =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None))
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private suspend fun single(
    effects: List<EffectSpec>,
    fill: Fill,
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(quadrantClip()), effects = effects)))),
      audio = AudioSpec.Remove,
      fill = fill,
    )

  private fun assertReads(
    expected: Rgb,
    read: Rgb,
    where: String,
  ) {
    withClue("$where read $read, and $expected was expected") { read.isNear(expected) shouldBe true }
  }
}

private suspend fun quadrantClip(): ByteArray =
  makePixelClip(width = SOURCE.width, height = SOURCE.height, frames = FRAMES, frameRate = FRAME_RATE) { _, x, y ->
    quadrantAt((x + 0.5) / SOURCE.width to (y + 0.5) / SOURCE.height)
  }

private fun quadrantAt(point: Pair<Double, Double>): Rgb {
  val (x, y) = point
  return when {
    y < 0.5 && x < 0.5 -> Rgb.Red
    y < 0.5 -> Rgb.Blue
    x < 0.5 -> Rgb.Green
    else -> Rgb.Grey
  }
}

private fun NormalizedRect.pointAt(
  x: Double,
  y: Double,
): Pair<Double, Double> = (left + x * width) to (top + y * height)

// Where a canvas point lands on the content this rect places in the canvas, or null when it lands on a bar.
private fun NormalizedRect.contentPointOf(
  x: Double,
  y: Double,
): Pair<Double, Double>? {
  val contentX = (x - left) / width
  val contentY = (y - top) / height
  return if (contentX in 0.0..1.0 && contentY in 0.0..1.0) contentX to contentY else null
}

// Sixteen by nine into a four by five canvas leaves the content 0.45 of the canvas tall, and the off-centre region
// shows part of each bar while keeping every probe several pixels clear of a bar or quadrant edge.
private val SOURCE = Size(160, 90)
private val CROP = Crop(AspectRatio(4, 5), Fit.Contain)
private val REGION = NormalizedRect(0.05f, 0.1f, 0.85f, 0.9f)
private val PAN = KenBurns(REGION, REGION)
private val PROBES = listOf(0.25 to 0.1, 0.75 to 0.12, 0.25 to 0.35, 0.75 to 0.35, 0.25 to 0.65, 0.75 to 0.88)

// The turn lays the canvas bars down the frame's left and right, so these probes run across it instead.
private val TURNED_PROBES = listOf(0.1 to 0.3, 0.4 to 0.3, 0.6 to 0.7, 0.4 to 0.7, 0.9 to 0.7)

// A square canvas turned and set in a portrait one leaves a bar above and below the square and down each side of the
// content inside it, so these probes reach all of them and every quadrant.
private val SQUARE = Crop(AspectRatio.Square, Fit.Contain)
private val TALL = Crop(AspectRatio(9, 16), Fit.Contain)
private val NESTED_PROBES =
  listOf(0.5 to 0.1, 0.1 to 0.5, 0.9 to 0.5, 0.35 to 0.35, 0.65 to 0.35, 0.35 to 0.65, 0.65 to 0.65, 0.5 to 0.9)

// A placement read off a whole-pixel frame lands within this of the aspect it was asked for.
private const val ASPECT_SLACK = 0.02f

// A small flat overlay in a color no quadrant or the fill carries, set in so it sits wholly inside a square canvas's
// top bar.
private const val MARK_ARGB = 0xFFE67814.toInt()
private val MARK_RGB = Rgb(0xE6, 0x78, 0x14)
private val MARK_PICTURE = Size(16, 16)
private val MARK =
  ImageOverlay(
    ImageSource.ofBytes(OverlayPixels.pngOf(MARK_PICTURE, MARK_ARGB)),
    Corner.TopStart,
    margin = 0.02f,
    scale = 0.15f,
  )
private val SLATE = Rgb(0x20, 0x40, 0x60)
private val CONTENT_NITS = floatArrayOf(120f, 180f, 75f)

private const val SLATE_ARGB = 0xFF204060.toInt()
private const val QUARTER_TURN = 90
private const val FRAMES = 6
private const val FULL_CODE = 255
private const val FRAME_RATE = 30
private val SPAN = TimeRange(Duration.ZERO, FRAMES.seconds / FRAME_RATE)

// A 4:2:0 encode carries a couple of codes of rounding on luma.
private const val LUMA_TOLERANCE = 2
