package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.STRAIGHT_READ_GLSL
import dev.jordond.filmstrip.effects.filters.internal.glslFloat

/**
 * The GLSL ES 3.00 fragment source that draws a [Crt], read by media3 and the browser.
 *
 * Every fixed number is written in from [CrtSignal]'s constants, and everything a control or a frame size changes is a
 * uniform from [uniforms], so the shader holds no figure of its own. It reads at the `vUv` varying, and draws the same
 * with `v` running either way, since the curve and falloff are symmetric about the centre and a whole count of
 * scanlines reads the same from both ends.
 *
 * A clip's frame holds clear bars. Alpha moves with the curve and a read past the frame takes the alpha of the edge
 * under it, while the picture fades by [CrtSignal.coverageAt]. So a picture ends in black and a bar stays clear out to
 * the edge of the frame. A read between texels mixes them premultiplied, so a picture beside a bar keeps its colour.
 */
@InternalFilmstripApi
public object CrtShader {
  /**
   * The sampler the frame is bound to, which has to clamp to its edge.
   */
  public const val TEXTURE: String = "uTexture"

  /**
   * The fragment source.
   */
  public val FRAGMENT: String by lazy { fragmentSource() }

  /**
   * The uniforms for [crt] on a frame [width] by [height], by name, each a float or a vec by its length.
   */
  public fun uniforms(
    crt: Crt,
    width: Int,
    height: Int,
  ): Map<String, FloatArray> {
    val params = CrtParams.of(crt)
    val fit = CrtSignal.tubeFit(width, height)
    return mapOf(
      "uFit" to floatArrayOf(fit[0].toFloat(), fit[1].toFloat()),
      "uFootprint" to floatArrayOf(1f / width, 1f / height),
      "uBarrelK" to floatArrayOf(params.barrelK.toFloat()),
      "uScanlineDepth" to floatArrayOf(params.scanlineDepth.toFloat()),
      "uScanlineAverage" to floatArrayOf(CrtSignal.footprintAverage(CRT_LINES, 1.0 / height).toFloat()),
      "uMaskDepth" to floatArrayOf(params.maskDepth.toFloat()),
      "uMaskAverage" to floatArrayOf(CrtSignal.footprintAverage(CRT_TRIADS, 1.0 / width).toFloat()),
      "uVignette" to floatArrayOf(params.vignette.toFloat()),
      "uGain" to floatArrayOf(params.gain.toFloat()),
    )
  }
}

private fun fragmentSource(): String =
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D ${CrtShader.TEXTURE};
  uniform vec2 uFit, uFootprint;
  uniform float uBarrelK, uScanlineDepth, uScanlineAverage, uMaskDepth, uMaskAverage, uVignette, uGain;

  float stripe(float phase, float average, float depth) {
    return 1.0 - depth * (0.5 - 0.5 * cos(phase) * average);
  }
$STRAIGHT_READ_GLSL
  void main() {
    vec2 centred = vUv - 0.5;
    vec2 fitted = 2.0 * centred * uFit;
    float radius = dot(fitted, fitted);
    vec2 source = 0.5 + centred * (1.0 + uBarrelK * radius);
    vec4 texel = straightRead(${CrtShader.TEXTURE}, source);
    vec2 fitSquared = uFit * uFit;
    float turn = abs(${glslFloat(CURVE_SLOPE)} * uBarrelK * centred.x * centred.y);
    vec2 stretch = 1.0 + uBarrelK * radius + ${glslFloat(CURVE_SLOPE)} * uBarrelK * centred * centred * fitSquared;
    vec2 reach = stretch * uFootprint + turn * fitSquared.yx * uFootprint.yx;
    vec2 share = clamp(min(source, 1.0 - source) / reach + 0.5, 0.0, 1.0);
    float coverage = share.x * share.y;
    float scanline = stripe(${glslFloat(TWO_PI * CRT_LINES)} * source.y, uScanlineAverage, uScanlineDepth);
    float across = ${glslFloat(TWO_PI * CRT_TRIADS)} * source.x;
    vec3 mask = vec3(
      stripe(across - ${glslFloat(CrtSignal.maskPhase(0))}, uMaskAverage, uMaskDepth),
      stripe(across - ${glslFloat(CrtSignal.maskPhase(1))}, uMaskAverage, uMaskDepth),
      stripe(across - ${glslFloat(CrtSignal.maskPhase(2))}, uMaskAverage, uMaskDepth)
    );
    float vignette = 1.0 - uVignette * radius / dot(uFit, uFit);
    // An encoded code stops at full scale, so the gain's lift ends there too.
    fragColor = vec4(min(texel.rgb * scanline * mask * vignette * uGain, vec3(1.0)) * coverage, texel.a);
  }
  """.trimIndent()
