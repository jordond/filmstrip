package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.alphaAt
import dev.jordond.filmstrip.ffmpeg.support.alphaRampClipOf
import dev.jordond.filmstrip.ffmpeg.support.assertBloomLanded
import dev.jordond.filmstrip.ffmpeg.support.bloomReads
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Bloom] straight off the preview pump against [BloomSignal], with no encoder in
 * the way, and what it reaches at each scope.
 *
 * The picture is flat columns on either side of an edge, which a lossless clip holds exactly. Its bright side sits in
 * the middle of the default threshold's knee, and its ground under it.
 */
class FfmpegBloomMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition bloom lands on the signal at a mid intensity`() =
    runBlocking<Unit> { assertCompositionReadsSignal(Bloom(), WIDTH, HEIGHT) }

  @Test
  fun `a clip bloom lands on the signal at a mid intensity`() =
    runBlocking<Unit> {
      val bloom = Bloom()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(bloom))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      assertBloomLanded(
        draw.planesAt(draw.composition(listOf(clip)), 0),
        draw.planesAt(draw.composition(listOf(plain)), 0),
        bloom,
      )
    }

  @Test
  fun `a low threshold takes the ground into the glow and a high one takes less of the patch`() =
    runBlocking<Unit> {
      assertCompositionReadsSignal(Bloom(threshold = LOW_THRESHOLD), WIDTH, HEIGHT)
      assertCompositionReadsSignal(Bloom(threshold = HIGH_THRESHOLD), WIDTH, HEIGHT)
    }

  @Test
  fun `a tight spread and a wide one land on the signal`() =
    runBlocking<Unit> {
      assertCompositionReadsSignal(Bloom(spread = 0.25f), WIDTH, HEIGHT)
      assertCompositionReadsSignal(Bloom(intensity = 0.75f, spread = 1f), WIDTH, HEIGHT)
    }

  @Test
  fun `the dreamy preset lands on the signal`() =
    runBlocking<Unit> { assertCompositionReadsSignal(Bloom.Dreamy, WIDTH, HEIGHT) }

  @Test
  fun `a read tells another intensity threshold or spread from the bloom that was drawn`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Bloom())), 0)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)

      listOf(Bloom(intensity = 0.35f), Bloom(threshold = 0.75f), Bloom(spread = 0.2f)).forEach { wrong ->
        withClue("$wrong read against a default bloom") {
          bloomReads(frame, plain, wrong).any { it.miss >= it.limit } shouldBe true
        }
      }
    }

  @Test
  fun `a picture under the threshold comes back through gbrap within a code`() =
    runBlocking<Unit> {
      val edge = WIDTH / 4
      val clip =
        draw.clipOfPlanes(
          WIDTH,
          HEIGHT,
          { EDGE_LUMA },
          { if (it < edge) EDGE_CB[0] else EDGE_CB[1] },
          { if (it < edge) EDGE_CR[0] else EDGE_CR[1] },
        )
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)
      val bloomed = draw.planesAt(draw.composition(listOf(clip), listOf(Bloom(intensity = 1f))), 0)

      listOf(edge - 2, edge - 1, edge, edge + 1).forEach { column ->
        withClue("chroma column $column") {
          abs(bloomed.cb(column, HEIGHT / 4) - plain.cb(column, HEIGHT / 4)) shouldBeLessThanOrEqual EDGE_CODES
          abs(bloomed.cr(column, HEIGHT / 4) - plain.cr(column, HEIGHT / 4)) shouldBeLessThanOrEqual EDGE_CODES
        }
      }
      withClue("luma beside the edge") {
        abs(bloomed.luma(edge * 2, HEIGHT / 2) - plain.luma(edge * 2, HEIGHT / 2)) shouldBeLessThanOrEqual EDGE_CODES
      }
    }

  @Test
  fun `a smaller preview draws the same look at its own size`() =
    runBlocking<Unit> {
      val bloom = Bloom()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val spec = ExportSpec(heightPx = HEIGHT / 2)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(bloom)), 0, spec)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)

      assertBloomLanded(frame, plain, bloom)
    }

  @Test
  fun `a 1080p frame blurs at the plans smaller size`() =
    runBlocking<Unit> {
      val bloom = Bloom()
      withClue("the plan has to scale past two for this to tell") {
        (BloomSignal.blurPlan(bloom.spread.toDouble(), FULL_HD_WIDTH).factor > 2) shouldBe true
      }
      assertCompositionReadsSignal(bloom, FULL_HD_WIDTH, FULL_HD_HEIGHT)
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::edgeAt)), listOf(Bloom()))
      val at = (FILM_READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, FILM_READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip bloom after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val bloom = Bloom(intensity = 1f, threshold = LOW_THRESHOLD, spread = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT }, listOf(crop, bloom))
      val frame = frameOf(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)))

      val barRow = HEIGHT / SQUARE_BAR_READ
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(HEIGHT, HEIGHT / 2, barRow, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  @Test
  fun `a composition bloom glows from the picture into a bar`() =
    runBlocking<Unit> {
      val bloom = Bloom(intensity = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT })
      val frame = frameOf(draw.composition(listOf(clip), listOf(crop, bloom), Fill.Solid(BLACK)))
      val plain = frameOf(draw.composition(listOf(clip), listOf(crop), Fill.Solid(BLACK)))

      val params = BloomParams.of(bloom)
      val plan = BloomSignal.blurPlan(bloom.spread.toDouble(), HEIGHT)
      // The picture fills the square's width, so bars run above and below it. The glow is read off the frame with no
      // bloom on it, since a row of the picture landing one off moves a boxed row across the threshold.
      val barTop = (HEIGHT - HEIGHT * HEIGHT / WIDTH) / 2
      val inBar = barTop - (plan.sigmaPx * plan.factor).toInt()
      val lift = frame.channelAt(HEIGHT, HEIGHT / 2, inBar, 0).toDouble()
      val glow =
        BloomSignal.glow(HEIGHT, HEIGHT, params) { x, y ->
          DoubleArray(3) { plain.channelAt(HEIGHT, x, y, it) / FULL }
        }
      withClue("the bar has to be black under the glow for this to tell") {
        plain.channelAt(HEIGHT, HEIGHT / 2, inBar, 0) shouldBe 0
      }
      val wanted = BloomSignal.bloomed(0.0, 0.0, 0.0, glow.at(HEIGHT / 2, inBar), params)[0] * FULL

      withClue("one sigma into the bar read $lift against $wanted") {
        wanted shouldBeGreaterThan BAR_GLOW_CODES * 2
        abs(lift - wanted) shouldBeLessThan BAR_GLOW_CODES
      }
    }

  // The glow takes each pixel's light weighed by its alpha, so where the clip's alpha ramps up from clear it glows far
  // less than a glow taken from the straight colour would. The look's frame lands on the black fill by its alpha. The
  // plain clip lands on it the same way, so its straight colour is the opaque side of its flat colour and its alpha is
  // read where the clip hands it to the fill.
  @Test
  fun `a clip bloom after a contain crop glows on the signal where its alpha ramps`() =
    runBlocking<Unit> {
      val bloom = Bloom(intensity = 1f, threshold = LOW_THRESHOLD)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val side = HEIGHT
      val frame = frameOf(draw.composition(listOf(draw.alphaRampClipOf(WIDTH, side, RAMP_COLOUR, listOf(crop, bloom)))))
      val plainComposition = draw.composition(listOf(draw.alphaRampClipOf(WIDTH, side, RAMP_COLOUR, listOf(crop))))
      val plain = frameOf(plainComposition)
      val alphas = draw.alphaAt(plainComposition, 0)
      val alphaAt = { x: Int, y: Int -> alphas[y * side + x] / FULL }
      val opaque = DoubleArray(3) { plain.channelAt(side, side - 1, side / 2, it) / FULL }
      val colourAt = { x: Int, y: Int -> if (alphas[y * side + x] > 0) opaque else DoubleArray(3) }
      val params = BloomParams.of(bloom)
      val weighed =
        BloomSignal.glow(side, side, params) { x, y -> colourAt(x, y).map { it * alphaAt(x, y) }.toDoubleArray() }
      val straight = BloomSignal.glow(side, side, params, colourAt)
      val row = side / 2
      val columns = (0 until side).filter { alphaAt(it, row) in RAMP_READS }

      columns.size shouldBeGreaterThan RAMP_COLUMNS
      columns.forEach { column ->
        val (red, green, blue) = colourAt(column, row)
        val alpha = alphaAt(column, row)
        val wanted = BloomSignal.bloomed(red, green, blue, weighed.at(column, row), params)[1] * alpha * FULL
        val unweighed = BloomSignal.bloomed(red, green, blue, straight.at(column, row), params)[1] * alpha * FULL
        val read = frame.channelAt(side, column, row, 1).toDouble()
        withClue("column $column at alpha $alpha read $read against $wanted") {
          abs(unweighed - wanted) shouldBeGreaterThan BAR_GLOW_CODES * 2
          abs(read - wanted) shouldBeLessThan BAR_GLOW_CODES
        }
      }
    }

  private suspend fun assertCompositionReadsSignal(
    bloom: Bloom,
    width: Int,
    height: Int,
  ) {
    val clip = draw.clipOf(width, height, ::edgeAt)
    assertBloomLanded(
      draw.planesAt(draw.composition(listOf(clip), listOf(bloom)), 0),
      draw.planesAt(draw.composition(listOf(clip)), 0),
      bloom,
    )
  }

  private suspend fun frameOf(composition: Composition): ByteArray =
    draw.frameAt(composition, Duration.ZERO, FILM_READ_SLOT)

  // A bright side half way up the default knee over a midtone. A column's codes, from the fraction of the width it
  // sits at.
  private fun edgeAt(x: Double): DoubleArray = if (x < 0.5) PATCH.copyOf() else GROUND.copyOf()

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val SQUARE_BAR_READ = 16

    // The ground's luma sits in this threshold's knee, and the patch's low in the other's.
    const val LOW_THRESHOLD = 0.35f
    const val HIGH_THRESHOLD = 0.75f

    // Plane codes either side of a chroma edge, all of it inside the RGB gamut and under the default threshold.
    const val EDGE_LUMA = 80
    val EDGE_CB = listOf(102, 170)
    val EDGE_CR = listOf(150, 110)
    const val EDGE_CODES = 1

    const val BAR_CODES = 3.0

    // How far a read through RGBA sits from the glow one sigma into a bar.
    const val BAR_GLOW_CODES = 2.5

    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
    val BRIGHT = doubleArrayOf(0.95, 0.9, 0.8)

    // Past the low threshold's knee with green well under full scale, so the glow green takes reads.
    val RAMP_COLOUR = doubleArrayOf(0.9, 0.6, 0.3)

    // The alphas read across the ramp, clear of either end, and how many columns at least hold one.
    val RAMP_READS = 0.55..0.9
    const val RAMP_COLUMNS = 20
    const val BLACK = 0xFF000000.toInt()
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())
  }
}
