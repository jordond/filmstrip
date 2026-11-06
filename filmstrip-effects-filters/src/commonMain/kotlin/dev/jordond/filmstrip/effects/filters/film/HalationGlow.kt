package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.floor

private const val RGB = 3

/**
 * The light halation adds across a frame, worked out the way the shader lowerings draw it, for a test to hold a
 * backend's pixels against.
 *
 * The frame's codes box down to the blur's size, each blurred pixel takes [FilmSignal.halationResponse], the two blurs
 * of the plan run over that, and a read filters between the blurred pixels.
 */
@InternalFilmstripApi
public class HalationGlow private constructor(
  private val frameWidth: Int,
  private val frameHeight: Int,
  private val blurred: Field,
) {
  /**
   * The blur of the response over pixel [x], [y] of the frame, as red, green and blue, for [FilmSignal.halated].
   */
  public fun at(
    x: Int,
    y: Int,
  ): DoubleArray =
    blurred.read(
      (x + 0.5) / frameWidth * blurred.width - 0.5,
      (y + 0.5) / frameHeight * blurred.height - 0.5,
    )

  private class Field(
    val width: Int,
    val height: Int,
  ) {
    val values = DoubleArray(width * height * RGB)

    fun put(
      x: Int,
      y: Int,
      rgb: DoubleArray,
    ) {
      rgb.copyInto(values, (y * width + x) * RGB)
    }

    // Bilinear with edges that clamp, the way the blur's targets are sampled.
    fun read(
      x: Double,
      y: Double,
    ): DoubleArray {
      val cx = x.coerceIn(0.0, width - 1.0)
      val cy = y.coerceIn(0.0, height - 1.0)
      val left = floor(cx).toInt()
      val top = floor(cy).toInt()
      val right = minOf(left + 1, width - 1)
      val bottom = minOf(top + 1, height - 1)
      val across = cx - left
      val down = cy - top
      return DoubleArray(RGB) { channel ->
        val a = lerp(texel(left, top, channel), texel(right, top, channel), across)
        val b = lerp(texel(left, bottom, channel), texel(right, bottom, channel), across)
        lerp(a, b, down)
      }
    }

    private fun texel(
      x: Int,
      y: Int,
      channel: Int,
    ) = values[(y * width + x) * RGB + channel]

    private fun lerp(
      a: Double,
      b: Double,
      t: Double,
    ) = a + (b - a) * t
  }

  /**
   * Builds the glow of one frame.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The glow of a frame [width] by [height], with [pixel] reading the R'G'B' of a pixel counted from the top, already
     * multiplied by its alpha.
     */
    public fun of(
      width: Int,
      height: Int,
      pixel: (x: Int, y: Int) -> DoubleArray,
    ): HalationGlow {
      val plan = FilmSignal.halationBlurPlan(width)
      val response = responseField(plan, width, height, pixel)
      val across = blurred(plan, response, 1.0, 0.0)
      val rowsPerGap = plan.heightOf(height) * width.toDouble() / (plan.width * height)
      return HalationGlow(width, height, blurred(plan, across, 0.0, rowsPerGap))
    }

    private fun responseField(
      plan: WideBlurPlan,
      width: Int,
      height: Int,
      pixel: (x: Int, y: Int) -> DoubleArray,
    ): Field {
      val field = Field(plan.width, plan.heightOf(height))
      val frame = Field(width, height)
      for (y in 0 until height) {
        for (x in 0 until width) frame.put(x, y, pixel(x, y))
      }
      val box = plan.factor
      for (y in 0 until field.height) {
        for (x in 0 until field.width) {
          val sum = DoubleArray(RGB)
          for (down in 0 until box) {
            for (across in 0 until box) {
              val u = (x + 0.5 + ((across + 0.5) / box - 0.5)) / field.width
              val v = (y + 0.5 + ((down + 0.5) / box - 0.5)) / field.height
              val tap = frame.read(u * width - 0.5, v * height - 0.5)
              for (channel in 0 until RGB) sum[channel] += tap[channel] / (box * box)
            }
          }
          field.put(x, y, FilmSignal.halationResponse(sum[0], sum[1], sum[2]))
        }
      }
      return field
    }

    private fun blurred(
      plan: WideBlurPlan,
      source: Field,
      columnsPerGap: Double,
      rowsPerGap: Double,
    ): Field {
      val field = Field(source.width, source.height)
      for (y in 0 until source.height) {
        for (x in 0 until source.width) {
          val sum = DoubleArray(RGB)
          for (tap in -plan.taps..plan.taps) {
            val weight = plan.weights[if (tap < 0) -tap else tap]
            val read = source.read(x + tap * columnsPerGap, y + tap * rowsPerGap)
            for (channel in 0 until RGB) sum[channel] += weight * read[channel]
          }
          field.put(x, y, sum)
        }
      }
      return field
    }
  }
}
