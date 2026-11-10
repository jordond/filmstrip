package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.GlitchParams
import dev.jordond.filmstrip.effects.filters.glitch.GlitchSignal
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelSignal
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.DurationUnit

/**
 * Looks drawn at a clip's scope on frames whose alpha sits part way or at none, through the real browser compositor.
 *
 * An [AlphaLeftOf] pass ahead of the look takes the left of the clip's frame to half alpha, or clears it the way a bar
 * is clear. Every read is held against the look's shared function on straight colour, read between pixels the way the
 * look's shader does, and laid by the alpha the pixel ends on over black, which a clip filling the frame lies over.
 */
class BrowserLookAlphaMediaTest {
  @Test
  fun aClipCrtBesideAClearBarKeepsThePicturesColour() =
    runTest {
      val source = flat(BRIGHT)
      val crt = Crt(MID)
      val params = CrtParams.of(crt)
      val plain = alphaFrameOf(Clip(source), fill = Fill.Black)
      val drawn = alphaFrameOf(Clip(source, effects = listOf(AlphaLeftOf(QUARTER, 0.0), crt)), fill = Fill.Black)
      val width = drawn.size.width
      val height = drawn.size.height
      val bar = (QUARTER * width).toInt()
      // The read between the bar's last column and the picture's first that lands nearest half way, in the middle
      // half where the curve leaves the picture whole.
      val (x, y, share) =
        (height / 4 until height * 3 / 4)
          .flatMap { row -> (0 until width / 2).map { column -> column to row } }
          .map { (column, row) ->
            val source =
              CrtSignal.barrelMap(
                (column + HALF) / width,
                (row + HALF) / height,
                width,
                height,
                params.barrelK,
              )
            Triple(column, row, source[0] * width - HALF - (bar - 1))
          }.filter { (_, _, t) -> t in 0.0..1.0 }
          .minBy { (_, _, t) -> abs(t - HALF) }
      withClue("a read has to land well between the two columns for this to tell") {
        abs(share - HALF) shouldBeLessThan BOUNDARY_SLACK
      }

      val picture = DoubleArray(RGB) { plain.codeAt(width / 2, height / 2, it) / MAX_CODE } + 1.0
      val at = { texel: (Int, Int) -> DoubleArray ->
        CrtSignal.pixelAt((x + HALF) / width, (y + HALF) / height, width, height, params, texel)
      }
      val wanted = at { column, _ -> if (column < bar) DoubleArray(RGB + 1) else picture }
      for (channel in 0 until RGB) {
        val straight = at { _, _ -> picture }[channel]
        val expected = wanted[channel] * wanted[RGB] * MAX_CODE
        val darkened = straight * MAX_CODE * share * share
        withClue("a straight bilinear read lands at $darkened, too close to $expected to tell") {
          abs(expected - darkened) shouldBeGreaterThan TELLS
        }
        val read = drawn.codeAt(x, y, channel)
        withClue("column $x row $y channel $channel read $read against $expected") {
          abs(read - expected) shouldBeLessThanOrEqualTo DRAWN_CODES
        }
      }
    }

  @Test
  fun aClipGlitchOnHalfAlphaReadsStraightColour() =
    runTest {
      val glitch = Glitch(1f)
      val params = GlitchParams.of(glitch)
      val tick = GlitchSignal.tickAt(READ_SECONDS)
      assertRowsLaid(glitch) { plain, x, y ->
        GlitchSignal.pixelAt(
          x,
          GlitchSignal.virtualRow(y, plain.size.height),
          tick,
          glitch.seed,
          plain.size.width,
          params,
        ) {
          plain.straightTexel(it, y, ::halfAlphaOf)
        }
      }
    }

  @Test
  fun aClipVhsOnHalfAlphaReadsStraightColour() =
    runTest {
      val vhs = Vhs(intensity = MID)
      val params = VhsParams.of(vhs)
      val tick = VhsSignal.tickAt(READ_SECONDS)
      assertRowsLaid(vhs) { plain, x, y ->
        val row = VhsSignal.virtualRow(y, plain.size.height)
        VhsSignal.pixelAt(
          x,
          row,
          tick,
          vhs.seed,
          plain.size.width,
          params,
        ) { plain.straightTexel(it, y, ::halfAlphaOf) }
      }
    }

