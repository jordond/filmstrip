package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.camcorder.OsdCorner
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.camcorder.OsdRaster
import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.DigicamSignal
import dev.jordond.filmstrip.effects.filters.digicam.digicam
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.CAMCORDER_CLEAR
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertDigicamLanded
import dev.jordond.filmstrip.ffmpeg.support.assertFormsAgree
import dev.jordond.filmstrip.ffmpeg.support.assertOnCamcorderRaster
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.chromaCodesOf
import dev.jordond.filmstrip.ffmpeg.support.digicamReads
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.planesAtExactTicks
import dev.jordond.filmstrip.ffmpeg.support.readPointsOf
import dev.jordond.filmstrip.ffmpeg.support.samplesWhollyOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Digicam] off the graph's own planes against [DigicamSignal], and its live noise
 * planes against the `geq` that draws the same map.
 *
 * The pictures are a ramp across the left half beside flat grey, which a lossless clip holds exactly. The halo is read
 * either side of the edge between them on a frame 1280 wide, where its blur is a pixel across, and the noise on a
 * frame 720 high, where a cell is one chroma sample.
 */
class FfmpegDigicamMediaTest {
  private val draw = FilterFrames()
  private val bright by lazy { draw.clipOf(WIDTH, HEIGHT, ::brightAt) }
  private val dark by lazy { draw.clipOf(WIDTH, HEIGHT, ::darkAt) }

  @Test
  fun `a composition digicam sharpens and clips on the signal at mid controls`() =
    runBlocking<Unit> {
      assertDigicamLanded(
        draw.planesAt(draw.composition(listOf(bright), listOf(QUIET)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(bright)), READ_SLOT),
        QUIET,
        READ_TICK,
        EDGE_READS,
      )
    }

