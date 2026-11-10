package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.pqClipOfLuma
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Reads a color effect and an overlay on a kept PQ grade over a contained picture straight off the preview pump, the
 * bars against the fill and the picture against [transformNits].
 *
 * The picture is one flat grey, which a lossless clip holds exactly, so its luma code is each channel's code and the
 * contained picture reads the same code everywhere.
 */
class FfmpegHdrGradeAlphaMediaTest {
  private val draw = FilterFrames()

  // Brightness runs a mixer with no bias, Contrast a bias through a 3D table and Saturation mixes the channels, so
  // every way a matrix lowers on a grade carries the frame's alpha to the fill.
  @Test
  fun `a kept PQ composition grade keeps a contained picture graded and the bars exactly the fill`() =
    runBlocking<Unit> {
      val plain = lumaOf(listOf(contain()))
      listOf<EffectSpec>(Brightness(HALF), Contrast(HALF), Saturation(HALF)).forEach { grade ->
        val graded = lumaOf(listOf(contain(), grade))
        val nits = nitsOfCode(codeOfNits(GREY_NITS)).toFloat()
        val expected =
          codeOfNits(checkNotNull(colorMatrixOf(grade)).transformNits(nits, nits, nits, HdrTransfer.Pq)[0].toDouble())
        val height = graded.size / CONTAINED_WIDTH
        val pictureRows = BAR_ROWS until height - BAR_ROWS
        for (index in graded.indices) {
          val x = index % CONTAINED_WIDTH
          val y = index / CONTAINED_WIDTH
          if (y in pictureRows) {
            withClue("$grade at $x, $y read ${graded[index]} for $expected") {
              abs(graded[index] - expected) shouldBeLessThanOrEqual CODE_TOLERANCE
            }
          } else {
            withClue("$grade at $x, $y in the bar") { graded[index] shouldBe plain[index] }
          }
        }
      }
    }

  // The overlay sits across the top bar and the picture with its left half clear, so the bar under that half and every
  // sample past the overlay read what the clip reads with no overlay at all, and its opaque half reads the same over
  // the bar as over the picture. The bar reads exactly the fill, and the picture under the overlay's region reads its
  // own codes after their trip through light.
  @Test
  fun `a kept PQ clip overlay over a contained picture leaves the bars exactly the fill`() =
    runBlocking<Unit> {
      val plain = clipLumaOf(listOf(contain()))
      val overlaid = clipLumaOf(listOf(contain(), halfClearOverlay()))
      val opaque = OVERLAY_SIDE / 2 - EDGE_SLACK until OVERLAY_SIDE + EDGE_SLACK
      for (index in overlaid.indices) {
        val x = index % CONTAINED_WIDTH
        val y = index / CONTAINED_WIDTH
        if (x in opaque && y < OVERLAY_SIDE + EDGE_SLACK) continue
        withClue("overlay at $x, $y read ${overlaid[index]} for ${plain[index]}") {
          if (y < BAR_ROWS) {
            overlaid[index] shouldBe plain[index]
          } else {
            abs(overlaid[index] - plain[index]) shouldBeLessThanOrEqual CODE_TOLERANCE
          }
        }
      }
      val column = OVERLAY_SIDE * 3 / 4
      withClue("the opaque half over the bar and over the picture") {
        overlaid[BAR_ROWS / 2 * CONTAINED_WIDTH + column] shouldBe
          overlaid[(BAR_ROWS + (OVERLAY_SIDE - BAR_ROWS) / 2) * CONTAINED_WIDTH + column]
      }
    }

