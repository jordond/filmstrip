package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.glslFloat

private fun triple(
  values: DoubleArray,
  float: (Double) -> String,
): String = values.joinToString(", ") { float(it) }

// FilmTone's faded, breathAt and toned in GLSL ES 3.00, for a fragment that holds FILM_GATE_GLSL's filmKnotted. Ticks
// are whole and never below 0. Each line carries the fragment sources' own two spaces, so trimIndent still lands
// #version first.
internal val FILM_TONE_GLSL: String =
  """
  vec3 filmFaded(vec3 rgb, float fade) {
    return rgb * (1.0 - fade * vec3(${triple(FADE_SPAN, ::glslFloat)})) +
      fade * vec3(${triple(FADE_LIFT, ::glslFloat)});
  }

  vec4 breathAt(uint tick, float breath) {
    uint knot = tick / ${BREATH_KNOT_TICKS}u;
    float f = float(tick - knot * ${BREATH_KNOT_TICKS}u) / ${glslFloat(BREATH_KNOT_TICKS.toDouble())};
    float ease = f * f * (3.0 - 2.0 * f);
    float warmth = ${glslFloat(BREATH_WARMTH)} * breath * filmKnotted(${PASS_BREATH_WARMTH}u, knot, ease);
    return vec4(
      1.0 + ${glslFloat(BREATH_CONTRAST)} * breath * filmKnotted(${PASS_BREATH_CONTRAST}u, knot, ease),
      1.0 + warmth,
      1.0 + ${glslFloat(BREATH_TINT)} * breath * filmKnotted(${PASS_BREATH_TINT}u, knot, ease),
      1.0 - warmth
    );
  }

  vec3 filmToned(vec3 rgb, uint tick, float breath, float fade) {
    vec4 by = breathAt(tick, breath);
    return (filmFaded(rgb, fade) * by.x + ${glslFloat(BREATH_PIVOT)} * (1.0 - by.x)) * by.yzw;
  }
  """

// The same in Metal, for a kernel that holds FILM_GATE_MSL's filmKnotted, with the seed handed in.
internal val FILM_TONE_MSL: String =
  """
  float3 filmFaded(float3 rgb, float fade) {
    return rgb * (1.0f - fade * float3(${triple(FADE_SPAN, ::metalFloat)})) +
      fade * float3(${triple(FADE_LIFT, ::metalFloat)});
  }

  float4 breathAt(uint seed, uint tick, float breath) {
    uint knot = tick / ${BREATH_KNOT_TICKS}u;
    float f = float(tick - knot * ${BREATH_KNOT_TICKS}u) / ${metalFloat(BREATH_KNOT_TICKS.toDouble())};
    float ease = f * f * (3.0f - 2.0f * f);
    float warmth = ${metalFloat(BREATH_WARMTH)} * breath * filmKnotted(seed, ${PASS_BREATH_WARMTH}u, knot, ease);
    return float4(
      1.0f + ${metalFloat(BREATH_CONTRAST)} * breath * filmKnotted(seed, ${PASS_BREATH_CONTRAST}u, knot, ease),
      1.0f + warmth,
      1.0f + ${metalFloat(BREATH_TINT)} * breath * filmKnotted(seed, ${PASS_BREATH_TINT}u, knot, ease),
      1.0f - warmth
    );
  }

  float3 filmToned(float3 rgb, uint seed, uint tick, float breath, float fade) {
    float4 by = breathAt(seed, tick, breath);
    return (filmFaded(rgb, fade) * by.x + ${metalFloat(BREATH_PIVOT)} * (1.0f - by.x)) * by.yzw;
  }
  """
