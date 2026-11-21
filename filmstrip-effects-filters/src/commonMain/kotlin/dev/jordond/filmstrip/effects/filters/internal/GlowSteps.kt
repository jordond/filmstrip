package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.spatial.SpatialBlur
import dev.jordond.filmstrip.geometry.Size

// The sampler a glow's steps read the frame through, and the one its last step reads the blurred response through.
internal const val GLOW_FRAME = "uFrame"
internal const val GLOW_BLURRED = "uGlow"
internal const val STEP_GLOW_RESPONSE = "response"

// lumaOf in GLSL ES 3.00. Each line carries the fragment sources' own two spaces, so trimIndent still lands #version
// first.
internal val LUMA_GLSL =
  """
  const float KR = ${glslFloat(KR)};
  const float KB = ${glslFloat(KB)};
  float lumaOf(vec3 c) { return KR * c.r + (1.0 - KR - KB) * c.g + KB * c.b; }
  """

// The steps every glow runs, in GlowField's order. The frame boxes down to the blur's size and takes its response,
// SpatialBlur blurs that by the plan's kernel, and the last step lays the blurred response over the frame at its own
// size.
internal object GlowSteps {
  // responseGlsl defines vec3 glowResponse(vec3 boxed) and any uniform it reads, which responseUniforms fills.
  // combineFragment reads GLOW_FRAME and GLOW_BLURRED, and combineUniforms fills the rest of its uniforms.
  fun of(
    sigmaFraction: Double,
    reads: String,
    responseGlsl: String,
    responseUniforms: Map<String, FloatArray>,
    combineName: String,
    combineFragment: String,
    combineUniforms: Map<String, FloatArray>,
  ): List<FilterStep> {
    val blurredSize = { width: Int, height: Int -> SpatialBlur.blurredSize(sigmaFraction, width, height) }
    val response =
      FilterStep(
        STEP_GLOW_RESPONSE,
        responseFragment(responseGlsl),
        mapOf(GLOW_FRAME to reads),
        blurredSize,
      ) { _, width, height ->
        val plan = BlurPlan.of(sigmaFraction, width)
        responseUniforms +
          mapOf(
            "uTargetSize" to floatArrayOf(plan.width.toFloat(), plan.heightOf(height).toFloat()),
            "uBox" to floatArrayOf(plan.factor.toFloat()),
          )
      }
    val blur =
      SpatialBlur.gaussianSteps(sigmaFraction, STEP_GLOW_RESPONSE).map { step ->
        FilterStep(step.name, step.fragment, step.reads, step.targetAt) { _, width, height ->
          step.uniformsAt(width, height)
        }
      }
    val combine =
      FilterStep(
        combineName,
        combineFragment,
        mapOf(GLOW_FRAME to reads, GLOW_BLURRED to SpatialBlur.BLURRED),
        { width, height -> Size(width, height) },
      ) { _, _, _ -> combineUniforms }
    return listOf(response) + blur + combine
  }

  // Boxes the frame's codes down to one blurred pixel with SpatialBlur's box, each texel premultiplied by its alpha
  // so a bar gives no light, and reads the response of the result.
  private fun responseFragment(responseGlsl: String): String =
    """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $GLOW_FRAME;
  uniform vec2 uTargetSize;
  uniform float uBox;
$LUMA_GLSL
$responseGlsl

  vec4 premultipliedAt(ivec2 texel, ivec2 size) {
    vec4 read = texelFetch($GLOW_FRAME, clamp(texel, ivec2(0), size - 1), 0);
    return vec4(read.rgb * read.a, read.a);
  }

${SpatialBlur.BOX_DOWN_GLSL.prependIndent("  ")}

  void main() {
    fragColor = vec4(glowResponse(boxedDown(textureSize($GLOW_FRAME, 0), vUv, uTargetSize, uBox).rgb), 1.0);
  }
    """.trimIndent()
}