  // The clip carries its own alpha of one half, so the contained picture under the overlay is partly see-through. The
  // overlay is composited over it once, so through its clear half every sample reads what the clip reads with no
  // overlay at all, and its opaque half reads the same over the picture as over the bar.
  @Test
  fun `a kept PQ clip overlay over a partly see through clip composites it once`() =
    runBlocking<Unit> {
      val plain = halfClearLumaOf(listOf(contain()))
      val overlaid = halfClearLumaOf(listOf(contain(), halfClearOverlay()))
      val pictureRow = BAR_ROWS * CONTAINED_WIDTH
      withClue("the clip's own alpha reaches the fill") {
        plain[pictureRow] shouldNotBe plain[0]
        plain[pictureRow] shouldNotBe clipLumaOf(listOf(contain()))[pictureRow]
      }
      val opaque = OVERLAY_SIDE / 2 - EDGE_SLACK until OVERLAY_SIDE + EDGE_SLACK
      for (index in overlaid.indices) {
        val x = index % CONTAINED_WIDTH
        val y = index / CONTAINED_WIDTH
        if (x in opaque && y < OVERLAY_SIDE + EDGE_SLACK) continue
        withClue("overlay at $x, $y read ${overlaid[index]} for ${plain[index]}") {
          abs(overlaid[index] - plain[index]) shouldBeLessThanOrEqual CODE_TOLERANCE
        }
      }
      val column = OVERLAY_SIDE * 3 / 4
      withClue("the opaque half over the bar and over the picture") {
        overlaid[BAR_ROWS / 2 * CONTAINED_WIDTH + column] shouldBe
          overlaid[(BAR_ROWS + (OVERLAY_SIDE - BAR_ROWS) / 2) * CONTAINED_WIDTH + column]
      }
    }

  private val clip by lazy { draw.pqClipOfLuma(WIDTH, HEIGHT, { codeOfNits(GREY_NITS) }) }

  private suspend fun lumaOf(effects: List<EffectSpec>): IntArray =
    draw.tenBitLumaAt(draw.composition(listOf(clip), effects, Fill.Solid(SLATE)), 0, COLOR_READ_HDR_SPEC)

  private suspend fun clipLumaOf(effects: List<EffectSpec>): IntArray {
    val clip = draw.pqClipOfLuma(WIDTH, HEIGHT, { codeOfNits(GREY_NITS) }, effects)
    return draw.tenBitLumaAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), 0, COLOR_READ_HDR_SPEC)
  }

  // The grey clip carrying an alpha of one half on every pixel.
  private suspend fun halfClearLumaOf(effects: List<EffectSpec>): IntArray {
    val clip = draw.pqClipOfLuma(WIDTH, HEIGHT, { codeOfNits(GREY_NITS) }, effects) { HALF_ALPHA }
    return draw.tenBitLumaAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), 0, COLOR_READ_HDR_SPEC)
  }

  // A white picture as wide as a third of the contained frame, its left half fully clear, at the frame's corner.
  private fun halfClearOverlay(): ImageOverlay {
    val image = BufferedImage(OVERLAY_SIDE, OVERLAY_SIDE, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until OVERLAY_SIDE) {
      for (x in OVERLAY_SIDE / 2 until OVERLAY_SIDE) image.setRGB(x, y, WHITE)
    }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart, margin = 0f, scale = OVERLAY_SCALE)
  }

  private fun contain(): Crop = Crop(CONTAINED, fit = Fit.Contain)

  private fun codeOfNits(nits: Double): Int = (TEN_BIT_BLACK + TEN_BIT_RANGE * pqSignalFromNits(nits)).roundToInt()

  private fun nitsOfCode(code: Int): Double = nitsFromPqSignal((code - TEN_BIT_BLACK) / TEN_BIT_RANGE)

  private companion object {
    const val WIDTH = 384
    const val HEIGHT = 216

    // An 8 by 9 canvas over the clip is 192 by 216, and its picture fills the width at 108 rows tall, so a bar of 54
    // rows runs above and below it.
    val CONTAINED = AspectRatio(8, 9)
    const val CONTAINED_WIDTH = 192
    const val BAR_ROWS = 54
    const val SLATE = 0xFF2F4F6F.toInt()

    // A third of the contained width, past the 54 rows of the top bar, drawn at the size it is stored at.
    const val OVERLAY_SCALE = 1f / 3
    const val OVERLAY_SIDE = 64
    const val EDGE_SLACK = 2
    const val WHITE = 0xFFFFFFFF.toInt()

    // Half way between clear and opaque, as a ten-bit alpha code.
    const val HALF_ALPHA = 512

    // Below reference white, so every grade lands short of the ceiling the tables clamp at.
    const val GREY_NITS = 60.0
    const val TEN_BIT_BLACK = 64.0
    const val TEN_BIT_RANGE = 876.0
  }
}
