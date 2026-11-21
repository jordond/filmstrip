package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterStep
import dev.jordond.filmstrip.effects.filters.internal.GLOW_BLURRED
import dev.jordond.filmstrip.effects.filters.internal.GLOW_FRAME
import dev.jordond.filmstrip.effects.filters.internal.GlowSteps
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.LUMA_GLSL
import dev.jordond.filmstrip.effects.filters.internal.STRAIGHT_READ_GLSL
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import dev.jordond.filmstrip.geometry.Size

/**
 * The GLSL ES 3.00 fragment sources that draw a [Film], read by media3 and the browser, and the order they run in.
 *
 * Every fixed number is written in from [FilmSignal]'s constants, and everything a control or a frame changes is a
 * uniform a [FilterStep] hands back, so the shaders hold no figure of their own. Each reads at the `vUv` varying.
 *
 * A clip's frame holds clear bars. Their alpha comes through every step untouched and they give the blur no light, so
 * neither the glow nor the grain ever shows over the fill.
 */
@InternalFilmstripApi
public object FilmShader {
  /**
   * The uniform that says `v` runs up the picture, 1 when it does and 0 when it runs down.
   *
   * It belongs to whoever runs the chain, and the weave and the grain read it.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The chain that draws [film] in [FilmSignal.pixelAt]'s order, leaving out every step its controls leave idle.
   *
   * The weave moves the frame first, the halation glows on what it moved, and one step lays the leak, fade, breath,
   * flicker, grain, dust, scratches and gate over the result. Every step before the last runs on a target of its own,
   * and the last one writes the frame.
   */
  public fun steps(film: Film): List<FilterStep> {
    val params = FilmParams.of(film)
    return buildList {
      if (params.weave > 0.0) add(weaveStep(params))
      if (params.halationGain > 0.0) addAll(halationSteps(params, lastNameOrInput()))
      if (params.drawsOverGlow) add(grainStep(params, lastNameOrInput()))
    }
  }

  private val FilmParams.drawsOverGlow: Boolean
    get() =
      grainStrength > 0.0 || flicker > 0.0 || dust > 0.0 || gate > 0.0 || leak > 0.0 || breath > 0.0 || fade > 0.0

  private fun List<FilterStep>.lastNameOrInput(): String = lastOrNull()?.name ?: FilterStep.INPUT

  private fun weaveStep(params: FilmParams): FilterStep =
    FilterStep(STEP_WEAVE, WEAVE_FRAGMENT, mapOf(FRAME to FilterStep.INPUT), ::frameSize) { seconds, _, _ ->
      mapOf(
        SEED to seedOf(params),
        TICK to tickAt(seconds),
        "uWeave" to floatArrayOf(params.weave.toFloat()),
      )
    }

  private fun halationSteps(
    params: FilmParams,
    reads: String,
  ): List<FilterStep> =
    GlowSteps.of(
      sigmaFraction = HALATION_SIGMA,
      reads = reads,
      responseGlsl = HALATION_RESPONSE_GLSL,
      responseUniforms = emptyMap(),
      combineName = STEP_HALATION,
      combineFragment = HALATION_FRAGMENT,
      combineUniforms = mapOf("uGain" to floatArrayOf(params.halationGain.toFloat())),
    )

  private fun grainStep(
    params: FilmParams,
    reads: String,
  ): FilterStep =
    FilterStep(STEP_GRAIN, GRAIN_FRAGMENT, mapOf(FRAME to reads), ::frameSize) { seconds, width, height ->
      mapOf(
        SEED to seedOf(params),
        TICK to tickAt(seconds),
        "uStrength" to floatArrayOf(params.grainStrength.toFloat()),
        "uFlicker" to floatArrayOf(params.flicker.toFloat()),
        "uDust" to floatArrayOf(params.dust.toFloat()),
        "uGate" to floatArrayOf(params.gate.toFloat()),
        "uLeak" to floatArrayOf(params.leak.toFloat()),
        "uBreath" to floatArrayOf(params.breath.toFloat()),
        "uFade" to floatArrayOf(params.fade.toFloat()),
        "uResolution" to floatArrayOf(width.toFloat(), height.toFloat()),
        "uCellSpan" to floatArrayOf(FilmSignal.grainCellSpan(width, height).toFloat()),
      )
    }

  private fun seedOf(params: FilmParams): FloatArray = floatArrayOf((params.seed and HASH_MASK).toFloat())

  private fun tickAt(seconds: Double): FloatArray =
    floatArrayOf((FilmSignal.grainTickAt(seconds) and HASH_MASK).toFloat())

  private fun frameSize(
    width: Int,
    height: Int,
  ): Size = Size(width, height)
}

private const val FRAME = GLOW_FRAME
private const val GLOW = GLOW_BLURRED
private const val SEED = "uSeed"
private const val TICK = "uTick"

