package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.GlitchParams
import dev.jordond.filmstrip.effects.filters.glitch.GlitchSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.lumaCode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Reads the frames ffmpeg draws for a [Glitch] straight off the preview pump against [GlitchSignal], with no encoder
 * in the way.
 *
 * The picture a read is held against is the same clip drawn with no [Glitch], read between pixels by the signal.
 * The picture is grey teeth steep enough that a split or a slide lands well past the tolerance. Rows within two pixels
 * of a change of shift and reads near a tooth's wrap are left out, since 4:2:0 averages chroma across them, and that
 * is ffmpeg's limit. The seed is the first whose burst is rising at the read, found through the signal.
 */
class FfmpegGlitchMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition glitch lands on the signal mid attack`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH))

      assertLands(clip, listOf(BURSTING), BURSTING, WIDTH, HEIGHT, TEETH)
    }

  @Test
  fun `a clip glitch lands on the signal mid attack`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH), listOf(BURSTING))
      val plain = draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH))

      assertLands(clip, emptyList(), BURSTING, WIDTH, HEIGHT, TEETH, plain)
    }

  @Test
  fun `a quiet tick splits the colours and moves no row`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH))

      assertLands(clip, listOf(QUIET), QUIET, WIDTH, HEIGHT, TEETH)
    }

  @Test
  fun `a smaller preview draws the same burst at its own rows`() =
    runBlocking<Unit> {
      // Half the teeth, so the preview draws them as many pixels apart as the export does.
      val clip = draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH / 2))

      assertLands(
        clip,
        listOf(BURSTING),
        BURSTING,
        WIDTH / 2,
        HEIGHT / 2,
        TEETH / 2,
        spec = ExportSpec(heightPx = HEIGHT / 2),
      )
    }

  @Test
  fun `a 4K frame lands on the signal`() =
    runBlocking<Unit> {
      val teeth = TEETH * UHD_WIDTH / WIDTH
      val clip = draw.clipOf(UHD_WIDTH, UHD_HEIGHT, sawtooth(teeth))

      assertLands(clip, listOf(BURSTING), BURSTING, UHD_WIDTH, UHD_HEIGHT, teeth)
    }

  // Coded at half the width with teeth 16 coded pixels apart, so the picture reaches the look at whatever size the
  // backend draws and each tooth spans as many coded pixels as the square-pixel tests.
  @Test
  fun `a clip glitch on non-square pixels lands on the signal at the display size`() =
    runBlocking<Unit> {
      val source = anamorphicStill(TEETH / 2)

      assertLands(
        Clip(source, effects = listOf(BURSTING)),
        emptyList(),
        BURSTING,
        WIDTH,
        HEIGHT,
        TEETH / 2,
        Clip(source),
      )
    }

  @Test
  fun `a clip glitch moves picture into a clear bar and leaves the fill elsewhere`() =
    runBlocking<Unit> {
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)
      val square = draw.clipOf(HEIGHT, HEIGHT, { GREY.copyOf() }, listOf(wide, BURSTING))
      val frame = frameOf(draw.composition(listOf(square), fill = Fill.Solid(SLATE_ARGB)))
      val plain =
        frameOf(
          draw.composition(
            listOf(
              draw.clipOf(HEIGHT, HEIGHT, {
                GREY.copyOf()
              }, listOf(wide)),
            ),
            fill = Fill.Solid(SLATE_ARGB),
          ),
        )
      val params = GlitchParams.of(BURSTING)
      val shiftAt = { y: Int -> GlitchSignal.rowShift(BURSTING.seed, TICK, row(y, HEIGHT), params) }
      val steady = steadyRows(HEIGHT, shiftAt)
      val y = steady.maxBy { abs(shiftAt(it)) }
      val shift = shiftAt(y) * WIDTH
      val left = (WIDTH - HEIGHT) / 2
      val leading = if (shift > 0) left + HEIGHT else left
      val trailing = if (shift > 0) left else left + HEIGHT
      val still = steady.first { shiftAt(it) == 0.0 }

      val picture = plain.green(WIDTH / 2.0, HEIGHT / 2)
      val fill = plain.green(left / 2.0, HEIGHT / 2)
      withClue("the row slides far enough to tell") { abs(shift) shouldBeGreaterThan SLIDE_PIXELS }
      withClue("picture moved into the bar") {
        abs(frame.green(leading + shift / 2, y) - picture) shouldBeLessThan
          ROUND_TRIP_CODES
      }
      withClue("the fill shows where the picture left") {
        abs(frame.green(trailing + shift / 2, y) - fill) shouldBeLessThan FLAT_CODES
      }
      withClue("a bar on a still row holds the fill") {
        abs(frame.green(left / 2.0, still) - fill) shouldBeLessThan FLAT_CODES
      }
    }

  @Test
  fun `a clear bar beside the picture holds the fill where the split reads picture`() =
    runBlocking<Unit> {
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)
      val drawnWith = { effects: List<EffectSpec> ->
        draw.composition(listOf(draw.clipOf(HEIGHT, HEIGHT, { GREY.copyOf() }, effects)), fill = Fill.Solid(SLATE_ARGB))
      }
      val frame = frameOf(drawnWith(listOf(wide, BURSTING)))
      val plain = frameOf(drawnWith(listOf(wide)))
      val params = GlitchParams.of(BURSTING)
      val split = GlitchSignal.channelSplit(BURSTING.seed, TICK, params) * WIDTH
      val shiftAt = { y: Int -> GlitchSignal.rowShift(BURSTING.seed, TICK, row(y, HEIGHT), params) }
      val y = steadyRows(HEIGHT, shiftAt).first { shiftAt(it) == 0.0 }
      val left = (WIDTH - HEIGHT) / 2
      val gap = { column: Int, channel: Int ->
        abs(frame.channelAt(WIDTH, column, y, channel) - plain.channelAt(WIDTH, column, y, channel)).toDouble()
      }

      withClue("the split reaches across the edge") { split shouldBeGreaterThan 1.0 }
      withClue("red beside the right edge") { gap(left + HEIGHT, 0) shouldBeLessThan ROUND_TRIP_CODES }
      withClue("blue beside the left edge") { gap(left - 1, 2) shouldBeLessThan ROUND_TRIP_CODES }
    }

  @Test
  fun `an overlay at the same scope stays clean and a clip overlay under a composition glitch is torn`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() })
      val swatched = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() }, listOf(swatch()))

      val clean = frameOf(draw.composition(listOf(clip), listOf(swatch(), BURSTING)))
      val plain = frameOf(draw.composition(listOf(swatched)))
      val torn = frameOf(draw.composition(listOf(swatched), listOf(BURSTING)))

      withClue("clean") { worstGreen(clean, plain) shouldBeLessThan FLAT_CODES }
      withClue("torn") { worstGreen(torn, plain) shouldBeGreaterThan FLAT_CODES }
    }

  @Test
  fun `a frame from a windowed preview equals the same frame from the start`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, sawtooth(TEETH))), listOf(BURSTING))

      val fromStart = draw.frames(composition, Duration.ZERO, READ_SLOT + 1).last()
      val fromWindow = draw.frames(composition, READ_TIME, 1).single()
      val elsewhere = draw.frames(composition, Duration.ZERO, 1).single()

      withClue("the same time") { fromWindow.contentEquals(fromStart) shouldBe true }
      withClue("another time") { elsewhere.contentEquals(fromStart) shouldBe false }
    }

  // The canvas holds clear bars either side of the picture, so a read that lands between the two weighs by alpha. A
  // read of straight colour would take the bar's black in on top of its clear. Luma is read since 4:2:0 holds one
  // chroma sample across that edge.
  @Test
  fun `a clip glitch read between the picture and a clear bar weighs by alpha`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val clip = draw.clipOf(HEIGHT, HEIGHT, { BRIGHT.copyOf() }, listOf(crop, BURSTING))
      val frame = draw.planesAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE_ARGB)), READ_SLOT)
      val params = GlitchParams.of(BURSTING)
      val pictureLeft = (WIDTH - HEIGHT) / 2
      val pictureRight = pictureLeft + HEIGHT
      val shownAt = { x: Int, y: Int, bar: DoubleArray ->
        GlitchSignal.pixelAt((x + 0.5) / WIDTH, row(y, HEIGHT), TICK, BURSTING.seed, WIDTH, params) { column ->
          if (column in pictureLeft until pictureRight) BRIGHT + 1.0 else bar
        }
      }
      val laid = { colour: DoubleArray, alpha: Double ->
        lumaCode(DoubleArray(RGB) { colour[it] * alpha + SLATE_CODES[it] / FULL * (1.0 - alpha) })
      }
      val columns = listOf(pictureLeft, pictureRight).flatMap { it - BOUNDARY_READS..it + BOUNDARY_READS }
      var worst = 0.0
      var tells = 0.0
      (HEIGHT / 4 until HEIGHT * 3 / 4 step ROW_EVERY).forEach { y ->
        columns.forEach { x ->
          val shown = shownAt(x, y, DoubleArray(RGB + 1))
          val wanted = laid(shown, shown[RGB])
          // The colour read with the bar's black mixed in straight, which only weighing by alpha keeps out.
          val straight = laid(shownAt(x, y, doubleArrayOf(0.0, 0.0, 0.0, 1.0)), shown[RGB])
          worst = max(worst, abs(frame.luma(x, y).toDouble() - wanted))
          tells = max(tells, abs(wanted - straight))
        }
      }

      withClue("a straight read lands up to $tells codes off, too close to tell") {
        tells shouldBeGreaterThan 4 * MIXED_CODES
      }
      withClue("worst $worst") { worst shouldBeLessThan MIXED_CODES }
    }

  // Only reads well inside one tooth are held to the signal, since 4:2:0 averages colour across a tooth's wrap.
  private suspend fun assertLands(
    clip: Clip,
    effects: List<EffectSpec>,
    glitch: Glitch,
    width: Int,
    height: Int,
    teeth: Int,
    plainClip: Clip = clip,
    spec: ExportSpec = ExportSpec(),
  ) {
    val frame = draw.frames(draw.composition(listOf(clip), effects), READ_TIME, 1, spec).single()
    val plain = draw.frames(draw.composition(listOf(plainClip)), READ_TIME, 1, spec).single()
    frame.size shouldBe width * height * RGBA_BYTES
    val params = GlitchParams.of(glitch)
    val split = GlitchSignal.channelSplit(glitch.seed, TICK, params)
    val shiftAt = { y: Int -> GlitchSignal.rowShift(glitch.seed, TICK, row(y, height), params) }
    val tooth = { column: Int -> ((column + 0.5) * teeth / width).toInt() }
    val inTooth = { at: Double ->
      val across = at * width - 0.5
      val first = floor(across).toInt() - TOOTH_MARGIN
      val last = ceil(across).toInt() + TOOTH_MARGIN
      first >= 0 && last < width && tooth(first) == tooth(last)
    }
    val steady = steadyRows(height, shiftAt)
    var worst = 0.0
    var reads = 0
    var swapGap = 0.0
    var stillGap = 0.0
    steady.filterIndexed { index, _ -> index % ROW_EVERY == 0 }.forEach { y ->
      val texel = { column: Int -> DoubleArray(RGB) { plain.channelAt(width, column, y, it) / FULL } }
      val nearest = { at: Double -> texel((at.coerceIn(0.0, 1.0) * width).toInt().coerceAtMost(width - 1)) }
      val shifted = { x: Double -> x - shiftAt(y) }
      (0 until width step width / COLUMNS)
        .map { (it + 0.5) / width }
        .filter { x -> listOf(-split, 0.0, split).all { inTooth(shifted(x) + it) } }
        .forEach { x ->
          val column = (x * width).toInt()
          val expected = GlitchSignal.pixelAt(x, row(y, height), TICK, glitch.seed, width, params, texel)
          for (channel in 0 until RGB) {
            worst = max(worst, abs(frame.channelAt(width, column, y, channel) - expected[channel] * FULL))
          }
          reads++
          swapGap = max(swapGap, abs(nearest(shifted(x) + split)[0] - expected[0]) * FULL)
          stillGap = max(stillGap, abs(nearest(x)[1] - expected[1]) * FULL)
        }
    }
    withClue("reads $reads") { reads shouldBeGreaterThan COLUMNS }
    withClue("red and blue swapped would read $swapGap codes off") { swapGap shouldBeGreaterThan 2 * ROUND_TRIP_CODES }
    if (glitch == BURSTING) {
      withClue("some row read slides at least a pixel") { steady.any { abs(shiftAt(it)) * width > 1.0 } shouldBe true }
      withClue("a row left still would read $stillGap codes off") { stillGap shouldBeGreaterThan 2 * ROUND_TRIP_CODES }
    }
    withClue("worst $worst") { worst shouldBeLessThan ROUND_TRIP_CODES }
  }

  // The rows whose shift holds for EDGE_PIXELS either side, so the rows 4:2:0 shares their chroma with slid as far.
  private fun steadyRows(
    height: Int,
    shiftAt: (Int) -> Double,
  ): List<Int> =
    (EDGE_PIXELS until height - EDGE_PIXELS).filter { y ->
      (y - EDGE_PIXELS..y + EDGE_PIXELS).all { shiftAt(it) == shiftAt(y) }
    }

  // A still coded at half the width with twice-wide pixels, so it shows at the export size.
  private fun anamorphicStill(teeth: Int): MediaSource {
    val still = File.createTempFile("glitch-anamorphic", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        hostFfmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "nullsrc=s=${WIDTH / 2}x$HEIGHT:r=$FILTER_RATE:d=2,geq=lum='${teethLuma(teeth)}':cb=128:cr=128,setsar=2",
        "-c:v",
        "ffv1",
        "-pix_fmt",
        "yuv420p",
        still.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return MediaSource.of(still.path)
  }

  private suspend fun frameOf(composition: Composition): ByteArray = draw.frames(composition, READ_TIME, 1).single()

  private fun worstGreen(
    a: ByteArray,
    b: ByteArray,
  ): Double {
    var worst = 0
    for (y in 0 until HEIGHT / 2) {
      for (x in 0 until WIDTH / 2) worst = max(worst, abs(a.channelAt(WIDTH, x, y, 1) - b.channelAt(WIDTH, x, y, 1)))
    }
    return worst.toDouble()
  }

  private fun ByteArray.green(
    x: Double,
    y: Int,
  ): Double = channelAt(WIDTH, x.toInt(), y, 1).toDouble()

  private fun row(
    y: Int,
    height: Int,
  ): Int = GlitchSignal.virtualRow(y, height)

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val UHD_WIDTH = 3840
    const val UHD_HEIGHT = 2160
    const val RGB = 3
    const val RGBA_BYTES = 4
    const val READ_SLOT = 30
    const val EDGE_PIXELS = 2
    const val SLIDE_PIXELS = 4.0
    const val ROW_EVERY = 5
    const val COLUMNS = 160

    // Teeth across the export width, each 16 pixels wide, and how far a read keeps clear of a tooth's wrap.
    const val TEETH = 40
    const val TOOTH_MARGIN = 3
    const val TOOTH_LOW = 0.1
    const val TOOTH_HIGH = 0.9
    const val SEED_SEARCH = 100_000
    val GREY = doubleArrayOf(0.4, 0.4, 0.4)
    const val SLATE_ARGB = 0xFF204060.toInt()
    val SLATE_CODES = listOf(0x20.toDouble(), 0x40.toDouble(), 0x60.toDouble())
    val BRIGHT = doubleArrayOf(0.745, 0.725, 0.706)
    const val BOUNDARY_READS = 6

    // A luma read between the picture and a clear bar, against the signal, through gbrap and 4:2:0 and back.
    const val MIXED_CODES = 4.0
    const val SWATCH_ARGB = 0xFFD0D0D0.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f

    val READ_TIME = outputSlotTime(READ_SLOT.toLong(), FILTER_RATE)
    val TICK = GlitchSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
    val MID = GlitchParams.at(0.5)

    // The first seed whose burst at the read is still rising, and the first with no burst there.
    val BURSTING =
      Glitch(
        0.5f,
        seed =
          (1..SEED_SEARCH).first { seed ->
            val now = GlitchSignal.burstAt(seed, TICK, MID)?.strength ?: 0.0
            val next = GlitchSignal.burstAt(seed, TICK + 1, MID)?.strength ?: 0.0
            now > 0.0 && next > now
          },
      )
    val QUIET = Glitch(0.5f, seed = (1..SEED_SEARCH).first { GlitchSignal.burstAt(it, TICK, MID) == null })

    // The mean of rounded channels on a flat area.
    const val FLAT_CODES = 2.0

    // Going through gbrap and back rounds twice, and the preview's RGBA rounds once more, which is ffmpeg's limit.
    const val ROUND_TRIP_CODES = 3.0

    // The sawtooth's teeth as a geq luma expression on full codes, read at each pixel's centre.
    fun teethLuma(teeth: Int): String = "255*($TOOTH_LOW+mod((X+0.5)*$teeth/W\\,1)*${TOOTH_HIGH - TOOTH_LOW})"

    // Grey teeth across the width, all three channels on one slope, so a read a split or a slide away lands codes off
    // while the colour stays flat enough inside a tooth for 4:2:0 to hold it.
    fun sawtooth(teeth: Int): (Double) -> DoubleArray =
      { x ->
        val across = x.coerceIn(0.0, 1.0) * teeth
        val code = TOOTH_LOW + (across - floor(across)) * (TOOTH_HIGH - TOOTH_LOW)
        doubleArrayOf(code, code, code)
      }
  }
}
