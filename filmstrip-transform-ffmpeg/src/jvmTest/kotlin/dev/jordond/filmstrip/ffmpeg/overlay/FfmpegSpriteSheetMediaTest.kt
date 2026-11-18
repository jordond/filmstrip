package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.loopArguments
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.SheetReads
import dev.jordond.filmstrip.ffmpeg.support.SheetReads.RGB
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sprite sheet overlays exported through a real ffmpeg, with the cell on every written frame read back and compared
 * with the frame the shared timeline puts on that slot.
 *
 * Each cell paints its index as eight squares, so a frame is read by what its overlay shows and never by a color an
 * encoder moves.
 */
class FfmpegSpriteSheetMediaTest {
  private val fixture = FfmpegExportFixture()
  private val frames = FilterFrames()

  @Test
  fun `a sheet of one duration shows the timeline's frame on every slot at every rate`() =
    runTest(timeout = TIMEOUT) {
      for (rate in RATES) assertExport(overlayOf(even()), rate)
    }

  // The durations are 100, 30, 250, 40 and 70 ms over and over, so a slot lands in the middle of a long frame and
  // between two short ones. The clip runs past a whole cycle, so the middle of the second one is read.
  @Test
  fun `a sheet of uneven durations shows the timeline's frame on every slot at every rate`() =
    runTest(timeout = TIMEOUT) {
      for (rate in RATES) {
        val read = assertExport(overlayOf(uneven()), rate)
        if (rate == FINE_RATE) {
          withClue("the second cycle reads every cell of the sheet") {
            read.distinct().size shouldBe SpriteSheets.COLUMNS * SpriteSheets.ROWS
          }
        }
      }
    }

  @Test
  fun `a sheet on a clip shows the timeline's frame on every slot`() =
    runTest(timeout = TIMEOUT) {
      val spec = overlayOf(uneven())
      for (rate in RATES) assertExport(spec, rate) { source -> clipScoped(source, spec) }
    }

  @Test
  fun `a sheet inside a window opens on the window's first slot and leaves nothing outside it`() =
    runTest(timeout = TIMEOUT) {
      val window = TimeRange.of(WINDOW_START, WINDOW_END)
      val spec = overlayOf(uneven(), visibleDuring = window)
      for (rate in listOf(30, FINE_RATE)) {
        val first = outputSlot(WINDOW_START, rate)
        val last = outputSlot(WINDOW_END, rate)
        assertExport(spec, rate, drawnSlots = first until last)
      }
    }

  @Test
  fun `a sheet next to a fade and a slide shows the timeline's frame on every slot it is opaque`() =
    runTest(timeout = TIMEOUT) {
      val moving = fade(inDuration = FADE) + slideIn(OverlayOffset(0.3f, 0f), SLIDE)
      val spec = overlayOf(uneven(), animation = moving)
      for (rate in RATES) {
        val span = TimeRange.of(Duration.ZERO, CLIP_LENGTH)
        val opaque =
          (0 until slotsOf(CLIP_LENGTH, rate)).filter { slot ->
            spec.frameAt(outputSlotTime(slot.toLong(), rate), span)!!.opacity >= 1f
          }
        // The slide is still moving after the fade has finished, so the cells are read mid-slide.
        withClue("a slot is read while the slide moves") {
          opaque.any { outputSlotTime(it.toLong(), rate) < SLIDE } shouldBe true
        }
        assertExport(spec, rate, drawnSlots = opaque.first()..opaque.last())
      }
    }

  @Test
  fun `a sheet on a clip at speed two is counted on the output slots`() =
    runTest(timeout = TIMEOUT) {
      val spec = overlayOf(uneven())
      for (rate in listOf(30, FINE_RATE)) {
        assertExport(spec, rate, source = SheetReads.backdrop(30, CLIP_LENGTH * 2)) { source ->
          compositionOf {
            clip(MediaSource.of(source.absolutePath)) { speed(2.0) }
            effects { add(spec) }
          }
        }
      }
    }

