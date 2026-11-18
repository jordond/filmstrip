package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import dev.jordond.filmstrip.test.SpriteSheets
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.videoSampleTimesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sprite sheets drawn by media3, checked against the pixels that were written.
 *
 * Every expected cell is the one the shared [FrameTimeline] puts on the slot, never a number worked out here. media3
 * hands an overlay truncated timestamps, so a floor of the time would read cells the export never shows, and reading
 * every written frame is what says the slot snap reached the overlay.
 *
 * The flat sheet has no ring and no squares, so a texel that came from beside a cell is a color the cell does not hold.
 * The export is yuv420p, whose shared chroma moves a flat cell a few codes, so the bleed is counted within a tolerance
 * that the same sheet drawn on whole pixels measures.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportMediaTest].
 */
class AndroidSpriteSheetMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun everyFrameShowsTheCellTheTimelineNamesForOneDuration() =
    runTest(timeout = TIMEOUT) {
      val spec = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds))

      val seen = everyFrameShowsItsCell(spec)

      withClue("a sheet with this many frames shows every one of them in two seconds") {
        seen.toSet() shouldBe (0 until SpriteSheets.COLUMNS * SpriteSheets.ROWS).toSet()
      }
    }

  // The durations are uneven and the clip outlasts one pass of them, so frames are read in the middle of a cycle and
  // across the wrap into the second one.
  @Test
  fun everyFrameShowsTheCellTheTimelineNamesForUnevenDurations() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
      val spec = overlayOf(sheet)

      val seen = everyFrameShowsItsCell(spec)

      val cycle = SpriteSheets.UNEVEN.fold(Duration.ZERO) { sum, each -> sum + each }
      withClue("the clip is longer than one cycle of $cycle") { (CLIP_LENGTH > cycle) shouldBe true }
      withClue("the frames wrap back to the first cell within the clip") {
        seen.zipWithNext().any { (before, after) -> after < before } shouldBe true
      }
    }

  @Test
  fun aSheetThatPlaysOnceHoldsItsLastCell() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 100.milliseconds, plays = FramePlays.Once)
      val spec = overlayOf(sheet)

      val seen = everyFrameShowsItsCell(spec)

      seen.last() shouldBe SpriteSheets.COLUMNS * SpriteSheets.ROWS - 1
    }

  // A sheet of one frame is one fixed cell and is drawn through the plain bitmap path.
  @Test
  fun aSheetOfOneFrameDrawsItsCellOnEveryFrame() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, listOf(1.seconds))
      val spec = overlayOf(sheet)

      everyFrameShowsItsCell(spec).toSet() shouldBe setOf(0)
    }

  @Test
  fun aSheetWiderThanTheTextureSideResolvesWhenItsCellsFit() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
    val side = SpriteSheets.CELL.width

    (SpriteSheets.SHEET.width > side) shouldBe true
    BuiltInEffectResolver()
      .resolve(overlayOf(sheet), capabilities(side), ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  @Test
  fun aCellPastTheTextureSideIsRefusedByName() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
    val side = SpriteSheets.CELL.width - 1

    val refused =
      BuiltInEffectResolver()
        .resolve(overlayOf(sheet), capabilities(side), ATTRIBUTES)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldContain "$side pixels on a side"
  }

  @Test
  fun aSheetWithNoFrameRateToCountOnIsRefusedByName() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
    val unrated = ATTRIBUTES.withoutFrameRate()

    val refused =
      BuiltInEffectResolver()
        .resolve(overlayOf(sheet), capabilities(MAX_SIDE), unrated)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldContain "frame rate"
  }

  // The sheet runs through a kept PQ grade, where the squares are read as signal rather than as sRGB. Frames are read
  // in the middle of the run, not only at its ends.
  @Test
  fun aSheetOnAKeptPqGradeShowsTheCellTheTimelineNamesMidRun() =
    runTest(timeout = TIMEOUT) {
      probe.filmstrip.assumeEncodesHdr()
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
      val spec = overlayOf(sheet)

      everyFrameShowsItsCell(spec, clip = HDR_CLIP, light = HDR_LIGHT, dark = HDR_DARK, every = HDR_EVERY)
    }

  // The cells are flat colors out to their edges, so a texel drawn from beside a cell is a color the cell does not
  // hold. The sheet is drawn 9.5 times as wide as it is stored and between whole pixels, which spreads a bilinear read
  // of the cell beside it over several pixels in from the edge. Cell 5 has a neighbour on all four sides, 1, 4 and 9 on
  // three.
  @Test
  fun aCellDrawnBetweenWholePixelsHoldsNoColorFromTheCellsBesideIt() =
    runTest(timeout = TIMEOUT) {
      val regions = flatRegions(BETWEEN_WIDTH, BETWEEN_MARGIN)

      withClue("every cell is read") { regions.map { it.cell }.toSet() shouldBe BLEED_CELLS.toSet() }
      withClue("texels of a neighbour's color at tolerance $TOLERANCE, which is clean from ${cleanFrom(regions)}") {
        foreignIn(regions, TOLERANCE) shouldBe 0
      }
    }

  // The same sheet drawn ten times as wide at a whole pixel offset has every texel of a cell on whole pixels, so what
  // it holds off its own color is the encode alone. The offset is within a twentieth of a pixel of the one above, since
  // where a cell's edge falls in the encoder's blocks changes how far the encode rings off it.
  @Test
  fun aSheetDrawnOnWholePixelsIsCleanWithinTheTolerance() =
    runTest(timeout = TIMEOUT) {
      val regions = flatRegions(WHOLE_WIDTH, WHOLE_MARGIN)

      withClue("the fewest codes of tolerance that read the sheet on whole pixels clean") {
        cleanFrom(regions).shouldNotBeNull() shouldBeLessThanOrEqualTo TOLERANCE
      }
    }

  // The tolerance is no wider than what a read of the cell beside it would show. At this scale a bilinear read puts
  // about a quarter of the neighbouring cell into the pixel two in from an edge, so a fifth is the least the test
  // promises to see.
  @Test
  fun aTexelAFifthOfTheWayToANeighbouringCellReadsAsForeign() {
    for (cell in BLEED_CELLS) {
      for (beside in neighboursOf(cell)) {
        val own = SpriteSheets.colorOf(cell)
        val other = SpriteSheets.colorOf(beside)
        val mixed = OPAQUE or (mix(own, other, RED_SHIFT) or mix(own, other, GREEN_SHIFT) or mix(own, other, 0))

        withClue("cell $cell a fifth of the way to cell $beside at tolerance $TOLERANCE") {
          SpriteSheets.foreignTexels(intArrayOf(mixed), cell, TOLERANCE) shouldBe 1
        }
      }
    }
  }

  private fun neighboursOf(cell: Int): List<Int> {
    val row = cell / SpriteSheets.COLUMNS
    val column = cell % SpriteSheets.COLUMNS
    return listOf(row to column + 1, row to column - 1, row + 1 to column, row - 1 to column)
      .filter { (r, c) -> r in 0 until SpriteSheets.ROWS && c in 0 until SpriteSheets.COLUMNS }
      .map { (r, c) -> r * SpriteSheets.COLUMNS + c }
  }

  private fun mix(
    own: Int,
    other: Int,
    shift: Int,
  ): Int = ((((own shr shift and BYTE) * 4 + (other shr shift and BYTE)) / 5) and BYTE) shl shift

  /**
   * Exports [spec] over [clip], reads the written frames [every] one and checks each shows the cell [FrameTimeline]
   * puts on its slot, and answers the cell of each frame read.
   */
  private suspend fun everyFrameShowsItsCell(
    spec: ImageOverlay,
    clip: String = Media3ExportProbe.CLIP,
    light: Int = SDR_LIGHT,
    dark: Int = SDR_DARK,
    every: Int = 1,
  ): List<Int> {
    val exported = export(spec, clip)
    val times = videoSampleTimesOf(exported.file)
    val seen = mutableListOf<Int>()
    withClue("the file holds frames") { times.isNotEmpty() shouldBe true }
    for ((read, time) in times.withIndex()) {
      if (read % every != 0 && read != times.lastIndex) continue
      val slot = outputSlotOf(time, exported.rate)
      val frame = probe.frame(exported.file, time)
      val rect = placedRect(spec, exported.size)
      val expected = expectedFrame(spec, exported, slot)

      withClue("slot $slot at $time squares ${squaresIn(frame, rect)}") {
        indexIn(frame, rect, light, dark) shouldBe expected
      }
      seen += expected
    }
    exported.file.delete()
    return seen
  }

  /**
   * The drawn region of a flat sheet's cell on frames from the middle of each of [BLEED_CELLS]' runs, with that cell.
   */
  private suspend fun flatRegions(
    width: Int,
    margin: Float,
  ): List<Region> {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
    val frame = probe.plan(compositionOf(overlayOf(sheet))).output.size
    val spec = flatOverlayOf(sheet, width.toFloat() / frame.width, margin)
    val exported = export(spec, spec = MEASURING)
    val rect = placedRect(spec, exported.size)
    val slots =
      videoSampleTimesOf(exported.file)
        .map { outputSlotOf(it, exported.rate) to it }
        .groupBy { (slot, _) -> expectedFrame(spec, exported, slot) }
        .filterKeys { it in BLEED_CELLS }
    val regions =
      slots.map { (cell, run) ->
        val (_, time) = run[run.size / 2]
        Region(cell, probe.frame(exported.file, time).regionIn(rect))
      }
    exported.file.delete()
    return regions
  }

  private fun foreignIn(
    regions: List<Region>,
    tolerance: Int,
  ): Int = regions.sumOf { SpriteSheets.foreignTexels(it.texels, it.cell, tolerance) }

  // The fewest codes of tolerance at which [regions] read clean, or null when none up to a quarter of the range does.
  private fun cleanFrom(regions: List<Region>): Int? = (0..64).firstOrNull { foreignIn(regions, it) == 0 }

  private suspend fun export(
    overlay: ImageOverlay,
    clip: String = Media3ExportProbe.CLIP,
    spec: ExportSpec = Media3ExportProbe.SPEC,
  ): Exported {
    val plan = probe.plan(compositionOf(overlay, clip), spec)
    val file = probe.export(plan)
    val rate = plan.output.frameRate
    return Exported(file, plan.output.size, TimeRange.of(Duration.ZERO, plan.duration), rate)
  }

  private fun compositionOf(
    spec: ImageOverlay,
    clip: String = Media3ExportProbe.CLIP,
  ): Composition = Composition(listOf(Track(listOf(Clip(probe.fixture(clip))))), listOf(spec))

  private fun overlayOf(sheet: SpriteSheet): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(SpriteSheets.png()),
      corner = Corner.TopStart,
      margin = 0f,
      scale = SCALE,
      sheet = sheet,
    )

  private fun flatOverlayOf(
    sheet: SpriteSheet,
    scale: Float,
    margin: Float,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(SpriteSheets.flatPng()),
      corner = Corner.TopStart,
      margin = margin,
      scale = scale,
      sheet = sheet,
    )

  // The sheet's own timeline, built the way the sheet builds it, since the sheet keeps its timeline to itself.
  private fun expectedFrame(
    spec: ImageOverlay,
    exported: Exported,
    slot: Long,
  ): Int {
    val sheet = checkNotNull(spec.sheet)
    val first = sheet.frameDurations.first()
    val timeline =
      if (sheet.frameDurations.all { it == first }) {
        FrameTimeline.uniform(sheet.frameCount, first, sheet.plays)
      } else {
        FrameTimeline(sheet.frameDurations, sheet.plays)
      }
    return timeline.frameOnSlot(spec.drawnRangeWithin(exported.span).start, slot, exported.rate)
  }

  private fun placedRect(
    spec: ImageOverlay,
    frame: Size,
  ): NormalizedRect = spec.placedOn(frame, SpriteSheets.CELL).rectOn(frame)

  // The index the squares of the cell drawn in [rect] write, read at the middle of each texel and snapped to black or
  // white. The chroma of a yuv420p frame is shared by two pixels, so a square takes some color from the cell around it
  // and is told apart by its brightness, a texel whose three channels average above [light] being white and one under
  // [dark] black.
  private fun indexIn(
    frame: Bitmap,
    rect: NormalizedRect,
    light: Int,
    dark: Int,
  ): Int? {
    val cell = SpriteSheets.CELL
    val left = rect.left * frame.width
    val top = rect.top * frame.height
    val stepX = rect.width * frame.width / cell.width
    val stepY = rect.height * frame.height / cell.height
    val texels =
      IntArray(cell.width * cell.height) { at ->
        val x = (left + (at % cell.width + 0.5f) * stepX).toInt().coerceIn(0, frame.width - 1)
        val y = (top + (at / cell.width + 0.5f) * stepY).toInt().coerceIn(0, frame.height - 1)
        val argb = frame.getPixel(x, y)
        val level = ((argb shr RED_SHIFT and BYTE) + (argb shr GREEN_SHIFT and BYTE) + (argb and BYTE)) / 3
        when {
          level < dark -> BLACK
          level > light -> WHITE
          else -> argb
        }
      }
    return runCatching { SpriteSheets.indexOf(texels) }.getOrNull()
  }

  // The first texel of each square the index is written in, as the frame holds it.
  private fun squaresIn(
    frame: Bitmap,
    rect: NormalizedRect,
  ): List<String> =
    List(SQUARES) { bit ->
      val x = rect.left * frame.width + (SQUARE_LEFT + SQUARE_STEP * bit + 0.5f) * rect.width * frame.width / 32
      val y = rect.top * frame.height + (SQUARE_ROW + 0.5f) * rect.height * frame.height / 16
      frame.getPixel(x.toInt(), y.toInt()).toUInt().toString(16)
    }

  // The pixels inside [rect], two pixels in from every edge. The edge rounds to a pixel and the chroma of a yuv420p
  // frame is shared by two, so a pixel nearer than that holds some of the video beside the cell.
  private fun Bitmap.regionIn(rect: NormalizedRect): IntArray {
    val left = (rect.left * width).roundToInt() + EDGE
    val top = (rect.top * height).roundToInt() + EDGE
    val right = (rect.right * width).roundToInt() - EDGE
    val bottom = (rect.bottom * height).roundToInt() - EDGE
    val texels = IntArray((right - left) * (bottom - top))
    getPixels(texels, 0, right - left, left, top, right - left, bottom - top)
    return texels
  }

  private fun capabilities(maxTextureSize: Int): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.OpenGlEs,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxTextureSize,
      features = emptySet(),
    )

  private fun Attributes.withoutFrameRate(): Attributes =
    Attributes(
      inputSize = inputSize,
      outputSize = outputSize,
      layoutSize = layoutSize,
      colorSpace = colorSpace,
      hdrTransfer = hdrTransfer,
      frameRate = null,
      span = span,
    )

  private class Exported(
    val file: File,
    val size: Size,
    val span: TimeRange,
    val rate: Int,
  )

  private class Region(
    val cell: Int,
    val texels: IntArray,
  )

  private companion object {
    val CLIP_LENGTH = 2.seconds

    // The output the other exports make, at the bitrate a pixel read is measured at so the encode moves a flat cell
    // less.
    val MEASURING = ExportSpec(heightPx = 240, bitrate = PREVIEW_PARITY_EXPORT_BITRATE)
    const val SCALE = 0.25f
    const val MAX_SIDE = 4096

    // The widths a flat sheet is drawn at, in pixels. A cell is 32 texels, so 304 is 9.5 pixels a texel and 320 is 10.
    const val BETWEEN_WIDTH = 304
    const val WHOLE_WIDTH = 320
    const val BETWEEN_MARGIN = 0.1873f
    const val WHOLE_MARGIN = 0.1875f

    // What a white and a black square read on an sRGB frame and on a PQ frame, whose white is a signal of about 0.58.
    const val SDR_LIGHT = 160
    const val SDR_DARK = 96
    const val HDR_LIGHT = 90
    const val HDR_DARK = 40
    const val HDR_EVERY = 3

    // A little over the codes the whole pixel sheet needs to read clean at the measuring bitrate, which is 9 on API 30
    // and 7 on API 36. Cells drawn between pixels need the same, 9 and 6. It is less than the fifth of a neighbour the
    // test after them says it still sees.
    const val TOLERANCE = 12
    val BLEED_CELLS = listOf(5, 9, 1, 4)

    const val EDGE = 2
    const val SQUARES = 8
    const val SQUARE_LEFT = 2
    const val SQUARE_STEP = 3
    const val SQUARE_ROW = 7
    const val BYTE = 0xFF
    const val OPAQUE = 0xFF000000.toInt()
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    val ATTRIBUTES =
      Attributes(
        inputSize = Size(320, 180),
        outputSize = Size(320, 180),
        layoutSize = Size(320, 180),
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
  }
}