  @Test
  fun aClipPixelOnHalfAlphaReadsStraightColour() =
    runTest {
      val pixel = Pixel(cells = CELLS)
      assertRowsLaid(pixel) { plain, x, y ->
        val width = plain.size.width
        val height = plain.size.height
        val column = (x * width).toInt()
        val colour =
          PixelSignal.pixelAt(column, y, width, height, pixel) { px, py ->
            DoubleArray(RGB) { plain.codeAt(px, py, it) / MAX_CODE }
          }
        val columns = PixelSignal.columns(width, height, CELLS)
        colour + halfAlphaOf(PixelSignal.centreOf(PixelSignal.cellOf(column, columns, width), columns, width))
      }
    }

  @Test
  fun aClipCamcorderOnHalfAlphaReadsStraightColour() =
    runTest {
      val camcorder = Camcorder(listOf(OsdElement.Rec(), OsdElement.Counter()))
      val raster = CamcorderOsd(camcorder, ALPHA_FRAME_RATE.toFloat()).rasterAt(READ_TIME, WIDTH, HEIGHT)
      assertRowsLaid(camcorder) { plain, x, y ->
        val column = (x * WIDTH).toInt()
        val argb = raster.argbAt(column, y)
        if (argb == CLEAR) {
          DoubleArray(RGB) { plain.codeAt(column, y, it) / MAX_CODE } + halfAlphaOf(column)
        } else {
          doubleArrayOf(
            (argb shr RED_SHIFT and BYTE_MASK) / MAX_CODE,
            (argb shr GREEN_SHIFT and BYTE_MASK) / MAX_CODE,
            (argb and BYTE_MASK) / MAX_CODE,
            1.0,
          )
        }
      }
    }

  // Draws look over a half alpha left half of the edge clip and holds every pixel of a spread of rows against expected,
  // which gives straight colour from 0 to 1 and the alpha after it, laid over black.
  private suspend fun assertRowsLaid(
    look: EffectSpec,
    expected: (plain: PreviewFrame, x: Double, y: Int) -> DoubleArray,
  ) {
    val source = verticalEdge()
    val plain = alphaFrameOf(Clip(source), fill = Fill.Black)
    val drawn = alphaFrameOf(Clip(source, effects = listOf(AlphaLeftOf(HALF, HALF), look)), fill = Fill.Black)
    var worst = 0.0
    var worstAt = ""
    for (y in 0 until HEIGHT step ROW_STEP) {
      for (column in 0 until WIDTH) {
        val wanted = expected(plain, (column + HALF) / WIDTH, y)
        for (channel in 0 until RGB) {
          val gap = abs(drawn.codeAt(column, y, channel) - wanted[channel] * MAX_CODE * wanted[RGB])
          if (gap > worst) {
            worst = gap
            worstAt = "column $column row $y channel $channel"
          }
        }
      }
    }
    withClue("$look is $worst codes off straight colour laid over black at $worstAt") {
      worst shouldBeLessThanOrEqualTo DRAWN_CODES
    }
  }

  private fun halfAlphaOf(column: Int): Double = if (column < WIDTH / 2) HALF else 1.0

  private suspend fun flat(color: Rgb): MediaSource =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, ALPHA_FRAME_RATE) { _, _ -> color })

  private suspend fun verticalEdge(): MediaSource =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, ALPHA_FRAME_RATE) { _, x, _ -> if (x < WIDTH / 2) LIGHT else DARK },
    )
}

private val BRIGHT = Rgb(230, 200, 120)
private val LIGHT = Rgb(220, 180, 140)
private val DARK = Rgb(40, 60, 90)
private val READ_TIME = outputSlotTime(ALPHA_READ_SLOT, ALPHA_FRAME_RATE)
private val READ_SECONDS = READ_TIME.toDouble(DurationUnit.SECONDS)
private const val WIDTH = 320
private const val HEIGHT = 180
private const val FRAMES = 12
private const val MAX_CODE = 255.0
private const val RGB = 3
private const val HALF = 0.5
private const val QUARTER = 0.25
private const val MID = 0.5f
private const val CELLS = 30
private const val ROW_STEP = 7
private const val CLEAR = 0
private const val BYTE_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

// How far from half way between the bar and the picture a read may land and still tell.
private const val BOUNDARY_SLACK = 0.3

// A straight bilinear read takes the bar's black in by the share again, which lands this many codes off or more.
private const val TELLS = 10.0

// Each eight bit target between the steps rounds once, the straight colour under the fill once more, and the read once.
private const val DRAWN_CODES = 3.0
