package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Divides a frame's color by its alpha, so every effect after it and the fill passes read straight color.
 *
 * media3 uploads a picture with alpha the way Android decodes it, with the color already multiplied by the alpha.
 * Every effect in the chain, and the pass that lays the fill under the frame, reads the color as the color of the
 * pixel, so a clip whose pictures can be transparent opens on this pass. A pixel with no alpha keeps a black color.
 */
internal data object StraightAlphaHead : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = FullFramePass(useHdr, FRAGMENT_SHADER)
}

private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec4 frame = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 straight = frame.a > 0.0 ? frame.rgb / frame.a : vec3(0.0);
    gl_FragColor = vec4(straight, frame.a);
  }
  """.trimIndent()
