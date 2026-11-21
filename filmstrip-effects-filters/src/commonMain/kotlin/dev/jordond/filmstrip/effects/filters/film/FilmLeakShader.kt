package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import kotlin.math.PI

private fun tintGlsl(tint: DoubleArray): String = "vec3(${tint.joinToString(", ") { glslFloat(it) }})"

// FilmLeak's leakAt in GLSL ES 3.00, for a fragment that holds HASH_GLSL's draw under the uSeed uniform. Ticks are
// whole and never below 0, and at runs down the frame from its top. Each line carries the fragment sources' own two
// spaces, so trimIndent still lands #version first.
internal val FILM_LEAK_GLSL: String =
  """
  const vec3 LEAK_TINTS[${LEAK_TINTS.size}] = vec3[${LEAK_TINTS.size}](
    ${LEAK_TINTS.joinToString(",\n    ") { tintGlsl(it) }}
  );

  vec3 leakSpot(vec2 at, float aspect, uint clock, uint index) {
    uint run = clock / ${LEAK_RUN_TICKS}u;
    float phase = float(clock - run * ${LEAK_RUN_TICKS}u) / ${glslFloat(LEAK_RUN_TICKS.toDouble())};
    float wave = sin(${glslFloat(PI)} * phase);
    float edge = floor(draw(${PASS_LEAK_EDGE}u, run, index) * ${glslFloat(LEAK_EDGES.toDouble())});
    float place = draw(${PASS_LEAK_PLACE}u, run, index);
    float radius = ${glslFloat(LEAK_RADIUS_MIN)} +
      ${glslFloat(LEAK_RADIUS_MAX - LEAK_RADIUS_MIN)} * draw(${PASS_LEAK_RADIUS}u, run, index);
    int tint = int(floor(draw(${PASS_LEAK_TINT}u, run, index) * ${glslFloat(LEAK_TINTS.size.toDouble())}));
    vec2 centre = vec2(place, 0.0);
    if (edge < ${glslFloat(LEAK_EDGE_LEFT + 0.5)}) {
      centre = vec2(0.0, place);
    } else if (edge < ${glslFloat(LEAK_EDGE_RIGHT + 0.5)}) {
      centre = vec2(1.0, place);
    }
    float fall = 1.0 - smoothstep(0.0, radius, length((at - centre) * vec2(aspect, 1.0)));
    return wave * wave * fall * fall * LEAK_TINTS[tint];
  }

  vec3 leakAt(vec2 at, float aspect, uint tick, float leak) {
    if (leak <= 0.0) return vec3(0.0);
    vec3 light = vec3(0.0);
    for (uint index = 0u; index < ${LEAK_SPOTS}u; index++) {
      light += leakSpot(at, aspect, tick + index * ${LEAK_STAGGER_TICKS}u, index);
    }
    return min(${glslFloat(LEAK_LEVEL)} * leak * light, vec3(1.0));
  }
  """

private fun tintMsl(tint: DoubleArray): String = "float3(${tint.joinToString(", ") { metalFloat(it) }})"

// Every tint but the last as a return of its own, since a kernel's source holds no array.
private val LEAK_TINT_BRANCHES: String =
  LEAK_TINTS
    .dropLast(1)
    .mapIndexed { index, tint -> "if (tint <= $index) return ${tintMsl(tint)};" }
    .joinToString("\n    ")

// The same functions in Metal, for a kernel that holds HASH_MSL's filterDraw, each taking the seed first. Each line
// carries the kernel sources' own two spaces, so trimIndent still lands the includes first.
internal val FILM_LEAK_MSL: String =
  """
  float3 leakTint(int tint) {
    $LEAK_TINT_BRANCHES
    return ${tintMsl(LEAK_TINTS.last())};
  }

  float3 leakSpot(uint seed, float2 at, float aspect, uint clock, uint index) {
    uint run = clock / ${LEAK_RUN_TICKS}u;
    float phase = float(clock - run * ${LEAK_RUN_TICKS}u) / ${metalFloat(LEAK_RUN_TICKS.toDouble())};
    float wave = sin(${metalFloat(PI)} * phase);
    float edge = floor(filterDraw(seed, ${PASS_LEAK_EDGE}u, run, index) * ${metalFloat(LEAK_EDGES.toDouble())});
    float place = filterDraw(seed, ${PASS_LEAK_PLACE}u, run, index);
    float radius = ${metalFloat(LEAK_RADIUS_MIN)} +
      ${metalFloat(LEAK_RADIUS_MAX - LEAK_RADIUS_MIN)} * filterDraw(seed, ${PASS_LEAK_RADIUS}u, run, index);
    int tint = int(floor(filterDraw(seed, ${PASS_LEAK_TINT}u, run, index) * ${metalFloat(LEAK_TINTS.size.toDouble())}));
    float2 centre = float2(place, 0.0f);
    if (edge < ${metalFloat(LEAK_EDGE_LEFT + 0.5)}) {
      centre = float2(0.0f, place);
    } else if (edge < ${metalFloat(LEAK_EDGE_RIGHT + 0.5)}) {
      centre = float2(1.0f, place);
    }
    float fall = 1.0f - smoothstep(0.0f, radius, length((at - centre) * float2(aspect, 1.0f)));
    return wave * wave * fall * fall * leakTint(tint);
  }

  float3 leakAt(uint seed, float2 at, float aspect, uint tick, float leak) {
    if (leak <= 0.0f) return float3(0.0f);
    float3 light = float3(0.0f);
    for (uint index = 0u; index < ${LEAK_SPOTS}u; index++) {
      light += leakSpot(seed, at, aspect, tick + index * ${LEAK_STAGGER_TICKS}u, index);
    }
    return min(${metalFloat(LEAK_LEVEL)} * leak * light, float3(1.0f));
  }
  """
