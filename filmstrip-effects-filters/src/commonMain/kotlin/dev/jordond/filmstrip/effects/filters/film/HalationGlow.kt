package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import kotlin.math.floor

private const val RGB = 3

/**
 * The light halation adds across a frame, worked out the way the shader lowerings draw it, for a test to hold a
 * backend's pixels against.
 *
 * The frame's codes box down to the blur's size, each blurred pixel takes [FilmSignal.halationResponse], [blurPlane]
 * blurs that by the plan's kernel one blurred pixel a tap on either axis, and a read filters between the blurred
 * pixels.
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
      return HalationGlow(width, height, blurred(responseField(plan, width, height, pixel)))
    }

    private fun responseField(
      plan: BlurPlan,
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

    // The field is already at the plan's size, where the plan's sigma is at most BlurPlan.MAX_SIGMA_PX, so blurPlane
    // runs it at a factor of 1 with the plan's own weights.
    private fun blurred(source: Field): Field {
      val opaque = DoubleArray(source.width * source.height) { 1.0 }
      val channels =
        List(RGB) { channel ->
          val plane = DoubleArray(source.width * source.height) { source.values[it * RGB + channel] }
          blurPlane(plane, opaque, source.width, source.height, HALATION_SIGMA)
        }
      val field = Field(source.width, source.height)
      for (index in 0 until source.width * source.height) {
        for (channel in 0 until RGB) field.values[index * RGB + channel] = channels[channel][index]
      }
      return field
    }
  }
}
