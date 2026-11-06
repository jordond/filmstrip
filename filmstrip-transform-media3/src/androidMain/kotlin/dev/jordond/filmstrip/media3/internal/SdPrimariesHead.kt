package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.color.BT709_ALPHA
import dev.jordond.filmstrip.color.BT709_BETA
import dev.jordond.filmstrip.color.BT709_SLOPE
import dev.jordond.filmstrip.color.POWER_SEGMENT_EXPONENT
import dev.jordond.filmstrip.color.sdPrimariesToBt709

/**
 * Moves an SD clip's frame off color primaries code point [primaries] onto BT.709's, ahead of the clip's own effects.
 *
 * The external sampler decodes a frame with its real matrix and leaves its primaries alone, so the frame reaches this
 * pass as the source's signal on the source's primaries. It only runs on an SDR pipeline, since an SD clip is an SDR
 * clip and the shared planner refuses to keep an HDR grade beside one, or tone maps the two together on request.
 */
internal data class SdPrimariesHead(
  val primaries: Int,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    check(!useHdr) { "An SD clip is never drawn into an HDR pipeline." }
    return FullFramePass(useHdr, sdPrimariesShader(primaries))
  }
}

/**
 * The fragment shader of [SdPrimariesHead], the steps of `sdSourceDisplayed` with every figure read off the shared
 * BT.709 curve and the shared matrix.
 */
internal fun sdPrimariesShader(primaries: Int): String =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  varying vec2 vTexSamplingCoord;
  const mat3 PRIMARIES = ${sdPrimariesToBt709(primaries).glslColumnMajor()};
  vec3 bt709Light(vec3 signal) {
    vec3 power = pow((signal + $BT709_ALPHA - 1.0) / $BT709_ALPHA, vec3(1.0 / $POWER_SEGMENT_EXPONENT));
    return mix(signal / $BT709_SLOPE, power, step($BT709_SLOPE * $BT709_BETA, signal));
  }
  vec3 bt709Signal(vec3 light) {
    vec3 power = $BT709_ALPHA * pow(light, vec3($POWER_SEGMENT_EXPONENT)) - ($BT709_ALPHA - 1.0);
    return mix(light * $BT709_SLOPE, power, step($BT709_BETA, light));
  }
  void main() {
    vec4 frame = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 light = bt709Light(clamp(frame.rgb, 0.0, 1.0));
    gl_FragColor = vec4(bt709Signal(clamp(PRIMARIES * light, 0.0, 1.0)), frame.a);
  }
  """.trimIndent()

// Core stores a matrix row by row, and a GLSL constructor reads column by column.
private fun DoubleArray.glslColumnMajor(): String =
  (0 until COLUMNS)
    .flatMap { column -> (0 until COLUMNS).map { row -> this[row * COLUMNS + column] } }
    .joinToString(", ", prefix = "mat3(", postfix = ")")

private const val COLUMNS = 3