private const val STEP_WEAVE = "weave"
private const val STEP_HALATION = "halation"
private const val STEP_GRAIN = "grain"

// Reads the frame at each pixel's place plus weaveAt's offset through straightRead, held at the frame's edge.
private val WEAVE_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform float ${FilmShader.FLIP_V};
  uniform float $SEED, $TICK, uWeave;
$HASH_GLSL
$FILM_GATE_GLSL
$STRAIGHT_READ_GLSL

  void main() {
    vec2 offset = weaveAt(uint($TICK + 0.5), uWeave);
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${FilmShader.FLIP_V}) + offset.y;
    fragColor = straightRead($FRAME, vec2(vUv.x + offset.x, mix(fromTop, 1.0 - fromTop, ${FilmShader.FLIP_V})));
  }
  """.trimIndent()
}

// halationResponse in GLSL ES 3.00, for GlowSteps' response fragment. Each line carries the fragment sources' own two
// spaces.
private val HALATION_RESPONSE_GLSL =
  """
  vec3 glowResponse(vec3 boxed) {
    float response = smoothstep(${glslFloat(HALATION_LOW)}, ${glslFloat(HALATION_HIGH)}, lumaOf(boxed));
    return response * vec3(
      ${glslFloat(HALATION_TINT_RED)},
      ${glslFloat(HALATION_TINT_GREEN)},
      ${glslFloat(HALATION_TINT_BLUE)}
    );
  }
  """

private val HALATION_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform sampler2D $GLOW;
  uniform float uGain;

  void main() {
    vec4 texel = textureLod($FRAME, vUv, 0.0);
    vec3 glowed = min(texel.rgb + uGain * textureLod($GLOW, vUv, 0.0).rgb, vec3(1.0));
    fragColor = vec4(glowed, texel.a);
  }
  """.trimIndent()
}

// The leak, the fade and breath, the flicker's gain, the grain, the dust and scratches, and the gate, in
// FilmSignal.pixelAt's order.
private val GRAIN_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uResolution;
  uniform float ${FilmShader.FLIP_V};
  uniform float $SEED, $TICK, uStrength, uCellSpan, uFlicker, uDust, uGate, uLeak, uBreath, uFade;
