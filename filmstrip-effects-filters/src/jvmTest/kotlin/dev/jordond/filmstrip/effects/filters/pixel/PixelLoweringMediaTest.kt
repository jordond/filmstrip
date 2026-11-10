package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

/**
 * Reads what ffmpeg makes of [pixelCells] and [pixelFragment] against [PixelSignal.pixelAt].
 *
 * The cell reads run on a `gbrap` frame drawn by its own `geq`, with no conversion to 4:2:0 in the way, so every code
 * is one the signal reads too. The ffmpeg reads run [hostFfmpeg], the one the build declares as this suite's input.
 */
class PixelLoweringMediaTest {
  @Test
  fun `levels and dither land on the signal at a mid cell count`() {
    assertLandsOnTheSignal(LEVELS, FRAME)
  }

  @Test
  fun `a palette lands on the signal`() {
    assertLandsOnTheSignal(PALETTE, FRAME)
  }

  @Test
  fun `a sixteen colour palette lands on the signal`() {
    assertLandsOnTheSignal(SIXTEEN, FRAME)
  }

  @Test
  fun `a frame of half the size lands on the same cells`() {
    val half = Size(FRAME.width / 2, FRAME.height / 2)

    PixelSignal.columns(half.width, half.height, CELLS) shouldBe PixelSignal.columns(FRAME.width, FRAME.height, CELLS)
    assertLandsOnTheSignal(LEVELS, half)
  }

  @Test
  fun `the gather reads every cell's centre into a frame the size of the grid`() {
    val grid = pixelGrid(LEVELS, FRAME)
    grid shouldBe Size(PixelSignal.columns(FRAME.width, FRAME.height, CELLS), CELLS)

    val planes = render(graph(rampSource(FRAME), pixelGather(LEVELS, FRAME, FilterBranch.INPUT, CELLS_PAD)), "gbrap")

    planes.size shouldBe grid.width * grid.height * GBRAP_PLANES
    for (cellY in 0 until grid.height) {
      for (cellX in 0 until grid.width) {
        val x = PixelSignal.centreOf(cellX, grid.width, FRAME.width)
        val y = PixelSignal.centreOf(cellY, grid.height, FRAME.height)
        val wanted = listOf(x % CODES, y * 3 % CODES, (x + y) % CODES)
        for (channel in 0 until RGB) {
          withClue("cell $cellX $cellY channel $channel") {
            planes.code(grid, cellX, cellY, channel) shouldBe wanted[channel].toDouble()
          }
        }
      }
    }
  }

  @Test
  fun `a pixel centre on a cell edge lands in the cell after it`() {
    val frame = Size(30, 15)
    val pixel = Pixel(cells = 10, levels = 6, dither = 0.5f)
    val columns = PixelSignal.columns(frame.width, frame.height, pixel.cells)
    withClue("pixel 1 has to sit on an edge both ways for this to tell") {
      ((2 * 1 + 1) * columns % (2 * frame.width)) shouldBe 0
      ((2 * 1 + 1) * pixel.cells % (2 * frame.height)) shouldBe 0
    }
    PixelSignal.cellOf(1, columns, frame.width) shouldBe 1

    assertLandsOnTheSignal(pixel, frame)
  }

  @Test
  fun `a clip whose first frame lands before zero still reads its maps`() {
    assertLandsOnTheSignal(LEVELS, FRAME, BEFORE_ZERO)
  }

  @Test
  fun `a clip frame keeps its alpha through the way back to yuva420p`() {
    val frame = Size(320, 180)
    val source =
      "color=c=0x6080a0:s=${frame.width}x${frame.height}:d=0.04,format=yuva420p," +
        "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='if(lt(X,${frame.width / 2}),0,$HALF_ALPHA)'"
    val planes = render(graph(source, pixelFragment(LEVELS, frame).branches), "yuva420p")

    val alpha = planes.copyOfRange(frame.width * frame.height * 3 / 2, planes.size)
    val left = alpha[frame.height / 2 * frame.width + CLEAR_READ].toInt() and 0xFF
    val right = alpha[frame.height / 2 * frame.width + frame.width - CLEAR_READ].toInt() and 0xFF
    left shouldBe 0
    abs(right - HALF_ALPHA) shouldBeLessThanOrEqual 1
  }

