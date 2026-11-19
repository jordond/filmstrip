package dev.jordond.filmstrip.media3.internal.fill

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media3.internal.FullFramePass

/**
 * Paints every pixel of a solid's frame in its [color], the way [FillFlatten] paints the fill.
 *
 * A solid is laid as a PNG of its color, and on an HDR grade media3 reads that PNG as SDR and carries it into linear
 * light at its own SDR level. Running ahead of the solid's effects, this writes the color where the shared HDR light
 * puts it instead, so the PNG's own pixels never reach the output on either grade. It runs after the heads that
 * unpremultiply a picture, since what it writes is straight already.
 *
 * @property color Packed ARGB the frame is painted with, its alpha kept.
 * @property transfer The transfer function reaching the encoder, or null when the export is SDR.
 */
internal class SolidPaint(
  private val color: Int,
  private val transfer: HdrTransfer? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram =
    FullFramePass(useHdr, FRAGMENT_SHADER, samplesInput = false) { setFloatsUniform("uPaint", paintOf(useHdr)) }

  /**
   * The straight red, green, blue and alpha this pass writes, on an HDR pipeline when [useHdr] is set.
   */
  fun paintOf(useHdr: Boolean): FloatArray =
    fillComponents(color, transfer.takeIf { useHdr }) + (color ushr ALPHA_SHIFT) / OPAQUE

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that
  // produced the same pass again.
  override fun equals(other: Any?): Boolean =
    this === other || (other is SolidPaint && color == other.color && transfer == other.transfer)

  override fun hashCode(): Int = HASH * color + transfer.hashCode()

  private companion object {
    const val HASH = 31
    const val ALPHA_SHIFT = 24
    const val OPAQUE = 255f
  }
}

private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform vec4 uPaint;
  void main() {
    gl_FragColor = uPaint;
  }
  """.trimIndent()
