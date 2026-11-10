package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.Test

/**
 * Runs the weave, flicker and marks expressions through ffmpeg's own `geq` and reads them back against [FilmSignal].
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class FilmGateExpressionMediaTest {
  @Test
  fun `the geq weave and flicker land on weaveAt and flickerAt at a mid tick`() {
    val params = FilmParams.of(Film(weave = 1f, flicker = 0.5f))
    val offset = FilmSignal.weaveAt(params.seed, WEAVE_TICK, params.weave)
    withClue("the weave has to move a read a tenth of a pixel across and a whole pixel down for this to tell") {
      (abs(offset[0] * WIDTH) > 0.1 && abs(offset[1] * HEIGHT) >= 1.0) shouldBe true
    }
    val gain = FilmSignal.flickerAt(params.seed, WEAVE_TICK, params.flicker)
    val read = rgba(FilmExpressions.light(params, weaves = true, flickers = true, tick = "$WEAVE_TICK"))

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val wanted = woven(x, y, offset)
        for (channel in 0 until 4) {
          val value = if (channel == 3) wanted[3] else min(FULL, wanted[channel] * gain)
          withClue("x $x y $y channel $channel") {
            abs(read[(channel * HEIGHT + y) * WIDTH + x] - value) shouldBeLessThanOrEqual ROUNDING
          }
        }
      }
    }
  }

  @Test
  fun `the geq weave alone rounds every plane to the nearest code of weaveAt`() {
    val params = FilmParams.of(Film(weave = 1f))
    val offset = FilmSignal.weaveAt(params.seed, WEAVE_TICK, params.weave)
    val read = rgba(FilmExpressions.light(params, weaves = true, flickers = false, tick = "$WEAVE_TICK"))

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val wanted = woven(x, y, offset)
        for (channel in 0 until 4) {
          withClue("x $x y $y channel $channel") {
            abs(read[(channel * HEIGHT + y) * WIDTH + x] - wanted[channel]) shouldBeLessThanOrEqual ROUNDING
          }
        }
      }
    }
  }

  @Test
  fun `a clear pixel lends the weave no colour`() {
    val params = FilmParams.of(Film(weave = 1f))
    val read = rgba(FilmExpressions.light(params, weaves = true, flickers = false, tick = "$WEAVE_TICK"))
    val offset = FilmSignal.weaveAt(params.seed, WEAVE_TICK, params.weave)

    // Whichever column of the pair the weave reads across into the other mixes them, and shows the opaque one's blue.
    withClue("the read has to reach a tenth of a pixel across for this to tell") {
      (abs(offset[0] * WIDTH) > 0.1) shouldBe true
    }
    val x = if (offset[0] > 0.0) CLEAR_FROM - 1 else CLEAR_FROM
    val y = HEIGHT / 2
    val opaque = sourceAt(CLEAR_FROM - 1, y)
    abs(read[(BLUE * HEIGHT + y) * WIDTH + x] - opaque[BLUE]) shouldBeLessThanOrEqual ROUNDING
  }

  @Test
  fun `a marks plane equals the geq and holds dust from dustAt`() {
    // The last frame's cells are two pixels across, so its dust is drawn on the cells and widened.
    listOf(Size(320, 180), Size(1920, 1080), Size(640, 2160)).forEach { frame ->
      val params = FilmParams.of(Film(dust = 1f))
      val tick = markedTick(params, frame)
      val planes = MarkPlanes(frame.width, frame.height, params.seed, params.dust)
      val drawn = gray(FilmGateExpressions.marksPlane(params, frame, "$tick"), frame)
      val plane = planes.at(tick)

      withClue("the planes of a $frame frame at tick $tick") { plane.contentEquals(drawn) shouldBe true }
      val specks =
        (0 until frame.width * frame.height).count { index ->
          val cell = FilmSignal.grainCell(index % frame.width, index / frame.width, frame.width, frame.height)
          FilmSignal.dustAt(params.seed, tick, cell, params.dust) != 0.0
        }
      specks shouldBeGreaterThan 0
      for (index in plane.indices) {
        val x = index % frame.width
        val y = index / frame.width
        val cell = FilmSignal.grainCell(x, y, frame.width, frame.height)
        val dust = FilmSignal.dustAt(params.seed, tick, cell, params.dust)
        val scratch = FilmSignal.scratchAt(params.seed, tick, (x + 0.5) / frame.width, params.dust) != 0.0
        if ((plane[index].toInt() and 0xFF) != markCode(dust, scratch)) {
          withClue("x $x y $y of $frame") { plane[index].toInt() and 0xFF shouldBe markCode(dust, scratch) }
        }
      }
    }
  }

  @Test
  fun `the defaults lower the graph grain and halation always did`() {
    val fragment = filmFragment(Film(), Size(1280, 720))

    fragment.branches.map { it.writes } shouldBe
      listOf(
        listOf("frame"),
        listOf("rgb"),
        listOf("narrow", "far"),
        listOf("near"),
        listOf("weighedlight", "weighedcover"),
        listOf("weighedalpha"),
        listOf("weighed"),
        listOf("glow"),
        listOf("glowed"),
        listOf("base", "grainIn"),
        listOf("grain"),
        listOf("grained"),
      )
    fragment.sidecars shouldBe emptyList()
    fragment.branches.flatMap { branch -> branch.nodes.map { it.name } }.count { it == "geq" } shouldBe 2
    val live = fragment.live.shouldNotBeNull()
    live.inputs.map { it.key } shouldBe listOf("film.grain.${Film.DEFAULT_SEED}")
    live.branches.last().writes shouldBe listOf("grained")
  }

  @Test
  fun `every control lands in order and both forms end on the gate`() {
    val fragment = filmFragment(Film.Super8, Size(1280, 720))
    val live = fragment.live.shouldNotBeNull()

    fragment.branches.last().writes shouldBe listOf(GATED)
    live.branches.last().writes shouldBe listOf(GATED)
    live.inputs.size shouldBe 2
    fragment.sidecars.size shouldBe 1
    val woven =
      fragment.branches
        .first { it.writes == listOf("rgb") }
        .nodes
        .last()
    woven.name shouldBe "geq"
    val lit =
      fragment.branches
        .first { it.writes == listOf("glowed") }
        .nodes
        .map { it.name }
    lit.take(4) shouldBe listOf("mix", "lutrgb", "format", "geq")
  }

  @Test
  fun `a seed of its own reaches the grain`() {
    val seeded = filmFragment(Film(seed = 7), Size(1280, 720))
    val grain =
      seeded.live
        .shouldNotBeNull()
        .inputs
        .single()

    grain.key shouldBe "film.grain.7"
    grain.planeAt(TICK).contentEquals(GrainPlanes(1280, 720, 7).at(TICK)) shouldBe true
    grain.planeAt(TICK).contentEquals(GrainPlanes(1280, 720).at(TICK)) shouldBe false
  }

  // The first tick from TICK where a speck and a scratch both land on the frame.
  private fun markedTick(
    params: FilmParams,
    frame: Size,
  ): Int =
    (TICK until TICK + SEARCH_TICKS).first { tick ->
      val scratched =
        (0 until frame.width).any { FilmSignal.scratchAt(params.seed, tick, (it + 0.5) / frame.width, 1.0) != 0.0 }
      scratched &&
        (0 until frame.height step 2).any { y ->
          (0 until frame.width step 2).any { x ->
            FilmSignal.dustAt(params.seed, tick, FilmSignal.grainCell(x, y, frame.width, frame.height), 1.0) != 0.0
          }
        }
    }

  // The source's colour and alpha as codes: an edge down the middle in red, an edge across the middle in green, a ramp
  // across, and clear columns at the right.
  private fun sourceAt(
    x: Int,
    y: Int,
  ): DoubleArray {
    val alpha = if (x >= CLEAR_FROM) 0.0 else 128.0 + x * 127 / CLEAR_FROM
    val red = if (x < WIDTH / 2) 220.0 else 40.0
    val green = if (y < HEIGHT / 2) 230.0 else 30.0
    val blue = if (x >= CLEAR_FROM) 255.0 else 60.0 + x * 190 / WIDTH
    return doubleArrayOf(red, green, blue, alpha)
  }

  // FilmSignal's woven read in codes: the four pixels around the moved place, held at the edge, mixed premultiplied.
  private fun woven(
    x: Int,
    y: Int,
    offset: DoubleArray,
  ): DoubleArray {
    val across = x + offset[0] * WIDTH
    val down = y + offset[1] * HEIGHT
    val left = floor(across).toInt()
    val top = floor(down).toInt()
    val fx = across - left
    val fy = down - top
    val taps =
      listOf(
        Triple((1 - fx) * (1 - fy), left, top),
        Triple(fx * (1 - fy), left + 1, top),
        Triple((1 - fx) * fy, left, top + 1),
        Triple(fx * fy, left + 1, top + 1),
      ).map { (weight, tx, ty) -> weight to sourceAt(tx.coerceIn(0, WIDTH - 1), ty.coerceIn(0, HEIGHT - 1)) }
    val coverage = taps.sumOf { (weight, texel) -> weight * texel[3] }
    return DoubleArray(4) { channel ->
      when {
        channel == 3 -> coverage
        coverage > 0.0 -> taps.sumOf { (weight, texel) -> weight * texel[channel] * texel[3] } / coverage
        else -> 0.0
      }
    }
  }

  // Runs the geq over the source on gbrap and hands back red, green, blue and alpha planes in that order.
  private fun rgba(node: FilterNode): DoubleArray {
    val input = ByteArray(WIDTH * HEIGHT * 4)
    GBRAP_CHANNELS.forEachIndexed { slot, channel ->
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) input[(slot * HEIGHT + y) * WIDTH + x] = sourceAt(x, y)[channel].toInt().toByte()
      }
    }
    val arguments = node.arguments.joinToString(":") { "${it.key}='${it.value}'" }
    val out = run(input, "gbrap", Size(WIDTH, HEIGHT), "${node.name}=$arguments", "gbrap")
    return DoubleArray(WIDTH * HEIGHT * 4) { index ->
      val slot = GBRAP_CHANNELS.indexOf(index / (WIDTH * HEIGHT))
      (out[slot * WIDTH * HEIGHT + index % (WIDTH * HEIGHT)].toInt() and 0xFF).toDouble()
    }
  }

  private fun gray(
    expression: String,
    frame: Size,
  ): ByteArray = run(ByteArray(frame.width * frame.height), "gray", frame, "geq=lum='$expression'", "gray")

  private fun run(
    input: ByteArray,
    inputFormat: String,
    frame: Size,
    filter: String,
    outputFormat: String,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        inputFormat,
        "-s",
        "${frame.width}x${frame.height}",
        "-i",
        "-",
        "-frames:v",
        "1",
        "-vf",
        filter,
        "-f",
        "rawvideo",
        "-pix_fmt",
        outputFormat,
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    process.outputStream.use { it.write(input) }
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    return out.toByteArray()
  }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val CLEAR_FROM = 600

    const val TICK = 3597

    // Between two of the weave's knots, so its ease is mid way, where it moves the frame over a pixel down.
    const val WEAVE_TICK = 4269
    const val SEARCH_TICKS = 2_400
    const val FULL = 255.0
    const val BLUE = 2

    // gbrap holds green, blue, red and alpha, as indices into red, green, blue and alpha.
    val GBRAP_CHANNELS = listOf(1, 2, 0, 3)

    // A geq rounds its result to the nearest code.
    const val ROUNDING = 0.5 + 1e-6
  }
}
