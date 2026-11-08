package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.cbCode
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.crCode
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.ffmpeg.support.lumaCode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a [Lut] on ffmpeg against [CubeLut.at] of the same picture drawn plain, in 8 bit limited codes.
 *
 * Reads spread over a ramp at mid-range codes, on luma and on both chroma planes, each to its own measured limit.
 */
@OptIn(InternalFilmstripApi::class)
class FfmpegLutMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition lut reads like the table`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Lut(CURVED))), 0)
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      assertLanded(frame, plain, CURVED)
    }

  // 4:2:0 holds one chroma sample over four pixels, so each channel is read as RGBA with that spread on top of luma's.
  @Test
  fun `every channel reads like the table`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.frames(draw.composition(listOf(clip), listOf(Lut(CURVED))), Duration.ZERO, 1).single()
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      var worst = 0.0
      READ_FRACTIONS.forEach { across ->
        val x = (across * WIDTH).toInt()
        val y = HEIGHT / 2
        val want = CURVED.at(code(plain, WIDTH, x, y, 0), code(plain, WIDTH, x, y, 1), code(plain, WIDTH, x, y, 2))
        (0 until RGB).forEach { channel ->
          worst = max(worst, abs(frame.channelAt(WIDTH, x, y, channel) / FULL - want[channel]) * FULL)
        }
      }
      withClue("the worst channel misses by $worst") { worst shouldBeLessThan LUT_CHANNEL_CODES }
    }

  @Test
  fun `a clip lut reads like the table`() =
    runBlocking<Unit> {
      val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(Lut(CURVED)))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt)

      val frame = draw.planesAt(draw.composition(listOf(filmed)), 0)
      val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

      assertLanded(frame, base, CURVED)
    }

  @Test
  fun `a mid intensity reads like the blended table`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Lut(CURVED, 0.5f))), 0)
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      assertLanded(frame, plain, CURVED.blended(0.5f))
    }

  @Test
  fun `a preview height reads the same table`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val small = ExportSpec(heightPx = HEIGHT / 2)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Lut(CURVED))), 0, small)
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1, small).single()

      frame.height shouldBe HEIGHT / 2
      assertLanded(frame, plain, CURVED)
    }

  // The table holds green at r*g*b on two points, which tetrahedral reads as the smallest of the three at this code.
  @Test
  fun `the table reads trilinearly and not tetrahedrally`() =
    runBlocking<Unit> {
      val colour = doubleArrayOf(0.75, 0.5, 0.25)
      val clip = draw.clipOf(WIDTH, HEIGHT, { colour.copyOf() })
      val cross = cubeOf(2) { red, green, blue -> doubleArrayOf(red, red * green * blue, blue) }
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Lut(cross))), 0)
      val plain = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, 1).single()

      val tetrahedral = cubeAtTetrahedral(plain, WIDTH)
      val want = cross.at(0.75f, 0.5f, 0.25f)[1]
      withClue("the two readings have to sit apart for this to tell") {
        abs(tetrahedral - want).toDouble() * FULL shouldBeGreaterThan LUT_CODES * 4
      }
      assertLanded(frame, plain, cross)
    }

  @Test
  fun `a near identity table leaves codes within the limit`() =
    runBlocking<Unit> {
      val near = cubeOf(3) { red, green, blue -> doubleArrayOf(red + 1e-3, green, blue) }
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      near.isIdentity shouldBe false

      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Lut(near))), 0)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)

      READ_FRACTIONS.forEach { fraction ->
        val x = (fraction * WIDTH).toInt()
        val miss = abs(frame.luma(x, HEIGHT / 2) - plain.luma(x, HEIGHT / 2)).toDouble()
        withClue("the near identity table moved column $x by $miss") { miss shouldBeLessThan LUT_CODES }
      }
    }

  @Test
  fun `a clip lut after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val filled = Fill.Solid(SLATE)
      val width = SQUARE * 16 / 9
      val clip = draw.clipOf(SQUARE, SQUARE, ::rampAt, listOf(crop, Lut(CURVED)))
      val plainClip = draw.clipOf(SQUARE, SQUARE, ::rampAt, listOf(crop))

      val frame = draw.frames(draw.composition(listOf(clip), fill = filled), Duration.ZERO, 1).single()
      val plain = draw.frames(draw.composition(listOf(plainClip), fill = filled), Duration.ZERO, 1).single()

      val y = SQUARE / 2
      (0 until RGB).forEach { channel ->
        withClue("the bar holds the fill, channel $channel") {
          abs(
            frame.channelAt(width, width / BAR_READ, y, channel) - plain.channelAt(width, width / BAR_READ, y, channel),
          ).shouldBeLessThan(BAR_CODES)
        }
      }
      val middle = width / 2
      val want =
        CURVED.at(
          code(plain, width, middle, y, 0),
          code(plain, width, middle, y, 1),
          code(plain, width, middle, y, 2),
        )
      (0 until RGB).forEach { channel ->
        withClue("the picture takes the table, channel $channel") {
          abs(frame.channelAt(width, middle, y, channel) / FULL - want[channel]) * FULL shouldBeLessThan
            LUT_CHANNEL_CODES
        }
      }
    }

  @Test
  fun `a composition lut reaches the bars`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val filled = Fill.Solid(SLATE)

      val frame =
        draw
          .frames(
            draw.composition(listOf(clip), listOf(crop, Lut(CURVED)), filled),
            Duration.ZERO,
            1,
          ).single()
      val plain =
        draw
          .frames(
            draw.composition(listOf(clip), listOf<EffectSpec>(crop), filled),
            Duration.ZERO,
            1,
          ).single()

      val size = HEIGHT
      val y = size / 2
      val want =
        CURVED.at(
          code(plain, size, size / 2, y, 0),
          code(plain, size, size / 2, y, 1),
          code(
            plain,
            size,
            size / 2,
            y,
            2,
          ),
        )
      abs(frame.channelAt(size, size / 2, y, 0) / FULL - want[0]) * FULL shouldBeLessThan LUT_CHANNEL_CODES
    }

  @Test
  fun `a clip lut lands on the table from a ten bit source`() =
    runBlocking<Unit> { assertClipScopeReadsTable("yuv420p10le", "tv") }

  // Film and CRT open on the same conversion as a Lut, so these two read its range and matrix for all three looks.
  @Test
  fun `a clip lut lands on the table from a full range source`() =
    runBlocking<Unit> { assertClipScopeReadsTable("yuv420p", "pc") }

  @Test
  fun `a clip lut lands on the table from an rgb source`() =
    runBlocking<Unit> { assertClipScopeReadsTable("gbrp", "pc") }

  @Test
  fun `a clip lut on an sd clip draws over the corrected colour`() =
    runBlocking<Unit> {
      val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(Lut(CURVED)), onSmpteC = true)
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt, onSmpteC = true)
      val composition = draw.composition(listOf(filmed))

      val frame = draw.planesAt(composition, 0)
      val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

      assertLanded(frame, base, CURVED)
      val graph = draw.graphOf(composition)
      graph shouldContain "colorspace=ispace"
      withClue("the head runs ahead of the table") {
        graph.indexOf("colorspace=ispace") shouldBeLessThan graph.indexOf("lut3d=")
      }
    }

  @Test
  fun `the fragment asks for trilinear and names the matrix each way`() =
    runBlocking<Unit> {
      val graph = draw.graphOf(draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(Lut(CURVED))))

      graph shouldContain "interp=trilinear"
      graph shouldContain "in_color_matrix=bt709"
      graph shouldContain "out_color_matrix=bt709"
    }

  @Test
  fun `an export writes what a preview draws`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val composition = draw.composition(listOf(clip), listOf(Lut(CURVED)))

      val written = draw.exportedFrame(composition, READ_SLOT, ExportSpec())
      val preview = draw.frames(composition, slotTime(READ_SLOT), 1).single()

      val x = WIDTH / 2
      val y = HEIGHT / 2
      abs(written.channelAt(WIDTH, x, y, 1) - preview.channelAt(WIDTH, x, y, 1)) shouldBeLessThan EXPORT_CODES
    }

  // A picture ending ahead of its sound holds its last frame over the slots left, and the look holds with it.
  @Test
  fun `a clip lut runs under a held tail`() =
    runBlocking<Unit> {
      val short = indexedClip(GRID_RATE, 1.seconds, blue = 200, sound = 1.seconds + 30.milliseconds)
      val next = indexedClip(GRID_RATE, 2.seconds, blue = 60)
      val sources = listOf(short, next).map { MediaSource.of(it.absolutePath) }
      val nextClip = Clip(sources[1], trim = TimeRange.of(Duration.ZERO, 1.seconds))
      val spec = ExportSpec(frameRate = GRID_RATE)

      val frame =
        draw.exportedFrame(
          draw.composition(listOf(Clip(sources[0], effects = listOf(Lut(CURVED))), nextClip)),
          HELD_SLOT,
          spec,
        )
      val plain = draw.exportedFrame(draw.composition(listOf(Clip(sources[0]), nextClip)), HELD_SLOT, spec)

      val x = GRID_WIDTH / 2
      val y = GRID_HEIGHT / 2
      val want =
        CURVED.at(
          code(plain, GRID_WIDTH, x, y, 0),
          code(plain, GRID_WIDTH, x, y, 1),
          code(plain, GRID_WIDTH, x, y, 2),
        )
      (0 until RGB).forEach { channel ->
        withClue("the held slot still holds the table's picture, channel $channel") {
          abs(frame.channelAt(GRID_WIDTH, x, y, channel) / FULL - want[channel]) * FULL shouldBeLessThan
            EXPORT_CODES.toDouble()
        }
      }
    }

  private suspend fun assertClipScopeReadsTable(
    pixelFormat: String,
    range: String,
  ) {
    val filmed = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(Lut(CURVED)), pixelFormat, range)
    val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt, pixelFormat = pixelFormat, range = range)

    val frame = draw.planesAt(draw.composition(listOf(filmed)), 0)
    val base = draw.frames(draw.composition(listOf(plain)), Duration.ZERO, 1).single()

    assertLanded(frame, base, CURVED)
  }

  private fun assertLanded(
    frame: Yuv420Frame,
    plain: ByteArray,
    table: CubeLut,
  ) {
    val width = frame.width
    val height = frame.height
    var luma = 0.0
    var chroma = 0.0
    var change = 0.0
    READ_FRACTIONS.forEach { across ->
      READ_FRACTIONS.forEach { down ->
        val x = (across * width).toInt()
        val y = (down * height).toInt()
        val read =
          table
            .at(code(plain, width, x, y, 0), code(plain, width, x, y, 1), code(plain, width, x, y, 2))
            .map { it.toDouble() }
            .toDoubleArray()
        val untouched = DoubleArray(RGB) { plain.channelAt(width, x, y, it) / FULL }
        luma = max(luma, abs(frame.luma(x, y) - lumaCode(read)))
        chroma = max(chroma, abs(frame.cb(x / 2, y / 2) - cbCode(read)))
        chroma = max(chroma, abs(frame.cr(x / 2, y / 2) - crCode(read)))
        change = max(change, abs(frame.luma(x, y) - lumaCode(untouched)))
      }
    }
    withClue("luma misses by $luma, chroma by $chroma, the table moved luma by $change") {
      change shouldBeGreaterThan LUT_CODES * 2
      luma shouldBeLessThan LUT_CODES
      chroma shouldBeLessThan LUT_CHROMA_CODES
    }
  }

  private fun code(
    plain: ByteArray,
    width: Int,
    x: Int,
    y: Int,
    channel: Int,
  ): Float = (plain.channelAt(width, x, y, channel) / FULL).toFloat()

  // What a tetrahedral read of the r*g*b table gives on the colour a plain frame holds, which is its smallest channel.
  private fun cubeAtTetrahedral(
    plain: ByteArray,
    width: Int,
  ): Float {
    val x = width / 2
    val y = HEIGHT / 2
    return minOf(code(plain, width, x, y, 0), code(plain, width, x, y, 1), code(plain, width, x, y, 2))
  }

  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.25 + 0.45 * x, 0.65 - 0.3 * x, 0.35 + 0.2 * x)

  private fun slotTime(slot: Int): Duration = Duration.parse("PT${slot}S") / FRAME_RATE

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val SQUARE = 720
    const val GRID_RATE = 25
    const val GRID_WIDTH = 320
    const val GRID_HEIGHT = 180
    const val HELD_SLOT = 25
    const val RGB = 3
    const val BAR_READ = 16
    const val READ_SLOT = 6
    const val FRAME_RATE = 30
    const val FULL = 255.0
    const val SLATE = 0xFF405060.toInt()
    const val BAR_CODES = 2
    const val EXPORT_CODES = 8

    // Worst measured on ffmpeg 8.0.1 x86_64 over this class: 1.72 on luma, 1.17 on chroma, 2.59 on a channel read as
    // RGBA.
    const val LUT_CODES = 2.0
    const val LUT_CHROMA_CODES = 1.5
    const val LUT_CHANNEL_CODES = 3.0
    val READ_FRACTIONS = listOf(0.1, 0.3, 0.5, 0.7, 0.9)
    val CURVED =
      cubeOf(5) { red, green, blue ->
        doubleArrayOf(
          red * red * 0.7 + blue * 0.2,
          green * (1.0 - red * 0.4) + blue * blue * 0.1,
          (1.0 - blue) * 0.5 + green * red * 0.4,
        )
      }

    fun cubeOf(
      size: Int,
      entry: (red: Double, green: Double, blue: Double) -> DoubleArray,
    ): CubeLut =
      CubeLut.parse(
        buildString {
          append("LUT_3D_SIZE ").append(size).append('\n')
          val last = (size - 1).toDouble()
          for (blue in 0 until size) {
            for (green in 0 until size) {
              for (red in 0 until size) {
                val row = entry(red / last, green / last, blue / last)
                append(row[0])
                  .append(' ')
                  .append(row[1])
                  .append(' ')
                  .append(row[2])
                  .append('\n')
              }
            }
          }
        },
      )
  }
}