  private fun assertLandsOnTheSignal(
    pixel: Pixel,
    frame: Size,
    retimed: String = "",
  ) {
    val planes =
      render(graph(rampSource(frame) + retimed, pixelCells(pixel, frame, FilterBranch.INPUT, CELLS_PAD)), "gbrap")
    val picture = { x: Int, y: Int -> doubleArrayOf(redAt(x), greenAt(y), blueAt(x, y)) }

    var worst = 0.0
    var changed = 0.0
    for (y in reads(frame.height)) {
      for (x in reads(frame.width)) {
        val wanted = PixelSignal.pixelAt(x, y, frame.width, frame.height, pixel, picture)
        for (channel in 0 until RGB) {
          val read = planes.code(frame, x, y, channel)
          worst = max(worst, abs(read - wanted[channel] * FULL_CODE))
          changed = max(changed, abs(read - picture(x, y)[channel] * FULL_CODE))
        }
      }
    }
    withClue("the look has to move the frame well past the tolerance for this to tell") {
      (changed > EFFECT_CODES) shouldBe true
    }
    withClue("${frame.width} by ${frame.height}") { worst shouldBeLessThan SIGNAL_CODES }
  }

  private fun rampSource(frame: Size): String =
    "color=c=black:s=${frame.width}x${frame.height}:d=0.04,format=gbrap," +
      "geq=r='$RED_RAMP':g='$GREEN_RAMP':b='$BLUE_RAMP':a='255'"

  // Every pixel through the middle, so each cell edge there is crossed, plus a spread out to both sides.
  private fun reads(size: Int): List<Int> =
    ((size / 2 - MIDDLE_READS until size / 2 + MIDDLE_READS) + (0 until size step SPREAD_STRIDE) + (size - 1))
      .filter { it in 0 until size }
      .distinct()

  private fun render(
    graph: String,
    format: String,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        graph,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        format,
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    return out.toByteArray()
  }

  // The source on the pad the branches read as their input, and the last branch's pad as the graph's output.
  private fun graph(
    source: String,
    branches: List<FilterBranch>,
  ): String {
    val last = branches.last().writes.single()
    val pad = { name: String -> if (name == last) "[out0]" else "[$name]" }
    return "$source[${FilterBranch.INPUT}];" +
      branches.joinToString(";") { branch ->
        val nodes = branch.nodes.ifEmpty { listOf(FilterNode("split", "outputs" to "${branch.writes.size}")) }
        branch.reads.joinToString("") { "[$it]" } + nodes.joinToString(",") { it.rendered() } +
          branch.writes.joinToString("") { pad(it) }
      }
  }

  private fun FilterNode.rendered(): String =
    if (arguments.isEmpty()) name else name + "=" + arguments.joinToString(":") { "${it.key}='${it.value}'" }

  // gbrap lays out green, blue, red and alpha.
  private fun ByteArray.code(
    frame: Size,
    x: Int,
    y: Int,
    channel: Int,
  ): Double =
    (this[PLANE_OF_CHANNEL[channel] * frame.width * frame.height + y * frame.width + x].toInt() and 0xFF)
      .toDouble()

  private fun redAt(x: Int): Double = (x % CODES) / FULL_CODE

  private fun greenAt(y: Int): Double = (y * 3 % CODES) / FULL_CODE

  private fun blueAt(
    x: Int,
    y: Int,
  ): Double = ((x + y) % CODES) / FULL_CODE

  private companion object {
    const val CELLS = 45
    const val CELLS_PAD = "cells"
    val LEVELS = Pixel(cells = CELLS, levels = 6, dither = 0.5f)
    val PALETTE = Pixel(cells = CELLS, dither = 0.5f, palette = PixelPalette.GameBoy)

    // A full palette on a diagonal, so the ramps cross many of its entries.
    val SIXTEEN =
      Pixel(
        cells = CELLS,
        dither = 0.5f,
        palette = PixelPalette(List(PixelPalette.MAX_COLORS) { (it * 0x0F0B07 + 0x081018) and 0xFFFFFF }),
      )
    val FRAME = Size(640, 360)
    const val RED_RAMP = "mod(X,256)"
    const val GREEN_RAMP = "mod(Y*3,256)"
    const val BLUE_RAMP = "mod(X+Y,256)"
    const val CODES = 256
    const val FULL_CODE = 255.0
    const val RGB = 3
    val PLANE_OF_CHANNEL = intArrayOf(2, 0, 1)
    const val GBRAP_PLANES = 4
    const val MIDDLE_READS = 24
    const val SPREAD_STRIDE = 13
    const val HALF_ALPHA = 128

    // The clip's first frame a frame before zero, ahead of any map drawn at zero.
    const val BEFORE_ZERO = ",setpts=PTS-0.04/TB"
    const val CLEAR_READ = 40

    // A cut lands on a whole code and a palette entry is one, so only the round to eight bits is left.
    const val SIGNAL_CODES = 0.51

    // Well past SIGNAL_CODES, so a read landing inside it says the look is there.
    const val EFFECT_CODES = 6.0
  }
}