  @Test
  fun `a clip digicam sharpens and clips on the signal at mid controls`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::brightAt, listOf(QUIET))
      assertDigicamLanded(
        draw.planesAt(draw.composition(listOf(clip)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(bright)), READ_SLOT),
        QUIET,
        READ_TICK,
        EDGE_READS,
      )
    }

  @Test
  fun `a read tells another sharpness or clip from the look that was drawn`() =
    runBlocking<Unit> {
      val frame = draw.planesAt(draw.composition(listOf(bright), listOf(QUIET)), READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(bright)), READ_SLOT)

      listOf(
        Digicam(sharpness = 0.25f, noise = 0f, clip = 0.5f),
        Digicam(sharpness = 0.75f, noise = 0f, clip = 0.5f),
        Digicam(sharpness = 0.5f, noise = 0f, clip = 0.25f),
      ).forEach { wrong ->
        withClue("$wrong read against $QUIET") {
          digicamReads(frame, plain, wrong, READ_TICK, EDGE_READS).any { it.miss >= it.limit } shouldBe true
        }
      }
    }

  @Test
  fun `noise alone lands each cell on the signal`() =
    runBlocking<Unit> {
      assertDigicamLanded(
        draw.planesAt(draw.composition(listOf(dark), listOf(NOISY)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(dark)), READ_SLOT),
        NOISY,
        READ_TICK,
        GRID_READS,
      )
    }

  @Test
  fun `the default look weighs its noise by the picture after the clip`() =
    runBlocking<Unit> {
      val look = Digicam()
      assertDigicamLanded(
        draw.planesAt(draw.composition(listOf(dark), listOf(look)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(dark)), READ_SLOT),
        look,
        READ_TICK,
        GRID_READS,
      )
    }

  @Test
  fun `full noise on a picture near black holds each channel at black`() =
    runBlocking<Unit> {
      val look = Digicam(sharpness = 0f, noise = 1f, clip = 0f)
      assertDigicamLanded(
        draw.planesAt(draw.composition(listOf(dark), listOf(look)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(dark)), READ_SLOT),
        look,
        READ_TICK,
        GRID_READS,
      )
    }

  @Test
  fun `a read tells another seed tick or noise from the look that was drawn`() =
    runBlocking<Unit> {
      val frame = draw.planesAt(draw.composition(listOf(dark), listOf(NOISY)), READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(dark)), READ_SLOT)

      withClue("another seed") {
        digicamReads(frame, plain, NOISY, READ_TICK, GRID_READS, seed = NOISY.seed + 1)
          .any { it.miss >= it.limit } shouldBe true
      }
      withClue("the tick after") {
        digicamReads(frame, plain, NOISY, READ_TICK + 1, GRID_READS).any { it.miss >= it.limit } shouldBe true
      }
      withClue("half the noise") {
        val wrong = Digicam(sharpness = 0f, noise = 0.25f, clip = 0f)
        digicamReads(frame, plain, wrong, READ_TICK, GRID_READS).any { it.miss >= it.limit } shouldBe true
      }
    }

  @Test
  fun `a smaller preview draws the noise in cells of its own size`() =
    runBlocking<Unit> {
      val spec = ExportSpec(heightPx = HEIGHT / 2)
      val frame = draw.planesAt(draw.composition(listOf(dark), listOf(NOISY)), READ_SLOT, spec)
      val plain = draw.planesAt(draw.composition(listOf(dark)), READ_SLOT, spec)

      withClue("the preview's cell has to be smaller than the export's for this to tell") {
        DigicamSignal.cellPx(frame.height) shouldBe DigicamSignal.cellPx(HEIGHT) / 2
      }
      assertDigicamLanded(frame, plain, NOISY, READ_TICK, gridOf(frame.width, frame.height))
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(dark), listOf(Digicam()))
      val at = (SEEK_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, SEEK_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()
      val first = draw.frames(composition, Duration.ZERO, 1).single()

      withClue("the noise has to differ from the first frame's for this to tell") {
        fromStart.contentEquals(first) shouldBe false
      }
      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip digicam after a contain crop leaves the bar as the fill`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(SMALL_WIDTH, SMALL_HEIGHT, ::brightAt, listOf(crop, Digicam.Ccd))
      val frame = draw.frameAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, READ_SLOT)

      listOf(SMALL_HEIGHT / BAR_READ, SMALL_HEIGHT - SMALL_HEIGHT / BAR_READ).forEach { row ->
        (0 until 3).forEach { channel ->
          withClue("row $row channel $channel") {
            frame.channelAt(SMALL_HEIGHT, SMALL_HEIGHT / 2, row, channel).toDouble() shouldBe
              (SLATE_CODES[channel] plusOrMinus BAR_CODES)
          }
        }
      }
    }

  @Test
  fun `the stamp sits clean over the noise in the bottom right`() =
    runBlocking<Unit> {
      val effects = EffectsBuilder().digicam { stamp() }.build()
      val stamp = effects.filterIsInstance<Camcorder>().single()
      val raster = rasterOf(stamp)

      assertOnCamcorderRaster(
        draw.planesAt(draw.composition(listOf(dark), effects), READ_SLOT),
        draw.planesAt(draw.composition(listOf(dark), effects.filterIsInstance<Digicam>()), READ_SLOT),
        raster,
      )
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          if (raster.argbAt(x, y) == CAMCORDER_CLEAR) continue
          withClue("a lit pixel at ($x, $y)") {
            x shouldBeGreaterThanOrEqual WIDTH / 2
            y shouldBeGreaterThanOrEqual HEIGHT / 2
          }
        }
      }
    }

  @Test
  fun `a stamp wide enough to hold a chroma sample reads its own orange over the noise`() =
    runBlocking<Unit> {
      val stamp = Camcorder(listOf(OsdElement.Stamp(OsdCorner.BottomRight)), textSize = BOLD_TEXT)
      val raster = rasterOf(stamp)
      val frame = draw.planesAt(draw.composition(listOf(dark), listOf(Digicam(), stamp)), READ_SLOT)

      val colours =
        (0 until HEIGHT)
          .flatMap { y -> (0 until WIDTH).map { x -> raster.argbAt(x, y) } }
          .filter { it != CAMCORDER_CLEAR }
          .distinct()
      withClue("the stamp draws in one colour with no edge") { colours.size shouldBe 1 }
      val (cb, cr) = chromaCodesOf(colours.single()).let { it[0] to it[1] }
      withClue("the stamp has to be a colour for its chroma to tell") { abs(cr - cb) shouldBeGreaterThanOrEqual TINT }
      val samples = samplesWhollyOf(raster, colours.single())
      withClue("a sample wholly inside the stamp") { samples.shouldNotBeEmpty() }
      samples.forEach { (column, row) ->
        withClue("Cb at ($column, $row)") { abs(frame.cb(column, row) - cb) shouldBeLessThanOrEqual CHROMA_CODES }
        withClue("Cr at ($column, $row)") { abs(frame.cr(column, row) - cr) shouldBeLessThanOrEqual CHROMA_CODES }
      }
    }

  @Test
  fun `a clip digicam at 720p draws the same noise live`() =
    runBlocking<Unit> { draw.assertFormsAgree(draw.composition(listOf(clip(WIDTH, HEIGHT, Digicam()))), frames = 12) }

  @Test
  fun `a frame a cell is one pixel of draws the same noise live`() =
    runBlocking<Unit> {
      DigicamSignal.cellPx(SMALL_HEIGHT) shouldBe 1
      draw.assertFormsAgree(draw.composition(listOf(clip(SMALL_WIDTH, SMALL_HEIGHT, Digicam.Ccd))), frames = 30)
    }

  @Test
  fun `a 1080p frame whose last cell the edge cuts draws the same noise live`() =
    runBlocking<Unit> {
      val look = Digicam(noise = 1f, seed = 7)
      draw.assertFormsAgree(draw.composition(listOf(clip(CUT_WIDTH, FULL_HD_HEIGHT, look))), frames = 6)
    }

  @Test
  fun `noise alone draws the same planes live`() =
    runBlocking<Unit> { draw.assertFormsAgree(draw.composition(listOf(clip(WIDTH, HEIGHT, NOISY))), frames = 12) }

  @Test
  fun `a composition digicam across two clips`() =
    runBlocking<Unit> {
      val plain = clip(SMALL_WIDTH, SMALL_HEIGHT)
      draw.assertFormsAgree(draw.composition(listOf(plain, plain), listOf(Digicam())), frames = 70)
    }

  @Test
  fun `a clip that opens at two seconds reads its own ticks`() =
    runBlocking<Unit> {
      val plan =
        draw.composition(listOf(clip(SMALL_WIDTH, SMALL_HEIGHT), clip(SMALL_WIDTH, SMALL_HEIGHT, Digicam())))
      draw.assertFormsAgree(plan, frames = 72)
    }

  @Test
  fun `the noise keeps the alpha that marks an unpainted bar`() =
    runBlocking<Unit> {
      val contain = Crop(AspectRatio.Square, fit = Fit.Contain)
      val plan =
        draw.composition(
          listOf(draw.clipOf(SMALL_WIDTH, SMALL_HEIGHT, ::rampAt, listOf(contain, Digicam()))),
          fill = Fill.Solid(SLATE),
        )
      draw.assertFormsAgree(plan, frames = 8)
    }

  @Test
  fun `a 29_97 clip past a long seek`() =
    runBlocking<Unit> {
      val clip =
        draw.clipOf(
          TINY_WIDTH,
          TINY_HEIGHT,
          ::rampAt,
          listOf(LOOK),
          rate = "30000/1001",
          seconds = 29,
          container = "mov",
        )
      draw.assertFormsAgree(draw.composition(listOf(clip)), SEEK_FRAMES, at = LONG_SEEK)
    }

  @Test
  fun `a 30 fps clip reads a new tick on every frame on both forms`() =
    runBlocking<Unit> {
      assertReadsExactTicks("30", "N")
      val clip = draw.clipOf(TINY_WIDTH, TINY_HEIGHT, ::rampAt, listOf(LOOK), rate = "30", container = "mov")
      val drawn = draw.formPlanes(draw.composition(listOf(clip)), TICK_FRAMES, live = true)
      val size = drawn.size / TICK_FRAMES
      val frames = List(TICK_FRAMES) { drawn.copyOfRange(it * size, (it + 1) * size).toList() }
      frames.zipWithNext().count { (one, next) -> one == next } shouldBe 0
    }

  @Test
  fun `a 60 fps clip holds each tick for two frames`() = runBlocking<Unit> { assertReadsExactTicks("60", "floor(N/2)") }

  @Test
  fun `a 24 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("24", "floor(N*30/24)") }

  @Test
  fun `a 29_97 clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("30000/1001", "floor(N*1001/1000)") }

  // The tick a frame's exact time gives, written over the frame's number so it can stand in for the look's clock.
  private suspend fun assertReadsExactTicks(
    rate: String,
    tick: String,
  ) {
    val clip = draw.clipOf(TINY_WIDTH, TINY_HEIGHT, ::rampAt, listOf(LOOK), rate = rate, container = "mov")
    val plan = draw.composition(listOf(clip))
    val want = draw.planesAtExactTicks(plan, TICK_FRAMES, tick)
    withClue("the drawn graph at $rate") {
      draw.formPlanes(plan, TICK_FRAMES, live = false).contentEquals(want) shouldBe true
    }
    withClue("the planes at $rate") {
      draw.formPlanes(plan, TICK_FRAMES, live = true).contentEquals(want) shouldBe true
    }
  }

  private fun rasterOf(stamp: Camcorder): OsdRaster =
    CamcorderOsd(stamp, FILTER_RATE.toFloat()).rasterAt(outputSlotTime(READ_SLOT.toLong(), FILTER_RATE), WIDTH, HEIGHT)

  private fun clip(
    width: Int,
    height: Int,
    digicam: Digicam? = null,
  ): Clip = draw.clipOf(width, height, ::rampAt, listOfNotNull(digicam))

  // A ramp whose red runs past where the mid clip gain cuts it off, beside a grey every channel of it steps down to.
  private fun brightAt(x: Double): DoubleArray =
    if (x < 0.5) doubleArrayOf(0.2 + 1.2 * x, 0.3 + 0.6 * x, 0.6 - 0.4 * x) else doubleArrayOf(0.35, 0.35, 0.35)

  // Shadows, where the noise is strongest and a move reaches past black.
  private fun darkAt(x: Double): DoubleArray =
    if (x < 0.5) doubleArrayOf(0.05 + 0.5 * x, 0.1 + 0.3 * x, 0.3 - 0.4 * x) else doubleArrayOf(0.2, 0.2, 0.2)

  private fun rampAt(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val SMALL_WIDTH = 640
    const val SMALL_HEIGHT = 360
    const val TINY_WIDTH = 160
    const val TINY_HEIGHT = 90

    // Two pixels short of 1920, so the last column of three pixel cells is cut.
    const val CUT_WIDTH = 1918
    const val FULL_HD_HEIGHT = 1080

    const val READ_SLOT = 12
    const val SEEK_SLOT = 40
    const val SEEK_FRAMES = 24
    const val TICK_FRAMES = 24
    val READ_TICK = DigicamSignal.tickAt(READ_SLOT.toDouble() / FILTER_RATE)
    val LONG_SEEK = 27_636_000.microseconds

    // The halo and the clip at the middle of their ranges, and the noise at the middle of its own.
    val QUIET = Digicam(sharpness = 0.5f, noise = 0f, clip = 0.5f)
    val NOISY = Digicam(sharpness = 0f, noise = 0.5f, clip = 0f)
    val LOOK = Digicam()

    // The usual reads plus the four columns either side of the edge, where the halo is.
    val EDGE_READS =
      readPointsOf(WIDTH, HEIGHT) +
        listOf(HEIGHT / 3, HEIGHT / 2).flatMap { y -> (-2..1).map { WIDTH / 2 + it to y } }
    val GRID_READS = gridOf(WIDTH, HEIGHT)

    // Reads spread over the frame on steps that share no factor with a cell.
    fun gridOf(
      width: Int,
      height: Int,
    ): List<Pair<Int, Int>> = (3 until height - 3 step 37).flatMap { y -> (3 until width - 3 step 41).map { it to y } }

    const val BAR_READ = 16
    const val BAR_CODES = 3.0
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())

    // Tall enough that a stroke of the stamp is wider than the scaler reads round a chroma sample.
    const val BOLD_TEXT = 0.15f
    const val CHROMA_CODES = 1
    const val TINT = 32
  }
}
