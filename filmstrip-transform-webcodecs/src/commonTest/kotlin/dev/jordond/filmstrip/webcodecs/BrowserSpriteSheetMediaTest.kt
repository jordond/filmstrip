package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.test.SpriteSheets
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sprite sheets drawn into a real browser export and a preview.
 *
 * Every expected cell is the one the shared [FrameTimeline] puts on the slot, never a number worked out here. The
 * index of the cell an export wrote is read off the squares it is written in, through the encode.
 *
 * Bleed is read off the preview, which composites through the export's own compositor and hands its pixels back before
 * any encode, so the flat cells need no tolerance for one. A sheet drawn on whole pixels is read as well as one between
 * them. A drawn cell is read in from its edges by [EDGE] pixel, since the edge itself is the rect and not the cell.
 */
class BrowserSpriteSheetMediaTest {
  @Test
  fun everyExportedFrameShowsTheCellTheTimelineNamesForOneDuration() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME)

      val seen = everyFrameShowsItsCell(overlayOf(sheet))

      withClue("a sheet this long shows every one of its frames") {
        seen.toSet() shouldBe (0 until SpriteSheets.COLUMNS * SpriteSheets.ROWS).toSet()
      }
    }

  // The durations are uneven and the clip outlasts one pass of them, so frames are read in the middle of a cycle and
  // across the wrap into the second one.
  @Test
  fun everyExportedFrameShowsTheCellTheTimelineNamesForUnevenDurations() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)

      val seen = everyFrameShowsItsCell(overlayOf(sheet))

      val cycle = SpriteSheets.UNEVEN.fold(Duration.ZERO) { sum, each -> sum + each }
      withClue("the clip of $CLIP_LENGTH is longer than one cycle of $cycle") { (CLIP_LENGTH > cycle) shouldBe true }
      withClue("the frames wrap back to the first cell within the clip") {
        seen.zipWithNext().any { (before, after) -> after < before } shouldBe true
      }
    }

  @Test
  fun aSheetThatPlaysOnceHoldsItsLastCell() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME, plays = FramePlays.Once)

      everyFrameShowsItsCell(overlayOf(sheet)).last() shouldBe SpriteSheets.COLUMNS * SpriteSheets.ROWS - 1
    }

  // The stored picture is turned a quarter, so a cell is cut from the shown sheet and only reads upright squares if the
  // pass carries the cell through the orientation.
  @Test
  fun aTurnedSheetShowsTheCellTheTimelineNamesUpright() =
    runTest {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME)
      val turned = overlayOf(sheet, ImageSource.ofBytes(SpriteSheets.orientedPng()))

      val seen = everyFrameShowsItsCell(turned)

      withClue("a sheet this long shows every one of its frames") {
        seen.toSet() shouldBe (0 until SpriteSheets.COLUMNS * SpriteSheets.ROWS).toSet()
      }
    }

  // The preview is seeked forward and then back to a slot it showed before, which is drawn from the composition time
  // alone, so it shows the cell the timeline names for that slot and not the one it showed last.
  @Test
  fun aPreviewSeekedBackShowsTheCellTheTimelineNamesForThatSlot() =
    runTest {
      val overlay = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN))
      val timeline = timelineOf(overlay.sheet.shouldNotBeNull())
      val drawnStart = drawnStartOf(overlay)
      val preview = previewOf(overlay)
      try {
        val slots = listOf(40L, 3L, 29L, 12L, 0L, 33L, 7L)
        val seen = mutableListOf<Int>()
        for (slot in slots) {
          val time = outputSlotTime(slot, FRAME_RATE)
          val frame = withClue("no preview frame at $time") { preview.frameAt(time).shouldNotBeNull() }
          val expected = timeline.frameOnSlot(drawnStart, slot, FRAME_RATE)
          withClue("slot $slot at $time") { indexIn(frame.read(), placedRect(overlay, frame.size)) shouldBe expected }
          seen += expected
        }
        withClue("the seeks land on more than one cell") { (seen.toSet().size > 1) shouldBe true }
      } finally {
        preview.release()
      }
    }

  // The cells are flat colors out to their edges, so a texel drawn from beside a cell is a color the cell does not
  // hold. The sheet is drawn 3.6875 pixels to a texel and between whole pixels, which spreads a bilinear read of the
  // cell beside it over the pixels in from the edge. Cell 5 has a neighbour on all four sides.
  @Test
  fun aCellDrawnBetweenWholePixelsHoldsNoColorFromTheCellsBesideIt() =
    runTest {
      val regions = flatRegions(BETWEEN_SCALE, BETWEEN_MARGIN)

      withClue("every cell is read") {
        regions.map { it.cell }.toSet() shouldBe (0 until SpriteSheets.COLUMNS * SpriteSheets.ROWS).toSet()
      }
      withClue("texels of a neighbour's color in ${regions.filter { foreignIn(it) > 0 }.map { it.cell }}") {
        regions.sumOf { foreignIn(it) } shouldBe 0
      }
    }

  // The same sheet at a whole number of pixels to a texel and a whole pixel offset. A pixel centre then falls a quarter
  // of a texel in from a cell's edge, so an unclamped read blends the cell beside it there too.
  @Test
  fun aSheetDrawnOnWholePixelsHoldsNoColorFromTheCellsBesideIt() =
    runTest {
      val regions = flatRegions(WHOLE_SCALE, WHOLE_MARGIN)

      val rect = placedRect(flatOverlayOf(WHOLE_SCALE, WHOLE_MARGIN), Size(WIDTH, HEIGHT))
      withClue("the sheet sits on whole pixels at $rect") {
        listOf(rect.left * WIDTH, rect.top * HEIGHT, rect.width * WIDTH, rect.height * HEIGHT)
          .all { abs(it - it.roundToInt()) < WHOLE_PIXEL } shouldBe true
      }
      regions.sumOf { foreignIn(it) } shouldBe 0
    }

  /**
   * Exports [overlay] over a clip, reads every written frame and checks each shows the cell [FrameTimeline] puts on
   * its slot, and answers the cell of each frame.
   */
  private suspend fun everyFrameShowsItsCell(overlay: ImageOverlay): List<Int> {
    val frames = exported(compositionOf(overlay))
    val timeline = timelineOf(overlay.sheet.shouldNotBeNull())
    val drawnStart = drawnStartOf(overlay)
    val seen = mutableListOf<Int>()
    withClue("the file holds frames") { frames.isNotEmpty() shouldBe true }
    for ((slot, frame) in frames.withIndex()) {
      val expected = timeline.frameOnSlot(drawnStart, slot.toLong(), FRAME_RATE)
      val rect = placedRect(overlay, Size(frame.width, frame.height))

      withClue("slot $slot") { indexIn(frame.read(), rect) shouldBe expected }
      seen += expected
    }
    return seen
  }

  /**
   * The drawn region of every cell of a flat sheet, read off a preview frame from the middle of that cell's run.
   */
  private suspend fun flatRegions(
    scale: Float,
    margin: Float,
  ): List<Region> {
    val overlay = flatOverlayOf(scale, margin)
    val timeline = timelineOf(overlay.sheet.shouldNotBeNull())
    val drawnStart = drawnStartOf(overlay)
    val slotsOf =
      (0L until CLIP_FRAMES)
        .groupBy { timeline.frameOnSlot(drawnStart, it, FRAME_RATE) }
        .mapValues { (_, slots) -> slots[slots.size / 2] }
    val preview = previewOf(overlay)
    try {
      return slotsOf.map { (cell, slot) ->
        val time = outputSlotTime(slot, FRAME_RATE)
        val frame = withClue("no preview frame at $time") { preview.frameAt(time).shouldNotBeNull() }
        Region(cell, frame.read().regionIn(placedRect(overlay, frame.size)))
      }
    } finally {
      preview.release()
    }
  }

  private fun foreignIn(region: Region): Int = SpriteSheets.foreignTexels(region.texels, region.cell)

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
    val clip =
      makeClip(width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES.toInt(), frameRate = FRAME_RATE, color = Rgb.Blue)
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

  private fun overlayOf(
    sheet: SpriteSheet,
    image: ImageSource = ImageSource.ofBytes(SpriteSheets.png()),
  ): ImageOverlay = ImageOverlay(image, Corner.TopStart, margin = 0f, scale = SCALE, sheet = sheet)

  private fun flatOverlayOf(
    scale: Float,
    margin: Float,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(SpriteSheets.flatPng()),
      corner = Corner.TopStart,
      margin = margin,
      scale = scale,
      sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SHEET_FRAME),
    )

  // The sheet keeps its timeline to itself, so it is built here the way the sheet builds it.
  private fun timelineOf(sheet: SpriteSheet): FrameTimeline {
    val first = sheet.frameDurations.first()
    return if (sheet.frameDurations.all { it == first }) {
      FrameTimeline.uniform(sheet.frameCount, first, sheet.plays)
    } else {
      FrameTimeline(sheet.frameDurations, sheet.plays)
    }
  }

  private fun drawnStartOf(overlay: ImageOverlay): Duration =
    overlay.drawnRangeWithin(TimeRange(Duration.ZERO, CLIP_LENGTH)).start

  private fun placedRect(
    overlay: ImageOverlay,
    frame: Size,
  ): NormalizedRect = overlay.placedOn(frame, SpriteSheets.CELL).rectOn(frame)

  // The index the squares of the cell drawn in [rect] write, read at the middle of each square and told apart by
  // brightness, which a 4:2:0 encode keeps where it moves the color around them.
  private fun indexIn(
    frame: Pixels,
    rect: NormalizedRect,
  ): Int {
    val cell = SpriteSheets.CELL
    val stepX = rect.width * frame.width / cell.width
    val stepY = rect.height * frame.height / cell.height
    var index = 0
    for (bit in 0 until SQUARES) {
      val x = (rect.left * frame.width + (SQUARE_LEFT + SQUARE_STEP * bit + 1f) * stepX).toInt()
      val y = (rect.top * frame.height + (SQUARE_ROW + 1f) * stepY).toInt()
      val pixel = frame.at(x, y)
      val white = pixel.red + pixel.green + pixel.blue > WHITE_SUM
      index = (index shl 1) or (if (white) 1 else 0)
    }
    return index
  }

  // The pixels the rect covers, [EDGE] in from every side. A pixel is covered when its centre is inside the rect.
  private fun Pixels.regionIn(rect: NormalizedRect): IntArray {
    val left = ceil(rect.left * width - HALF).toInt() + EDGE
    val top = ceil(rect.top * height - HALF).toInt() + EDGE
    val right = ceil(rect.right * width - HALF).toInt() - EDGE
    val bottom = ceil(rect.bottom * height - HALF).toInt() - EDGE
    return IntArray((right - left) * (bottom - top)) { index ->
      val pixel = at(left + index % (right - left), top + index / (right - left))
      (pixel.red shl RED_SHIFT) or (pixel.green shl GREEN_SHIFT) or pixel.blue
    }
  }

  private fun OutputFrame.read(): Pixels =
    Pixels(width, height) { x, y -> at((x + HALF_PIXEL) / width, (y + HALF_PIXEL) / height) }

  private fun PreviewFrame.read(): Pixels =
    Pixels(size.width, size.height) { x, y ->
      val offset = (y * size.width + x) * RGBA_CHANNELS
      Rgb(pixels[offset].toInt() and BYTE, pixels[offset + 1].toInt() and BYTE, pixels[offset + 2].toInt() and BYTE)
    }

  private class Pixels(
    val width: Int,
    val height: Int,
    val at: (Int, Int) -> Rgb,
  )

  private class Region(
    val cell: Int,
    val texels: IntArray,
  )

  private companion object {
    const val WIDTH = 256
    const val HEIGHT = 128
    const val FRAME_RATE = 25
    const val CLIP_FRAMES = 50L
    val CLIP_LENGTH = 2.seconds
    val SHEET_FRAME = 150.milliseconds
    const val SCALE = 0.5f

    // A cell is 32 texels, so 0.4625 of the frame is 118 pixels, 3.6875 a texel, and 0.5 is 128, 4 a texel. The margin
    // is a fraction of the frame's shorter side, 128 pixels, so 0.1875 is 24 whole pixels and 0.1873 is not.
    const val BETWEEN_SCALE = 0.4625f
    const val BETWEEN_MARGIN = 0.1873f
    const val WHOLE_SCALE = 0.5f
    const val WHOLE_MARGIN = 0.1875f
    const val WHOLE_PIXEL = 0.01f

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
  }
}
