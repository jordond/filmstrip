package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.CRT_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.CRT_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.CrtMiss
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.crtLit
import dev.jordond.filmstrip.ffmpeg.support.crtMissOf
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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Crt] straight off the preview pump against the signal, with no encoder in the
 * way, and what it reaches at each scope.
 *
 * Frames are read as the planes the graph writes, since the way back to 4:2:0 is where chroma leaves the signal.
 */
class FfmpegCrtMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition crt lands on the signal`() = runBlocking<Unit> { assertCompositionLands(WIDTH, HEIGHT) }

  @Test
  fun `a clip crt lands on the signal`() =
    runBlocking<Unit> {
      val crt = Crt()
      val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(crt))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.planesAt(draw.composition(listOf(filmed)), 0)
      val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

      assertLands(crtMissOf(frame, base, crt))
    }

  @Test
  fun `a 720p frame lands on the signal`() = runBlocking<Unit> { assertCompositionLands(LARGE_WIDTH, LARGE_HEIGHT) }

  @Test
  fun `a 1080p frame lands on the signal at a mid intensity`() =
    runBlocking<Unit> { assertCompositionLands(FULL_HD_WIDTH, FULL_HD_HEIGHT, MID) }

  @Test
  fun `a frame odd to the patterns lands on the signal`() =
    runBlocking<Unit> { assertCompositionLands(UNEVEN_WIDTH, UNEVEN_HEIGHT) }

  @Test
  fun `a tall frame lands on the signal`() = runBlocking<Unit> { assertCompositionLands(HEIGHT, WIDTH) }

  @Test
  fun `a bright picture stops at full scale and lands on the signal`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val clip = draw.clipOf(FULL_HD_WIDTH, FULL_HD_HEIGHT, { WHITE.copyOf() })
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(crt)), 0)
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      val miss = crtMissOf(frame, plain, crt)
      withClue("chroma ${miss.chroma}") { miss.chroma shouldBeLessThan CRT_CHROMA_CODES }
      withClue("luma ${miss.luma}") { miss.luma shouldBeLessThan CRT_LUMA_CODES }
    }

  @Test
  fun `the picture ends in black on each side of the curve`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val clip = draw.clipOf(LARGE_WIDTH, LARGE_HEIGHT, { BRIGHT.copyOf() })
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(crt)), 0)
      val k = CrtParams.of(crt).barrelK

      val column = (0 until LARGE_WIDTH).first { crtLit(it, LARGE_HEIGHT / 2, LARGE_WIDTH, LARGE_HEIGHT, k) }
      val row = (0 until LARGE_HEIGHT).first { crtLit(LARGE_WIDTH / 2, it, LARGE_WIDTH, LARGE_HEIGHT, k) }
      withClue("the curve has to leave a margin for this to tell") { (column > 1 && row > 1) shouldBe true }

      abs(frame.luma(column - 1, LARGE_HEIGHT / 2) - BLACK_LUMA) shouldBeLessThan CLEAR_LUMA
      abs(frame.luma(LARGE_WIDTH / 2, row - 1) - BLACK_LUMA) shouldBeLessThan CLEAR_LUMA
      abs(frame.luma(0, 0) - BLACK_LUMA) shouldBeLessThan CLEAR_LUMA
      frame.luma(column + 2, LARGE_HEIGHT / 2) shouldBeBrighterThan LIT_LUMA
      frame.luma(LARGE_WIDTH / 2, row + 2) shouldBeBrighterThan LIT_LUMA
    }

  @Test
  fun `a smaller preview is the larger frame boxed down at even and at wide factors`() =
    runBlocking<Unit> {
      assertBoxedDown(LARGE_WIDTH, LARGE_HEIGHT, factor = 2)
      assertBoxedDown(FULL_HD_WIDTH, FULL_HD_HEIGHT, factor = 3)
    }

  @Test
  fun `a clip crt after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val clip = draw.clipOf(SQUARE, SQUARE, { BRIGHT.copyOf() }, listOf(crop, crt))
      val frame =
        draw.frames(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, 1).single()
      val width = SQUARE * 16 / 9
      val height = SQUARE

      withClue("the bar's edge holds the fill past the curve, not the black a picture ends in") {
        assertFill(frame, width, 0, height / 2)
        assertFill(frame, width, width - 1, height / 4)
      }
      withClue("the bar next to the picture holds the fill") { assertFill(frame, width, width / BAR_READ, height / 2) }
      val k = CrtParams.of(crt).barrelK
      val row = (0 until height).first { crtLit(width / 2, it, width, height, k) }
      withClue("the picture's own top edge ends in black") {
        (row > 0) shouldBe true
        (0 until RGB).forEach { frame.channelAt(width, width / 2, row - 1, it) shouldBeLessThan CLEAR_LUMA.toInt() + 1 }
      }
    }

  // The canvas holds clear bars either side of the picture, so a read that lands between the two weighs by alpha. A
  // read of straight colour would take the bar's black in on top of its clear, and land a quarter of the step low at a
  // mix of one half. Luma is read since 4:2:0 holds one chroma sample across that edge.
  @Test
  fun `a clip crt read between the picture and a clear bar weighs by alpha`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val clip = draw.clipOf(SQUARE, SQUARE, { BRIGHT.copyOf() }, listOf(crop, crt))
      val frame = draw.planesAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), 0)
      val width = SQUARE * 16 / 9
      val height = SQUARE
      val params = CrtParams.of(crt)
      val pictureLeft = (width - SQUARE) / 2

      val (x, y, mix) =
        (height / 3 until 2 * height / 3)
          .flatMap { row ->
            (0 until width / 2).map { column ->
              val u = (column + 0.5) / width
              val source = CrtSignal.barrelMap(u, (row + 0.5) / height, width, height, params.barrelK)
              Triple(column, row, source[0] * width - (pictureLeft - 0.5))
            }
          }.first { (_, _, mix) -> mix in MIXED_READ }
      val shown =
        CrtSignal.pixelAt((x + 0.5) / width, (y + 0.5) / height, width, height, params) { column, _ ->
          if (column < pictureLeft) DoubleArray(RGB + 1) else BRIGHT + 1.0
        }
      val alpha = shown[RGB]
      val wanted = lumaCode(DoubleArray(RGB) { shown[it] * alpha + SLATE_CODES[it] / FULL * (1.0 - alpha) })

      withClue("column $x at a mix of $mix") {
        frame.luma(x, y).toDouble() shouldBe (wanted plusOrMinus MIXED_CODES)
      }
    }

  // Each clip draws on a map of its own size. The square clip draws 1:1 between the bars, and starts past zero.
  @Test
  fun `a track crt on clips of different sizes lands each on the signal for its own frame`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val wide = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() })
      val square = draw.clipOf(SQUARE, SQUARE, { GREY.copyOf() })
      val composition = Composition(listOf(Track(listOf(wide, square), effects = listOf(crt))))

      val first = draw.planesAt(composition, READ_SLOT)
      val second = draw.planesAt(composition, 2 * RATE + READ_SLOT)

      withClue("a size of its own has its own frames") { movies(composition) shouldBe FRAMES_OF_ONE_SIZE * 2 }
      first.width shouldBe WIDTH
      assertLandsAt(first, crt, Size(WIDTH, HEIGHT), left = 0)
      assertLandsAt(second, crt, Size(SQUARE, SQUARE), left = (WIDTH - SQUARE) / 2)
    }

  @Test
  fun `clips of one size read one set of frames`() =
    runBlocking<Unit> {
      val clips = List(3) { draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() }) }
      val composition = Composition(listOf(Track(clips, effects = listOf(Crt(1f)))))

      movies(composition) shouldBe FRAMES_OF_ONE_SIZE
      val frame = draw.planesAt(composition, 4 * RATE + READ_SLOT)

      assertLandsAt(frame, Crt(1f), Size(WIDTH, HEIGHT), left = 0)
    }

  // Coded at half the width, so a map of the display size has to be met by a picture of the display size.
  @Test
  fun `a clip crt on non-square pixels lands on the signal at the display size`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val still = File.createTempFile("crt-anamorphic", ".mkv").apply { deleteOnExit() }
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
          "color=c=0x787878:s=${WIDTH / 2}x$HEIGHT:r=$RATE:d=1,setsar=2",
          "-c:v",
          "ffv1",
          "-pix_fmt",
          "yuv420p",
          still.path,
        ).inheritIO().start()
      encode.waitFor() shouldBe 0
      val clip = Clip(MediaSource.of(still.path), effects = listOf(crt))

      val frame = draw.planesAt(draw.composition(listOf(clip)), 0)

      frame.width shouldBe WIDTH
      assertLandsAt(frame, crt, Size(WIDTH, HEIGHT), left = 0, picture = PLAIN_GREY)
    }

  @Test
  fun `a composition crt reaches the bars`() =
    runBlocking<Unit> {
      val crt = Crt()
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT.copyOf() })
      val fill = Fill.Solid(SLATE)

      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(crop, crt), fill), 0)
      val plain = draw.frames(draw.composition(listOf(clip), listOf<EffectSpec>(crop), fill), Duration.ZERO, 1).single()

      assertLands(crtMissOf(frame, plain, crt))
    }

  @Test
  fun `an overlay at the same scope stays clean and a clip overlay under a composition crt is burned`() =
    runBlocking<Unit> {
      val crt = Crt(1f)
      val clip = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() })
      val x = (SWATCH_X * WIDTH).toInt()
      val y = (SWATCH_Y * HEIGHT).toInt()
      val swatched = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() }, listOf(swatch()))

      val clean = rgbaOf(draw.composition(listOf(clip), listOf(swatch(), crt)))
      val plain = rgbaOf(draw.composition(listOf(swatched)))
      val burned = rgbaOf(draw.composition(listOf(swatched), listOf(crt)))

      withClue("clean") {
        abs(clean.channelAt(WIDTH, x, y, 0) - plain.channelAt(WIDTH, x, y, 0)) shouldBeLessThan SWATCH_CODES
      }
      withClue("burned") {
        abs(burned.channelAt(WIDTH, x, y, 0) - plain.channelAt(WIDTH, x, y, 0)) shouldBeGreaterThanCodes SWATCH_CODES
      }
    }

  @Test
  fun `a frame from a windowed preview equals the same frame from the start`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(Crt(MID)))

      val fromStart = draw.frameAt(composition, Duration.ZERO, READ_SLOT)
      val fromWindow = draw.frames(composition, slotTime(READ_SLOT), 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `an export writes what a preview draws`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, { BRIGHT.copyOf() })), listOf(Crt(1f)))
      val plain = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, { BRIGHT.copyOf() })))

      val written = draw.exportedFrame(composition, READ_SLOT, ExportSpec())
      val preview = draw.frames(composition, slotTime(READ_SLOT), 1).single()
      val untouched = draw.frames(plain, Duration.ZERO, 1).single()

      val x = WIDTH / 2
      // A dark scanline, which moves far past what an encode does to it.
      val y = HEIGHT / 2 + 1
      withClue("the look has to move this pixel past the encoder's reach for this to tell") {
        abs(preview.channelAt(WIDTH, x, y, 1) - untouched.channelAt(WIDTH, x, y, 1)) shouldBeGreaterThanCodes
          EXPORT_CODES
      }
      abs(written.channelAt(WIDTH, x, y, 1) - preview.channelAt(WIDTH, x, y, 1)) shouldBeLessThan EXPORT_CODES
    }

  private suspend fun assertCompositionLands(
    width: Int,
    height: Int,
    intensity: Float = 1f,
  ) {
    val crt = Crt(intensity)
    val clip = draw.clipOf(width, height, ::rampAt)
    val frame = draw.planesAt(draw.composition(listOf(clip), listOf(crt)), 0)
    val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

    assertLands(crtMissOf(frame, plain, crt))
  }

  // The frame the look drew on sits at [left] of this frame's width, as flat picture drawn 1:1.
  private fun assertLandsAt(
    read: Yuv420Frame,
    crt: Crt,
    drawn: Size,
    left: Int,
    picture: DoubleArray = GREY,
  ) {
    val params = CrtParams.of(crt)
    (0 until drawn.height step TRACK_STRIDE).forEach { y ->
      (0 until drawn.width step TRACK_STRIDE).forEach { x ->
        val shown =
          CrtSignal.pixelAt(
            (x + 0.5) / drawn.width,
            (y + 0.5) / drawn.height,
            drawn.width,
            drawn.height,
            params,
          ) { _, _ ->
            picture
          }
        withClue("${drawn.width} by ${drawn.height} at $x $y") {
          abs(read.luma(left + x, y) - lumaCode(shown)) shouldBeLessThan TRACK_LUMA_CODES
        }
      }
    }
  }

  private suspend fun movies(composition: Composition): Int = Regex("movie=").findAll(draw.graphOf(composition)).count()

  private suspend fun rgbaOf(composition: Composition): ByteArray = draw.frames(composition, Duration.ZERO, 1).single()

  private fun assertLands(miss: CrtMiss) {
    withClue("luma ${miss.luma}, chroma ${miss.chroma}, change ${miss.change}") {
      miss.change shouldBeGreaterThan CRT_LUMA_CODES * 2
      miss.luma shouldBeLessThan CRT_LUMA_CODES
      miss.chroma shouldBeLessThan CRT_CHROMA_CODES
    }
  }

  private suspend fun assertBoxedDown(
    width: Int,
    height: Int,
    factor: Int,
  ) {
    val crt = Crt(MID)
    val clip = draw.clipOf(width, height, { GREY.copyOf() })
    val composition = draw.composition(listOf(clip), listOf(crt))

    val large = draw.planesAt(composition, 0)
    val preview = draw.planesAt(composition, 0, ExportSpec(heightPx = height / factor))

    preview.width shouldBe width / factor
    var worst = 0.0
    var worstPoint = 0.0
    parityReads(preview.height).forEach { row ->
      parityReads(preview.width).forEach { column ->
        var sum = 0.0
        for (dy in 0 until factor) {
          for (dx in 0 until factor) sum += large.luma(column * factor + dx, row * factor + dy)
        }
        val read = preview.luma(column, row).toDouble()
        worst = max(worst, abs(read - sum / (factor * factor)))
        worstPoint = max(worstPoint, abs(read - large.luma(column * factor, row * factor)))
      }
    }
    withClue("a nearest read of the large frame has to miss for this to tell, it reads $worstPoint") {
      worstPoint shouldBeGreaterThan PARITY_CODES * 2
    }
    withClue("$width by $height at a factor of $factor") { worst shouldBeLessThan PARITY_CODES }
  }

  private fun parityReads(size: Int): List<Int> {
    val margin = size / PARITY_MARGIN_OF_SIZE
    return ((0 until size step PARITY_STRIDE) + (0 until margin) + (size - margin until size)).distinct()
  }

  private fun assertFill(
    frame: ByteArray,
    width: Int,
    x: Int,
    y: Int,
  ) {
    (0 until RGB).forEach { channel ->
      withClue("channel $channel") {
        frame.channelAt(width, x, y, channel).toDouble() shouldBe (SLATE_CODES[channel] plusOrMinus BAR_CODES)
      }
    }
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), PALE_SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private infix fun Int.shouldBeBrighterThan(floor: Int) = this shouldBeGreaterThanCodes floor

  private infix fun Int.shouldBeGreaterThanCodes(floor: Int) {
    withClue("$this has to pass $floor") { (this > floor) shouldBe true }
  }

  // A gentle ramp on each channel, sitting well off clipping under the gain, stored by column.
  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.25 + 0.45 * x, 0.65 - 0.3 * x, 0.35 + 0.2 * x)

  private fun slotTime(slot: Int): Duration = (slot.toDouble() / RATE).seconds

  private companion object {
    const val MID = 0.5f
    const val WIDTH = 640
    const val HEIGHT = 360
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val SQUARE = 360
    const val RGB = 3
    const val RATE = 30
    const val READ_SLOT = 12
    val WHITE = doubleArrayOf(1.0, 1.0, 1.0)
    val BRIGHT = doubleArrayOf(0.745, 0.725, 0.706)
    val GREY = doubleArrayOf(0.47, 0.47, 0.47)
    const val SLATE = 0xFF204060.toInt()
    val SLATE_CODES = listOf(0x20.toDouble(), 0x40.toDouble(), 0x60.toDouble())
    const val BAR_CODES = 2.0
    const val BAR_READ = 8
    val MIXED_READ = 0.35..0.65

    // Rounding through gbrap and the overlay, well under what a straight read loses.
    const val MIXED_CODES = 4.0

    const val PARITY_CODES = 3.0
    const val PARITY_STRIDE = 7
    const val PARITY_MARGIN_OF_SIZE = 16

    const val BLACK_LUMA = 16
    const val CLEAR_LUMA = 2
    const val LIT_LUMA = 100

    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val PALE_SWATCH_ARGB = 0xFFE0E0E0.toInt()
    const val SWATCH_X = 0.15
    const val SWATCH_Y = 0.2
    const val SWATCH_CODES = 4

    const val EXPORT_CODES = 5

    const val FRAMES_OF_ONE_SIZE = 8
    const val TRACK_STRIDE = 5

    // Past the conversions either way on a flat picture, and a picture of a read of a flat clip through swscale.
    const val TRACK_LUMA_CODES = 3.0
    val PLAIN_GREY = doubleArrayOf(0x78 / 255.0, 0x78 / 255.0, 0x78 / 255.0)
  }
}
