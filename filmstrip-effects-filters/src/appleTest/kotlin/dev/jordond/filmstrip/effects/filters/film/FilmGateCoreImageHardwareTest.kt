package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernels' weave, flicker, dust, scratches and gate against [FilmSignal.pixelAt], every pixel of a frame.
 *
 * The codes a pixel is held against are the frame's own, read off the frame moved by [FilmSignal.weaveAt] between its
 * four nearest pixels and held at the edge.
 */
@OptIn(ExperimentalForeignApi::class)
class FilmGateCoreImageHardwareTest {
  @Test
  fun `every control at a mid strength lands on the signal`() {
    val film = Film(MID, MID, weave = MID, flicker = MID, dust = MID, gate = MID)

    assertMoves(film, WIDTH, HEIGHT)
    assertLandsOnTheSignal(film, WIDTH, HEIGHT)
  }

  @Test
  fun `super 8 lands on the signal`() {
    assertMoves(Film.Super8, WIDTH, HEIGHT)
    assertLandsOnTheSignal(Film.Super8, WIDTH, HEIGHT)
  }

  @Test
  fun `super 8 on a smaller frame lands on the signal at its own size`() {
    assertMoves(Film.Super8, WIDTH / 2, HEIGHT / 2)
    assertLandsOnTheSignal(Film.Super8, WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun `a seed of its own draws its own grain weave and scratch`() {
    val seeded = Film(grain = 1f, halation = 0f, weave = 1f, dust = MID, seed = OWN_SEED)
    val unseeded = Film(grain = 1f, halation = 0f, weave = 1f, dust = MID)

    withClue("a scratch has to land on the frame for this to tell") {
      (0 until WIDTH).any { x ->
        FilmSignal.scratchAt(OWN_SEED, TICK, (x + HALF) / WIDTH, MID.toDouble()) != 0.0
      } shouldBe
        true
    }
    drawn(seeded, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT).contentEquals(
      drawn(unseeded, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT),
    ) shouldBe false
    assertLandsOnTheSignal(seeded, WIDTH, HEIGHT)
  }

  @Test
  fun `the same time draws the same super 8 after a seek`() {
    val first = drawn(Film.Super8, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val elsewhere = drawn(Film.Super8, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds = SECONDS + 1.0)
    val again = drawn(Film.Super8, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT)

    withClue("the look moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  // Core Image does not hold a small frame to the region it asks for, so the region itself is read here, against
  // every weave a run of ticks reaches and the pixel past it a read between two pixels takes.
  @Test
  fun `the source region of a tile holds every weaved read under it`() {
    val seed = Film.DEFAULT_SEED
    listOf(
      CGRectMake(0.0, 0.0, 1920.0, 1080.0),
      CGRectMake(8.0, 8.0, 24.0, 24.0),
      CGRectMake(1880.0, 1040.0, 40.0, 40.0),
    ).forEach { tile ->
      val (x, y, w, h) = tile.useContents { listOf(origin.x, origin.y, size.width, size.height) }
      filmSourceRegion(tile, 1920, 1080, 1.0).useContents {
        (0 until REGION_TICKS).forEach { tick ->
          val offset = FilmSignal.weaveAt(seed, tick, 1.0)
          val dx = abs(offset[0]) * 1920 + 1.0
          val dy = abs(offset[1]) * 1080 + 1.0
          withClue("tick $tick of $x $y") {
            (origin.x <= x - dx && origin.x + size.width >= x + w + dx) shouldBe true
            (origin.y <= y - dy && origin.y + size.height >= y + h + dy) shouldBe true
          }
        }
      }
    }
  }

  @Test
  fun `a corner tile reads the same as the whole frame`() {
    val film = Film(grain = 1f, halation = 0f, weave = 1f, flicker = 1f, dust = 1f, gate = 1f)
    val whole = drawn(film, quartered(WIDTH, HEIGHT), WIDTH, HEIGHT)
    val output =
      filmStep(
        film,
      ).apply(quartered(WIDTH, HEIGHT), FrameInfo(filmAttributes(WIDTH, HEIGHT), SECONDS.seconds))
    val tile =
      filmRendered(output, WIDTH, HEIGHT, originX = TILE_X, originY = TILE_Y, boundsWidth = TILE, boundsHeight = TILE)

    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        // A bitmap row runs from the top, of the tile here and of the frame there, and the tile sits at the bottom.
        val wholeAt = ((HEIGHT - TILE_Y - TILE + y) * WIDTH + TILE_X + x) * FILM_CHANNELS
        val tileAt = (y * TILE + x) * FILM_CHANNELS
        withClue("row $y column $x") {
          tile[tileAt].toDouble() shouldBe (whole[wholeAt].toDouble() plusOrMinus TOLERANCE)
        }
      }
    }
  }

  @Test
  fun `a bar keeps its alpha and stays clear under every control`() {
    val picture = floatArrayOf(PATCH[0].toFloat(), PATCH[1].toFloat(), PATCH[2].toFloat(), 1f)
    val canvas =
      filmImageOf(WIDTH, HEIGHT) { x, _ -> if (x in CANVAS_LEFT until CANVAS_RIGHT) picture else CLEAR_BLACK }
    val frame = drawn(Film.Super8, canvas, WIDTH, HEIGHT)

    val y = HEIGHT / 2
    val at = { x: Int, channel: Int -> frame[(y * WIDTH + x) * FILM_CHANNELS + channel].toDouble() }
    withClue("a bar stays clear") {
      at(CANVAS_LEFT / 2, ALPHA) shouldBe (0.0 plusOrMinus TOLERANCE)
      at(CANVAS_LEFT / 2, 0) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("the picture stays opaque") { at(WIDTH / 2, ALPHA) shouldBe (1.0 plusOrMinus TOLERANCE) }
  }

  @Test
  fun `the resolver draws every control`() {
    val capabilities =
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = emptySet(),
        maxTextureSize = MAX_TEXTURE,
        features = emptySet(),
      )
    for (film in listOf(Film.Super8, Film(seed = OWN_SEED))) {
      FilterEffectResolver()
        .resolve(film, capabilities, filmAttributes(WIDTH, HEIGHT))
        .shouldBeInstanceOf<EffectResolution.Resolved>()
    }
  }

  private fun assertMoves(
    film: Film,
    width: Int,
    height: Int,
  ) {
    val params = FilmParams.of(film)
    withClue("the weave has to move the frame part of a pixel for this to tell") {
      abs(FilmSignal.weaveAt(params.seed, TICK, params.weave)[0] * width) shouldBeGreaterThan MIN_WEAVE_PX
    }
    withClue("the flicker has to change the gain for this to tell") {
      abs(FilmSignal.flickerAt(params.seed, TICK, params.flicker) - 1.0) shouldBeGreaterThan MIN_FLICKER
    }
    val marked =
      (0 until height).any { y ->
        (0 until width).any { x ->
          FilmSignal.dustAt(params.seed, TICK, FilmSignal.grainCell(x, y, width, height), params.dust) != 0.0
        }
      }
    withClue("dust has to land on the frame for this to tell") { marked shouldBe true }
  }

  private fun assertLandsOnTheSignal(
    film: Film,
    width: Int,
    height: Int,
  ) {
    val params = FilmParams.of(film)
    val offset = FilmSignal.weaveAt(params.seed, TICK, params.weave)
    val moved =
      Array(width * height) {
        between(width, height, it % width + offset[0] * width, it / width + offset[1] * height)
      }
    val glow = HalationGlow.of(width, height) { x, y -> moved[y * width + x] }
    val frame = drawn(film, quartered(width, height), width, height)
    // The blur's limit reaches the frame through the gain, on top of the float reads' own rounding.
    val tolerance = BLUR_CODES * params.halationGain / FULL + TOLERANCE

    var worst = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val codes = moved[y * width + x]
        val expected =
          FilmSignal.pixelAt(
            codes[0],
            codes[1],
            codes[2],
            glow.at(x, y),
            FilmSignal.grainCell(x, y, width, height),
            TICK,
            params,
            (x + HALF) / width,
            (y + HALF) / height,
            width.toDouble() / height,
          )
        for (channel in expected.indices) {
          worst = maxOf(worst, abs(frame[(y * width + x) * FILM_CHANNELS + channel] - expected[channel]))
        }
      }
    }
    withClue("the worst pixel of a ${width}x$height frame landed $worst off") { worst shouldBeLessThan tolerance }
  }

  // The four pixels around x, y read between, each held at the frame's edge.
  private fun between(
    width: Int,
    height: Int,
    x: Double,
    y: Double,
  ): DoubleArray {
    val left = floor(x).toInt()
    val top = floor(y).toInt()
    val across = x - left
    val down = y - top
    val at = { column: Int, row: Int ->
      codesAt(column.coerceIn(0, width - 1), row.coerceIn(0, height - 1), width, height)
    }
    return DoubleArray(RGB) { channel ->
      val upper = at(left, top)[channel] * (1 - across) + at(left + 1, top)[channel] * across
      val lower = at(left, top + 1)[channel] * (1 - across) + at(left + 1, top + 1)[channel] * across
      upper * (1 - down) + lower * down
    }
  }

  // A bright patch in the upper left and lower right quarters, which sits on the middle of the response's ramp, over
  // a midtone, so the weave moves an edge on both axes.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray = if ((x < width / 2) == (y < height / 2)) PATCH else GROUND

  private fun quartered(
    width: Int,
    height: Int,
  ): CIImage =
    filmImageOf(width, height) { x, y ->
      val codes = codesAt(x, y, width, height)
      floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
    }

  private fun drawn(
    film: Film,
    image: CIImage,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
  ): FloatArray =
    filmRendered(filmStep(film).apply(image, FrameInfo(filmAttributes(width, height), seconds.seconds)), width, height)

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val MID = 0.5f
    const val HALF = 0.5
    const val RGB = 3
    const val ALPHA = 3
    const val FULL = 255.0

    // Tick 615, half way between two weave knots so a read rides the ease, and one dust lands on at both sizes.
    const val SECONDS = 25.0 + 15.0 / 24.0
    val TICK = FilmSignal.grainTickAt(SECONDS)
    const val OWN_SEED = 2
    const val TOLERANCE = 1e-4
    const val TILE = 24
    const val TILE_X = 8
    const val TILE_Y = 8
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val REGION_TICKS = 1000
    const val MAX_TEXTURE = 4096

    // A read that tells a weave from rounding, and a gain that tells a flicker from none.
    const val MIN_WEAVE_PX = 0.02
    const val MIN_FLICKER = 0.005

    // Core Image's Gaussian is Core Image's own limit. It lands about one code off the plan's kernel on a patch.
    const val BLUR_CODES = 2.0
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
  }
}