  // A sheet that plays twice ends on its last frame, and the frames before it are the ones the timeline counts in the
  // first and the second play.
  @Test
  fun `a sheet that plays twice holds its last frame`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 40.milliseconds, plays = FramePlays.Times(2))
      for (rate in listOf(30, FINE_RATE)) {
        val read = assertExport(overlayOf(sheet), rate)
        val held = read.takeLastWhile { it == read.last() }
        withClue("the last frame holds from the end of the second play") {
          read.last() shouldBe sheet.frameCount - 1
          (held.size * 1.0 / rate > 1.0) shouldBe true
        }
      }
    }

  // The cells are flat colors out to their edges, so a texel drawn from beside a cell is a color the cell does not
  // hold. The sheet is drawn 3.5 times as wide as it is stored and between whole pixels. Cells 5 and 6 have a neighbour
  // on all four sides, 1 and 9 on three.
  //
  // The frames are read from SETTLED on, past the encoder's first second, whose rate control has not yet settled on the
  // cell's flat color.
  @Test
  fun `a cell drawn between whole pixels holds no color from the cells beside it`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
      val read = mutableMapOf<Int, Int>()
      val foreign =
        foreignTexelsOf(
          flatOverlayOf(sheet, SheetReads.SCALE, BETWEEN_MARGIN),
          BLEED_CELLS,
        ) { read.merge(it, 1, Int::plus) }
      withClue("every cell is read on frames from the middle of the run") { read.keys shouldBe BLEED_CELLS.toSet() }
      withClue("texels of a neighbour's color over ${read.values.sum()} frames") { foreign shouldBe 0 }
    }

  // The export is yuv420p, so its chroma is shared by two pixels and a flat cell wobbles by a few codes against the
  // video under it, most at its corners, whatever the sheet does. The same sheet drawn four times as wide at a whole
  // pixel offset is cut and scaled with every texel of a cell on a whole pixel, so it is the wobble by itself.
  // TOLERANCE is the fewest codes that read it clean, and the code below it does not.
  @Test
  fun `a sheet drawn on whole pixels is clean within the tolerance and not within one code less`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)
      val whole = flatOverlayOf(sheet, WHOLE_SCALE, WHOLE_MARGIN)
      withClue("on whole pixels at tolerance $TOLERANCE") { foreignTexelsOf(whole, BLEED_CELLS) {} shouldBe 0 }
      withClue("on whole pixels at tolerance ${TOLERANCE - 1}") {
        foreignTexelsOf(whole, BLEED_CELLS, TOLERANCE - 1) {} shouldBeGreaterThan 0
      }
    }

  /**
   * Exports [spec] and counts the drawn texels of every frame from [SETTLED] on that are not the color of the cell it
   * shows, when that cell is one of [cells]. [read] is called with the cell of each frame counted.
   */
  private suspend fun foreignTexelsOf(
    spec: ImageOverlay,
    cells: List<Int>,
    tolerance: Int = TOLERANCE,
    read: (cell: Int) -> Unit,
  ): Int {
    val exported = exported(spec, FINE_RATE)
    val size = exported.plan.output.size
    val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
    var foreign = 0
    SheetReads.forEachFrame(exported.file.absolutePath, size) { slot, rgb ->
      val cell = SheetReads.expectedFrame(spec, span, slot.toLong(), FINE_RATE)
      if (slot >= SETTLED && cell in cells) {
        val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), FINE_RATE), SpriteSheets.CELL)
        foreign += SpriteSheets.foreignTexels(SheetReads.regionIn(rgb, size, rect), cell, tolerance)
        read(cell)
      }
    }
    exported.file.delete()
    return foreign
  }

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

  @Test
  fun `the sheet is opened once and the graph repeats it`() =
    runTest(timeout = TIMEOUT) {
      val source = SheetReads.backdrop(30, CLIP_LENGTH)
      val invocation = frames.invocationOf(compositionOf(source, overlayOf(uneven())))
      val sheet = invocation.inputs.last()

      withClue("the sheet's own input is read at the frame rate and is not looped by the demuxer") {
        sheet.loopArguments() shouldContain "-framerate"
        sheet.loopArguments() shouldNotContain "-loop"
      }
      source.delete()
    }

  private suspend fun exported(
    spec: ImageOverlay,
    rate: Int,
    source: File = SheetReads.backdrop(SOURCE_RATE, CLIP_LENGTH),
    build: (File) -> Composition = { compositionOf(it, spec) },
  ): ExportedOverlay {
    val output = File.createTempFile("filmstrip-sheet", ".mp4").also { it.delete() }
    val plan = fixture.plannedRun(build(source), ExportSpec(frameRate = rate))
    fixture.exported(plan, output)
    source.delete()
    return ExportedOverlay(output, plan)
  }

  /**
   * Exports [spec] at [rate], reads the cell off every written frame, and answers the index read on each slot of
   * [drawnSlots]. The frames outside it must not show the sheet.
   */
  private suspend fun assertExport(
    spec: ImageOverlay,
    rate: Int,
    drawnSlots: IntRange? = null,
    source: File = SheetReads.backdrop(SOURCE_RATE, CLIP_LENGTH),
    build: (File) -> Composition = { compositionOf(it, spec) },
  ): List<Int> {
    val exported = exported(spec, rate, source, build)
    val size = exported.plan.output.size
    val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
    val read = mutableListOf<Int>()
    val count =
      SheetReads.forEachFrame(exported.file.absolutePath, size) { slot, rgb ->
        val texel = SheetReads.packed(rgb, size.width, RGB)
        if (drawnSlots == null || slot in drawnSlots) {
          val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), rate), SpriteSheets.CELL)
          val expected = SheetReads.expectedFrame(spec, span, slot.toLong(), rate)
          withClue(
            "slot $slot at $rate fps read ${SheetReads.indexIn(texel, rect, size)}, the timeline says $expected",
          ) {
            SheetReads.indexIn(texel, rect, size) shouldBe expected
          }
          read += expected
        } else {
          withClue("slot $slot at $rate fps is outside the window") {
            SheetReads.showsRing(texel) shouldBe false
          }
        }
      }
    withClue("frames written at $rate fps") { count shouldBe slotsOf(exported.plan.duration, rate) }
    exported.file.delete()
    return read
  }

  private fun clipScoped(
    source: File,
    spec: ImageOverlay,
  ): Composition =
    compositionOf {
      clip(MediaSource.of(source.absolutePath)) { effects { add(spec) } }
    }

  private fun compositionOf(
    source: File,
    spec: ImageOverlay,
  ): Composition =
    compositionOf {
      clip(MediaSource.of(source.absolutePath))
      effects { add(spec) }
    }

  private fun slotsOf(
    length: Duration,
    rate: Int,
  ): Int = outputSlot(length, rate)

  private fun outputSlot(
    time: Duration,
    rate: Int,
  ): Int = outputSlotOf(time, rate).toInt()

  private fun overlayOf(
    sheet: SpriteSheet,
    scale: Float = SheetReads.SCALE,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ): ImageOverlay = SheetReads.overlayOf(sheet, scale, visibleDuring, animation)

  private fun even(): SpriteSheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)

  private fun uneven(): SpriteSheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)

  private companion object {
    val RATES = listOf(24, 30, 60)
    const val FINE_RATE = 60
    const val SOURCE_RATE = 30
    val CLIP_LENGTH = 3.seconds
    val WINDOW_START = 500.milliseconds
    val WINDOW_END = 2_000.milliseconds
    val FADE = 400.milliseconds
    val SLIDE = 1_500.milliseconds
    const val SETTLED = 60
    const val TOLERANCE = 6
    const val BETWEEN_MARGIN = 0.1873f
    const val WHOLE_SCALE = 0.4f
    const val WHOLE_MARGIN = 0.1f
    val BLEED_CELLS = listOf(5, 6, 9, 1)
  }
}
