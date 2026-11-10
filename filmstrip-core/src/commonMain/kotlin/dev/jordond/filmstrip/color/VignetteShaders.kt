package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * A GLSL ES 3.00 function `float vignetteGain(vec2 at, float aspect, float strength, float radius, float softness)`
 * that works out [vignetteGain] on the GPU.
 *
 * `at` is the point as a fraction of the frame, with 0.5 at its centre, and the rest are [vignetteGain]'s figures. A
 * shader pastes it in above the code that calls it.
 */
@InternalFilmstripApi
public val VIGNETTE_GAIN_GLSL: String =
  """
  float vignetteGain(vec2 at, float aspect, float strength, float radius, float softness) {
    vec2 offset = (at - 0.5) * vec2(aspect, 1.0);
    float distance = length(offset) / length(vec2(0.5 * aspect, 0.5));
    float from = radius - softness;
    float to = radius + softness;
    float falloff = to > from ? smoothstep(from, to, distance) : step(from, distance);
    return 1.0 - strength * falloff;
  }
  """.trimIndent()

/**
 * The Metal Shading Language form of [VIGNETTE_GAIN_GLSL], for a Core Image kernel library, with the same name and
 * arguments on `float2` and `float`.
 */
@InternalFilmstripApi
public val VIGNETTE_GAIN_MSL: String =
  """
  float vignetteGain(float2 at, float aspect, float strength, float radius, float softness) {
    float2 offset = (at - 0.5f) * float2(aspect, 1.0f);
    float distance = length(offset) / length(float2(0.5f * aspect, 0.5f));
    float from = radius - softness;
    float to = radius + softness;
    float falloff = to > from ? smoothstep(from, to, distance) : step(from, distance);
    return 1.0f - strength * falloff;
  }
  """.trimIndent()