$LUMA_GLSL
$HASH_GLSL
$FILM_GATE_GLSL
$FILM_LEAK_GLSL
$FILM_TONE_GLSL

  void main() {
    vec4 texel = textureLod($FRAME, vUv, 0.0);
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${FilmShader.FLIP_V});
    uint span = uint(uCellSpan + 0.5);
    uint column = uint(clamp(floor(vUv.x * uResolution.x), 0.0, uResolution.x - 1.0)) * ${GRAIN_CELL_SCALE}u / span;
    uint row = uint(clamp(floor(fromTop * uResolution.y), 0.0, uResolution.y - 1.0)) * ${GRAIN_CELL_SCALE}u / span;
    uint cell = row * ${GRAIN_CELLS_ACROSS}u + column;
    uint tick = uint($TICK + 0.5);
    float aspect = uResolution.x / uResolution.y;
    vec3 light = leakAt(vec2(vUv.x, fromTop), aspect, tick, uLeak);
    vec3 leaked = texel.rgb + light * (1.0 - texel.rgb);
    vec3 lit = clamp(filmToned(leaked, tick, uBreath, uFade) * flickerAt(tick, uFlicker), 0.0, 1.0);
    float grain = draw(${PASS_GRAIN_FIRST}u, tick, cell) + draw(${PASS_GRAIN_SECOND}u, tick, cell) - 1.0;
    float luma = lumaOf(lit);
    float weight = max(${glslFloat(GRAIN_WEIGHT_FLOOR)}, ${glslFloat(GRAIN_WEIGHT_PEAK)} * luma * (1.0 - luma));
    vec3 grained = clamp(lit + uStrength * weight * grain, 0.0, 1.0);
    float mark = dustAt(tick, cell, uDust) + scratchAt(tick, vUv.x, uDust);
    float gate = gateMask(vec2(vUv.x, fromTop), aspect, uGate);
    fragColor = vec4(clamp(grained + mark, 0.0, 1.0) * gate, texel.a);
  }
  """.trimIndent()
}

// FilmSignal's weaveAt, flickerAt, dustAt, scratchAt and gateMask in GLSL ES 3.00, for a fragment that holds
// HASH_GLSL's draw under the uSeed uniform. Ticks are whole and never below 0. Each line carries the fragment sources'
// own two spaces, so trimIndent still lands #version first.
internal val FILM_GATE_GLSL: String =
  """
  float filmKnotted(uint pass, uint knot, float ease) {
    float from = draw(pass, knot, 0u) * 2.0 - 1.0;
    float to = draw(pass, knot + 1u, 0u) * 2.0 - 1.0;
    return from + (to - from) * ease;
  }

  vec2 weaveAt(uint tick, float weave) {
    uint knot = tick / ${WEAVE_KNOT_TICKS}u;
    float f = float(tick - knot * ${WEAVE_KNOT_TICKS}u) / ${glslFloat(WEAVE_KNOT_TICKS.toDouble())};
    float ease = f * f * (3.0 - 2.0 * f);
    return weave * vec2(
      ${glslFloat(WEAVE_REACH_X)} * filmKnotted(${PASS_WEAVE_X}u, knot, ease),
      ${glslFloat(WEAVE_REACH_Y)} * filmKnotted(${PASS_WEAVE_Y}u, knot, ease)
    );
  }

  float flickerAt(uint tick, float flicker) {
    return 1.0 + ${glslFloat(FLICKER_DEPTH)} * flicker * (draw(${PASS_FLICKER}u, tick, 0u) * 2.0 - 1.0);
  }

  float dustAt(uint tick, uint cell, float dust) {
    uint column = cell % ${GRAIN_CELLS_ACROSS}u;
    uint row = cell / ${GRAIN_CELLS_ACROSS}u;
    uint block = (row / ${DUST_CELL}u) * ${DUST_CELLS_ACROSS}u + column / ${DUST_CELL}u;
    if (draw(${PASS_DUST}u, tick, block) >= ${glslFloat(DUST_PER_FRAME)} * dust / ${glslFloat(DUST_CELLS.toDouble())}) {
      return 0.0;
    }
    float radius = ${glslFloat(DUST_RADIUS_MIN)} +
      ${glslFloat(DUST_RADIUS_MAX - DUST_RADIUS_MIN)} * draw(${PASS_DUST_SIZE}u, tick, block);
    float dx = float(column % ${DUST_CELL}u) + 0.5 - ${glslFloat(DUST_CELL / 2.0)};
    float dy = float(row % ${DUST_CELL}u) + 0.5 - ${glslFloat(DUST_CELL / 2.0)};
    if (dx * dx + dy * dy > radius * radius) return 0.0;
    return draw(${PASS_DUST_TONE}u, tick, block) < 0.5 ? ${glslFloat(DUST_LEVEL)} : -${glslFloat(DUST_LEVEL)};
  }

  float scratchAt(uint tick, float x, float dust) {
    uint run = tick / ${SCRATCH_RUN_TICKS}u;
    if (draw(${PASS_SCRATCH}u, run, 0u) >= dust) return 0.0;
    float lives = ${glslFloat((SCRATCH_LIFE_MAX - SCRATCH_LIFE_MIN + 1).toDouble())};
    float life = ${glslFloat(SCRATCH_LIFE_MIN.toDouble())} + floor(draw(${PASS_SCRATCH_LIFE}u, run, 0u) * lives);
    float start = floor(draw(${PASS_SCRATCH_START}u, run, 0u) * (${glslFloat(
    SCRATCH_RUN_TICKS.toDouble(),
  )} - life + 1.0));
    float age = float(tick - run * ${SCRATCH_RUN_TICKS}u) - start;
    if (age < 0.0 || age >= life) return 0.0;
    float place = ${glslFloat(
    SCRATCH_EDGE,
  )} + ${glslFloat(1.0 - 2.0 * SCRATCH_EDGE)} * draw(${PASS_SCRATCH_PLACE}u, run, 0u);
    float drift = ${glslFloat(SCRATCH_DRIFT)} * (draw(${PASS_SCRATCH_DRIFT}u, run, 0u) * 2.0 - 1.0);
    return abs(x - place - drift * age) <= ${glslFloat(SCRATCH_WIDTH / 2.0)} ? ${glslFloat(SCRATCH_LEVEL)} : 0.0;
  }

  float gateMask(vec2 at, float aspect, float gate) {
    if (gate <= 0.0) return 1.0;
    float inset = ${glslFloat(GATE_INSET)} * gate;
    float radius = ${glslFloat(GATE_RADIUS)} * gate;
    float softness = ${glslFloat(GATE_SOFTNESS)} * gate;
    vec2 q = abs(at - 0.5) * vec2(aspect, 1.0) - (vec2(0.5 * aspect, 0.5) - inset - radius);
    float past = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    return 1.0 - smoothstep(-softness, softness, past);
  }
  """

// The same functions in Metal, for a kernel that holds HASH_MSL's filterDraw, each taking the seed first. Each line
// carries the kernel sources' own two spaces, so trimIndent still lands the includes first.
internal val FILM_GATE_MSL: String =
  """
  float filmKnotted(uint seed, uint pass, uint knot, float ease) {
    float from = filterDraw(seed, pass, knot, 0u) * 2.0f - 1.0f;
    float to = filterDraw(seed, pass, knot + 1u, 0u) * 2.0f - 1.0f;
    return from + (to - from) * ease;
  }

  float2 weaveAt(uint seed, uint tick, float weave) {
    uint knot = tick / ${WEAVE_KNOT_TICKS}u;
    float f = float(tick - knot * ${WEAVE_KNOT_TICKS}u) / ${metalFloat(WEAVE_KNOT_TICKS.toDouble())};
    float ease = f * f * (3.0f - 2.0f * f);
    return weave * float2(
      ${metalFloat(WEAVE_REACH_X)} * filmKnotted(seed, ${PASS_WEAVE_X}u, knot, ease),
      ${metalFloat(WEAVE_REACH_Y)} * filmKnotted(seed, ${PASS_WEAVE_Y}u, knot, ease)
    );
  }

  float flickerAt(uint seed, uint tick, float flicker) {
    return 1.0f + ${metalFloat(FLICKER_DEPTH)} * flicker * (filterDraw(seed, ${PASS_FLICKER}u, tick, 0u) * 2.0f - 1.0f);
  }

  float dustAt(uint seed, uint tick, uint cell, float dust) {
    uint column = cell % ${GRAIN_CELLS_ACROSS}u;
    uint row = cell / ${GRAIN_CELLS_ACROSS}u;
    uint block = (row / ${DUST_CELL}u) * ${DUST_CELLS_ACROSS}u + column / ${DUST_CELL}u;
    float chance = ${metalFloat(DUST_PER_FRAME)} * dust / ${metalFloat(DUST_CELLS.toDouble())};
    if (filterDraw(seed, ${PASS_DUST}u, tick, block) >= chance) return 0.0f;
    float radius = ${metalFloat(DUST_RADIUS_MIN)} +
      ${metalFloat(DUST_RADIUS_MAX - DUST_RADIUS_MIN)} * filterDraw(seed, ${PASS_DUST_SIZE}u, tick, block);
    float dx = float(column % ${DUST_CELL}u) + 0.5f - ${metalFloat(DUST_CELL / 2.0)};
    float dy = float(row % ${DUST_CELL}u) + 0.5f - ${metalFloat(DUST_CELL / 2.0)};
    if (dx * dx + dy * dy > radius * radius) return 0.0f;
    float level = ${metalFloat(DUST_LEVEL)};
    return filterDraw(seed, ${PASS_DUST_TONE}u, tick, block) < 0.5f ? level : -level;
  }

  float scratchAt(uint seed, uint tick, float x, float dust) {
    uint run = tick / ${SCRATCH_RUN_TICKS}u;
    if (filterDraw(seed, ${PASS_SCRATCH}u, run, 0u) >= dust) return 0.0f;
    float lives = ${metalFloat((SCRATCH_LIFE_MAX - SCRATCH_LIFE_MIN + 1).toDouble())};
    float life = ${metalFloat(
    SCRATCH_LIFE_MIN.toDouble(),
  )} + floor(filterDraw(seed, ${PASS_SCRATCH_LIFE}u, run, 0u) * lives);
    float span = ${metalFloat(SCRATCH_RUN_TICKS.toDouble())} - life + 1.0f;
    float start = floor(filterDraw(seed, ${PASS_SCRATCH_START}u, run, 0u) * span);
    float age = float(tick - run * ${SCRATCH_RUN_TICKS}u) - start;
    if (age < 0.0f || age >= life) return 0.0f;
    float spread = ${metalFloat(1.0 - 2.0 * SCRATCH_EDGE)};
    float place = ${metalFloat(SCRATCH_EDGE)} + spread * filterDraw(seed, ${PASS_SCRATCH_PLACE}u, run, 0u);
    float drift = ${metalFloat(SCRATCH_DRIFT)} * (filterDraw(seed, ${PASS_SCRATCH_DRIFT}u, run, 0u) * 2.0f - 1.0f);
    return abs(x - place - drift * age) <= ${metalFloat(SCRATCH_WIDTH / 2.0)} ? ${metalFloat(SCRATCH_LEVEL)} : 0.0f;
  }

  float gateMask(float2 at, float aspect, float gate) {
    if (gate <= 0.0f) return 1.0f;
    float inset = ${metalFloat(GATE_INSET)} * gate;
    float radius = ${metalFloat(GATE_RADIUS)} * gate;
    float softness = ${metalFloat(GATE_SOFTNESS)} * gate;
    float2 q = abs(at - 0.5f) * float2(aspect, 1.0f) - (float2(0.5f * aspect, 0.5f) - inset - radius);
    float past = length(max(q, 0.0f)) + min(max(q.x, q.y), 0.0f) - radius;
    return 1.0f - smoothstep(-softness, softness, past);
  }
  """

internal fun metalFloat(value: Double): String = "${glslFloat(value)}f"
