package dev.jordond.filmstrip.effects.filters.internal

import kotlin.math.floor
import kotlin.math.min

// Reads a straight colour frame at uv between the four texels around it, each held at the frame's edge the way a
// clamped texture is. The taps mix premultiplied, so a clear bar lends the picture no colour, and the mix goes back
// out straight, clear where every tap is.
internal val STRAIGHT_READ_GLSL: String =
  """
  vec4 premultipliedTexel(sampler2D frame, ivec2 texel, ivec2 size) {
    vec4 read = texelFetch(frame, clamp(texel, ivec2(0), size - 1), 0);
    return vec4(read.rgb * read.a, read.a);
  }

  vec4 straightRead(sampler2D frame, vec2 uv) {
    ivec2 size = textureSize(frame, 0);
    vec2 at = uv * vec2(size) - 0.5;
    vec2 corner = floor(at);
    vec2 t = at - corner;
    ivec2 low = ivec2(corner);
    vec4 top = mix(premultipliedTexel(frame, low, size), premultipliedTexel(frame, low + ivec2(1, 0), size), t.x);
    vec4 bottom =
      mix(premultipliedTexel(frame, low + ivec2(0, 1), size), premultipliedTexel(frame, low + ivec2(1, 1), size), t.x);
    vec4 mixed = mix(top, bottom, t.y);
    return vec4(mixed.a > 0.0 ? mixed.rgb / mixed.a : vec3(0.0), mixed.a);
  }
  """

// The read STRAIGHT_READ_GLSL makes, for a signal to hold a backend's pixels against. texel gives a texel's straight
// red, green and blue, with alpha after them or opaque without, and is only asked for texels inside the frame.
internal fun straightRead(
  u: Double,
  v: Double,
  width: Int,
  height: Int,
  texel: (column: Int, row: Int) -> DoubleArray,
): DoubleArray {
  val atX = u * width - 0.5
  val atY = v * height - 0.5
  val left = floor(atX)
  val top = floor(atY)
  val tx = atX - left
  val ty = atY - top
  var channels = 0
  val mixed = DoubleArray(RGBA)
  for ((dx, dy) in CORNERS) {
    val weight = (if (dx == 0) 1.0 - tx else tx) * (if (dy == 0) 1.0 - ty else ty)
    val column = (left.toInt() + dx).coerceIn(0, width - 1)
    val row = (top.toInt() + dy).coerceIn(0, height - 1)
    val read = texel(column, row)
    channels = read.size
    val alpha = if (read.size > ALPHA) read[ALPHA] else 1.0
    for (channel in 0 until min(read.size, ALPHA)) mixed[channel] += weight * read[channel] * alpha
    mixed[ALPHA] += weight * alpha
  }
  val alpha = mixed[ALPHA]
  return DoubleArray(channels) { channel ->
    when {
      channel == ALPHA -> alpha
      alpha > 0.0 -> mixed[channel] / alpha
      else -> 0.0
    }
  }
}

// The same read along one row, held at the row's own centre.
internal fun straightReadAcross(
  u: Double,
  width: Int,
  texel: (column: Int) -> DoubleArray,
): DoubleArray = straightRead(u, 0.5, width, 1) { column, _ -> texel(column) }

private const val ALPHA = 3
private const val RGBA = 4
private val CORNERS = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
