package dev.jordond.filmstrip.media3.internal.fill

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media3.internal.FullFramePass

/**
 * Paints an opaque fill color wherever a frame carries no pixels of its own.
 *
 * media3 clears a letterbox bar to black with zero alpha, and a color effect changes a pixel's
 * color without ever touching its alpha. Run after the composition's effects and before its
 * overlays, this reads that leftover alpha to tell an empty pixel from a real one and writes [color]
 * over it, whatever color an earlier effect left behind.
 *
 * A timeline gap in the video is laid as a still with every pixel at zero alpha. The composition's
 * presentation fits it into the output frame like any other picture, so the still and the bars
 * beside it all read as empty and this pass covers the gap with [color] edge to edge.
 *
 * @property color Packed ARGB the empty region is painted with. Alpha is ignored, the pass always
 *   writes full opacity.
 * @property transfer The transfer function reaching the encoder, or null when the export is SDR.
 *   An HDR frame reaches this pass as linear light, where an sRGB channel means nothing on its own.
 */
internal class FillFlatten(
  private val color: Int,
  private val transfer: HdrTransfer? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    val fill = fillComponents(color, transfer.takeIf { useHdr })
    return FullFramePass(useHdr, FRAGMENT_SHADER) { setFloatsUniform("uFill", fill) }
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that
  // produced the same pass again.
  override fun equals(other: Any?): Boolean =
    this === other || (other is FillFlatten && color == other.color && transfer == other.transfer)

  override fun hashCode(): Int = HASH * color + transfer.hashCode()

  private companion object {
    const val HASH = 31
  }
}

// A pixel holds its color multiplied by its alpha, so the fill shows through in the share the pixel leaves clear. A
// clear pixel adds nothing of its own, since a color effect may have lifted its color without its alpha. The result is
// written back fully opaque.
private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec3 uFill;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 own = src.a > 0.0 ? src.rgb : vec3(0.0);
    gl_FragColor = vec4(own + uFill * (1.0 - src.a), 1.0);
  }
  """.trimIndent()
