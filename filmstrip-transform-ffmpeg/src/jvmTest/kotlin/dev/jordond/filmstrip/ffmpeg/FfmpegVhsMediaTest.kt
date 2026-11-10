package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.Mbps
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.clearCanvasNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.support.BYTE
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.RGBA
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.texelsOver
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import io.kotest.matchers.doubles.shouldBeLessThan as shouldBeBelow

/**
 * What a [Vhs] reaches on ffmpeg at each scope, and how its clock holds across a seek and an encoder.
 */
class FfmpegVhsMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")
  private val draw = FilterFrames()

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val composition = whole(Clip(MediaSource.of(landscape.path)), listOf(Vhs(intensity = 0.5f)))

      assertSameFrameOnExport(composition)
    }

  @Test
  fun `a clip vhs in a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val composition = whole(Clip(MediaSource.of(landscape.path), effects = listOf(Vhs(intensity = 0.5f))))

      assertSameFrameOnExport(composition)
    }

  @Test
  fun `a composition vhs reaches the bars and a same scope overlay stays clean`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val vhs = Vhs(intensity = 0.5f)
      val composition =
        whole(
          Clip(MediaSource.of(landscape.path)),
          listOf(Crop(AspectRatio.Square, fit = Fit.Contain), vhs, swatch()),
          Fill.Solid(BLACK),
        )

      val frame = squareFrame(composition)

      abs(barLuma(frame) - barLumaOnSignal(vhs)) shouldBeBelow halfLift(vhs)
      abs(red(frame, CANVAS, SWATCH_X, SWATCH_Y) - SWATCH_CODE) shouldBeLessThan CLEAN_CODES
    }

  @Test
  fun `a clip vhs leaves the composition bars alone`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val vhs = Vhs(intensity = 0.5f)
      val composition =
        whole(
          Clip(MediaSource.of(landscape.path), effects = listOf(vhs)),
          listOf(Crop(AspectRatio.Square, fit = Fit.Contain)),
          Fill.Solid(BLACK),
        )

      barLuma(squareFrame(composition)) shouldBeBelow halfLift(vhs)
    }

  @Test
  fun `a clip vhs after a contain crop keeps the alpha that marks the unpainted bars`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val clip =
        Clip(
          MediaSource.of(landscape.path),
          effects = listOf(Crop(AspectRatio.Square, fit = Fit.Contain), Vhs()),
        )
      val frame = squareFrame(whole(clip, fill = Fill.Solid(SWATCH_FILL)))

      // The canvas runs in an alpha format until the fill lands, so the fill still lands in a bar under the tape. A geq
      // that dropped the plane would leave the bar holding the canvas's own black, lifted.
      abs(red(frame, CANVAS, CANVAS / 2, 10) - SWATCH_FILL_CODE) shouldBeLessThan CLEAN_CODES
    }

  @Test
  fun `a clip overlay under a composition vhs is burned`() =
    runBlocking<Unit> {
      assumeTrue("the fixtures are absent", landscape.isFile)
      val composition =
        whole(Clip(MediaSource.of(landscape.path), effects = listOf(swatch())), listOf(Vhs()))

      val read = red(draw.frames(composition, Duration.ZERO, 1).single(), 640, SWATCH_X, SWATCH_Y)

      abs(read - SWATCH_CODE) shouldBeGreaterThan CLEAN_CODES - 1
    }

  @Test
  fun `a clip vhs moves the picture into a canvas bar and leaves the fill alone`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val clip = draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt, listOf(crop, vhs))
      val frame = draw.frames(whole(clip, fill = Fill.Solid(BAR_FILL)), Duration.ZERO, READ_SLOT + 1).last()
      withClue("the canvas is as wide as this test reads it") {
        frame.size shouldBe WIDE_WIDTH * RAMP_HEIGHT * RGBA
      }
      val content =
        placementOf(
          Size(RAMP_WIDTH, RAMP_HEIGHT),
          listOf(listOf(crop)),
          Size(WIDE_WIDTH, RAMP_HEIGHT),
        ).drawnRect
      val params = VhsParams.of(vhs)
      val tick = VhsSignal.tickAt(outputSlotTime(READ_SLOT.toLong(), FILTER_RATE).toDouble(DurationUnit.SECONDS))
      val y = RAMP_HEIGHT - 1
      val row = VhsSignal.virtualRow(y, RAMP_HEIGHT)
      val shift = VhsSignal.rowShift(vhs.seed, tick, row, params)
      withClue("the head band has to reach into the bar for this to tell") {
        (shift * WIDE_WIDTH).toInt() shouldBeGreaterThan 2 * BAR_MARGIN
      }
      val inside = { column: Int ->
        val across = ((column + 0.5) / WIDE_WIDTH - content.left) / (content.right - content.left)
        if (across in 0.0..1.0) rampAt(across) else doubleArrayOf(0.0, 0.0, 0.0)
      }

      val movedIn = content.right + shift / 2
      val column = (movedIn * WIDE_WIDTH).toInt()
      val wanted = VhsSignal.pixelAt((column + 0.5) / WIDE_WIDTH, row, tick, vhs.seed, WIDE_WIDTH, params, inside)
      withClue("picture moved into the right bar") {
        val read = DoubleArray(3) { frame.channelAt(WIDE_WIDTH, column, y, it).toDouble() }
        abs(
          lumaOf(read[0], read[1], read[2]) - lumaOf(wanted[0] * FULL, wanted[1] * FULL, wanted[2] * FULL),
        ) shouldBeBelow
          LUMA_CODES
      }
      withClue("past the moved picture the right bar holds the fill") {
        assertFill(frame, ((content.right + shift) * WIDE_WIDTH).toInt() + BAR_MARGIN, y)
      }
      withClue("the picture has left the start of its own rect, and the fill shows there") {
        assertFill(frame, ((content.left + shift / 2) * WIDE_WIDTH).toInt(), y)
      }
      withClue("a row with no head band leaves the bar on the fill") {
        assertFill(
          frame,
          (content.right * WIDE_WIDTH).toInt() + (WIDE_WIDTH * content.left).toInt() / 2,
          RAMP_HEIGHT / 2,
        )
      }
    }

  @Test
  fun `a clip vhs leaves a blurred fill's canvas bars black and moves the picture over them`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val clip = draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt, listOf(crop, vhs))
      val frame = draw.frames(whole(clip, fill = Fill.Blurred()), Duration.ZERO, READ_SLOT + 1).last()
      withClue("the canvas is as wide as this test reads it") {
        frame.size shouldBe WIDE_WIDTH * RAMP_HEIGHT * RGBA
      }
      val placed = placementOf(Size(RAMP_WIDTH, RAMP_HEIGHT), listOf(listOf(crop, vhs)), Size(WIDE_WIDTH, RAMP_HEIGHT))
      withClue("the canvas's bars count as bars") {
        placed.leavesBars shouldBe true
      }
      withClue("nothing shows through them, so the graph runs no cover blur") {
        draw.graphOf(whole(clip, fill = Fill.Blurred())) shouldNotContain "blur]"
      }
      withClue("the canvas fills the frame") {
        listOf(placed.drawnRect.left, placed.drawnRect.top, placed.drawnRect.right, placed.drawnRect.bottom) shouldBe
          listOf(0f, 0f, 1f, 1f)
      }
      val content =
        placementOf(
          Size(RAMP_WIDTH, RAMP_HEIGHT),
          listOf(listOf(crop)),
          Size(WIDE_WIDTH, RAMP_HEIGHT),
        ).drawnRect
      val params = VhsParams.of(vhs)
      val tick = VhsSignal.tickAt(outputSlotTime(READ_SLOT.toLong(), FILTER_RATE).toDouble(DurationUnit.SECONDS))
      val y = RAMP_HEIGHT - 1
      val row = VhsSignal.virtualRow(y, RAMP_HEIGHT)
      val shift = VhsSignal.rowShift(vhs.seed, tick, row, params)
      val inside = { column: Int ->
        val across = ((column + 0.5) / WIDE_WIDTH - content.left) / (content.right - content.left)
        if (across in 0.0..1.0) rampAt(across) else doubleArrayOf(0.0, 0.0, 0.0)
      }

      val column = ((content.right + shift / 2) * WIDE_WIDTH).toInt()
      val wanted = VhsSignal.pixelAt((column + 0.5) / WIDE_WIDTH, row, tick, vhs.seed, WIDE_WIDTH, params, inside)
      withClue("picture moved into the right bar") {
        val read = DoubleArray(3) { frame.channelAt(WIDE_WIDTH, column, y, it).toDouble() }
        abs(
          lumaOf(read[0], read[1], read[2]) - lumaOf(wanted[0] * FULL, wanted[1] * FULL, wanted[2] * FULL),
        ) shouldBeBelow
          LUMA_CODES
      }
      withClue("past the moved picture the right bar is black") {
        assertFill(frame, ((content.right + shift) * WIDE_WIDTH).toInt() + BAR_MARGIN, y, BLACK)
      }
      withClue("the picture has left the start of its own rect, and black shows there") {
        assertFill(frame, ((content.left + shift / 2) * WIDE_WIDTH).toInt(), y, BLACK)
      }
      withClue("a row with no head band leaves the bar black") {
        assertFill(
          frame,
          (content.right * WIDE_WIDTH).toInt() + (WIDE_WIDTH * content.left).toInt() / 2,
          RAMP_HEIGHT / 2,
          BLACK,
        )
      }
    }

  @Test
  fun `a clear canvas under a blurred fill comes in the limited range its black is written in`() {
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val stages = listOf(listOf(crop, Vhs()))
    val canvas = placementOf(Size(RAMP_WIDTH, RAMP_HEIGHT), stages, Size(WIDE_WIDTH, RAMP_HEIGHT)).canvases.single()

    clearCanvasNodes(canvas, "yuv420p").first().render() shouldBe "scale=out_range=limited"
  }

  // A clip chain gates the overlay's window on clip time, so a chain left on the composition clock would miss it.
  @Test
  fun `a timed overlay after a clip vhs keeps the clip's own clock`() =
    runBlocking<Unit> {
      val overlay = swatch(TimeRange(2_500.milliseconds, 3_000.milliseconds))
      val plain = draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt)
      val taped = draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt, listOf(Vhs(intensity = 0.5f), overlay))
      val frames =
        draw.frames(
          draw.composition(listOf(plain, taped)),
          Duration.ZERO,
          2 * FILTER_RATE + LATE_IN_CLIP + 1,
        )

      val inWindow = red(frames[2 * FILTER_RATE + IN_WINDOW], RAMP_WIDTH, SWATCH_X, SWATCH_Y)
      val pastWindow = red(frames[2 * FILTER_RATE + LATE_IN_CLIP], RAMP_WIDTH, SWATCH_X, SWATCH_Y)

      abs(inWindow - SWATCH_CODE) shouldBeLessThan CLEAN_CODES
      abs(pastWindow - SWATCH_CODE) shouldBeGreaterThan CLEAN_CODES
    }

  // A window opening at the seek base runs the same chain, so a clock left moved here reads the seek twice.
  @Test
  fun `a timed overlay after a composition vhs keeps the composition clock in a windowed preview`() =
    runBlocking<Unit> {
      val overlay = swatch(TimeRange(1_200.milliseconds, 1_500.milliseconds))
      val clip = draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt)
      val composition = draw.composition(listOf(clip), listOf(Vhs(intensity = 0.5f), overlay))
      val frames = draw.frames(composition, 1_000.milliseconds, LATE_IN_WINDOW + 1)

      val inWindow = red(frames[IN_WINDOW_FROM_SEEK], RAMP_WIDTH, SWATCH_X, SWATCH_Y)
      val pastWindow = red(frames[LATE_IN_WINDOW], RAMP_WIDTH, SWATCH_X, SWATCH_Y)

      abs(inWindow - SWATCH_CODE) shouldBeLessThan CLEAN_CODES
      abs(pastWindow - SWATCH_CODE) shouldBeGreaterThan CLEAN_CODES
    }

  // A track's effect lands once per clip, so these two fragments are longer than Linux takes for one argument.
  @Test
  fun `a track vhs on two clips exports and reads on the signal`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val clips = List(2) { draw.clipOf(RAMP_WIDTH, RAMP_HEIGHT, ::rampAt) }
      val composition = Composition(listOf(Track(clips, effects = listOf(vhs))), fill = Fill.Solid(BLACK))
      withClue("the graph has to outgrow one argument for this to tell") {
        draw.graphOf(composition).length shouldBeGreaterThan LINUX_ARGUMENT_CHARACTERS
      }

      listOf(READ_SLOT, 2 * FILTER_RATE + READ_SLOT).forEach { slot ->
        val frame = draw.exportedFrame(composition, slot, ENCODED)
        withClue("mean luma error against the signal at slot $slot") {
          meanLumaOffSignal(frame, vhs, slot) shouldBeBelow ENCODED_SIGNAL_CODES
        }
      }
    }

  private fun meanLumaOffSignal(
    frame: ByteArray,
    vhs: Vhs,
    slot: Int,
  ): Double {
    val params = VhsParams.of(vhs)
    val tick = VhsSignal.tickAt(outputSlotTime(slot.toLong(), FILTER_RATE).toDouble(DurationUnit.SECONDS))
    val ramp = (::rampAt).texelsOver(RAMP_WIDTH)
    val errors =
      (0 until RAMP_HEIGHT step SIGNAL_STEP).flatMap { y ->
        val row = VhsSignal.virtualRow(y, RAMP_HEIGHT)
        (0 until RAMP_WIDTH step SIGNAL_STEP).map { x ->
          val want = VhsSignal.pixelAt((x + 0.5) / RAMP_WIDTH, row, tick, vhs.seed, RAMP_WIDTH, params, ramp)
          val read = DoubleArray(3) { frame.channelAt(RAMP_WIDTH, x, y, it).toDouble() }
          abs(lumaOf(read[0], read[1], read[2]) - lumaOf(want[0] * FULL, want[1] * FULL, want[2] * FULL))
        }
      }
    return errors.average()
  }

  private suspend fun assertSameFrameOnExport(composition: Composition) {
    val at = 1_000.milliseconds
    val windowed = draw.frames(composition, at, 1, ENCODED).single()
    val exported = draw.exportedFrame(composition, outputSlotOf(at, FILTER_RATE).toInt(), ENCODED)

    exported.size shouldBe windowed.size
    val error = meanCodes(windowed, exported)
    withClue("mean error across the frame") { error shouldBeBelow ENCODER_CODES }
  }

  private fun meanCodes(
    first: ByteArray,
    second: ByteArray,
  ): Double {
    var total = 0L
    var count = 0L
    first.indices.filter { it % RGBA != RGBA - 1 }.forEach { index ->
      total += abs((first[index].toInt() and BYTE) - (second[index].toInt() and BYTE))
      count++
    }
    return total.toDouble() / count
  }

  private fun assertFill(
    frame: ByteArray,
    x: Int,
    y: Int,
    fill: Int = BAR_FILL,
  ) {
    listOf(fill shr 16 and BYTE, fill shr 8 and BYTE, fill and BYTE).forEachIndexed { channel, code ->
      withClue("column $x row $y channel $channel") {
        abs(frame.channelAt(WIDE_WIDTH, x, y, channel) - code) shouldBeLessThan CLEAN_CODES
      }
    }
  }

  private fun lumaOf(
    red: Double,
    green: Double,
    blue: Double,
  ): Double = 0.2126 * red + 0.7152 * green + 0.0722 * blue

  private fun whole(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(BLACK),
  ): Composition = draw.composition(listOf(clip), effects, fill)

  private suspend fun squareFrame(composition: Composition): ByteArray {
    val frame = draw.frames(composition, Duration.ZERO, 1).single()
    withClue("the square canvas is as wide as this test reads it") {
      frame.size shouldBe CANVAS * CANVAS * RGBA
    }
    return frame
  }

  // The mean luma across the top bar, since chroma and luma noise move any one pixel by more than the lift it reads.
  // Half the frame's width keeps clear of a swatch in the top corner.
  private fun barLuma(frame: ByteArray): Double =
    meanAcrossBar { x, y ->
      lumaOf(
        frame.channelAt(CANVAS, x, y, 0).toDouble(),
        frame.channelAt(CANVAS, x, y, 1).toDouble(),
        frame.channelAt(CANVAS, x, y, 2).toDouble(),
      )
    }

  private fun barLumaOnSignal(vhs: Vhs): Double {
    val params = VhsParams.of(vhs)
    val tick = VhsSignal.tickAt(0.0)
    val black = { _: Int -> doubleArrayOf(0.0, 0.0, 0.0) }
    return meanAcrossBar { x, y ->
      val row = VhsSignal.virtualRow(y, CANVAS)
      val rgb = VhsSignal.pixelAt((x + 0.5) / CANVAS, row, tick, vhs.seed, CANVAS, params, black)
      lumaOf(rgb[0] * FULL, rgb[1] * FULL, rgb[2] * FULL)
    }
  }

  private fun meanAcrossBar(lumaAt: (Int, Int) -> Double): Double {
    val columns = CANVAS / 2 until CANVAS
    val total = (0 until BAR_ROWS).sumOf { y -> columns.sumOf { x -> lumaAt(x, y) } }
    return total / (BAR_ROWS * columns.count())
  }

  // A bar tells lifted from clean when the read lands closer to its own side of this.
  private fun halfLift(vhs: Vhs): Double = VhsParams.of(vhs).lift * FULL / 2

  // The mean of the three channels, since chroma noise moves any one of them by more than a swatch's margin.
  private fun red(
    frame: ByteArray,
    width: Int,
    x: Int,
    y: Int,
  ): Int = (0..2).sumOf { frame[(y * width + x) * RGBA + it].toInt() and BYTE } / 3

  // A mid-range ramp with blue running against the other two, so nothing saturates and a shift reads in both.
  private fun rampAt(x: Double): DoubleArray {
    val code = RAMP_LOW + x.coerceIn(0.0, 1.0) * RAMP_SPAN
    return doubleArrayOf(code, code, 1.0 - code)
  }

  private fun swatch(window: TimeRange? = null): ImageOverlay {
    val image = BufferedImage(SWATCH_PX, SWATCH_PX, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until SWATCH_PX) {
      for (x in 0 until SWATCH_PX) image.setRGB(x, y, SWATCH_RGB)
    }
    val file = File.createTempFile("vhs-swatch", ".png").apply { deleteOnExit() }
    ImageIO.write(image, "png", file)
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE, visibleDuring = window)
  }

  private companion object {
    const val BLACK = 0xFF000000.toInt()
    const val READ_SLOT = 30
    const val RAMP_WIDTH = 640
    const val RAMP_HEIGHT = 360
    const val RAMP_LOW = 0.25
    const val RAMP_SPAN = 0.5
    const val LUMA_CODES = 4.0
    const val SWATCH_CODE = 0x20
    const val SWATCH_RGB = 0x202020
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_X = 40
    const val SWATCH_Y = 40
    const val CLEAN_CODES = 4

    // A square canvas keeps the height of the 640 by 360 fixture, which leaves 79 rows of bar above the picture.
    const val CANVAS = 360
    const val BAR_ROWS = 70
    const val SWATCH_FILL = 0xFF808080.toInt()
    const val SWATCH_FILL_CODE = 0x80
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val WIDE_WIDTH = 1080
    const val BAR_FILL = 0xFF20C040.toInt()
    const val BAR_MARGIN = 12
    const val IN_WINDOW = 22
    const val LATE_IN_CLIP = 45
    const val IN_WINDOW_FROM_SEEK = 9
    const val LATE_IN_WINDOW = 18
    val ENCODED = ExportSpec(bitrate = 40.Mbps)

    // The encoder's own error at this bitrate, a mean across the frame.
    const val ENCODER_CODES = 1.0

    // What Linux takes for one argument, MAX_ARG_STRLEN.
    const val LINUX_ARGUMENT_CHARACTERS = 131_072
    const val SIGNAL_STEP = 8

    // A written frame against the signal, the encoder's error on top of the trip through limited range and back.
    const val ENCODED_SIGNAL_CODES = 3.0
  }
}
