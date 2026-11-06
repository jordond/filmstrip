package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.CanvasPlacement
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import androidx.media3.common.util.Size as Media3Size

/**
 * Draws the frame it is handed at [rect] of a frame of [size], leaving zero alpha wherever the frame does not reach.
 *
 * This is how a clip is placed in the output frame at the size stage, and how a crop that letterboxes or stretches
 * builds its canvas inside the clip's chain. A frame [rect] runs past is cut at the edge.
 *
 * media3 hands every effect a texture of whole pixels and draws into one without multisampling, so a frame's edge can
 * only land on a pixel edge. That is media3's limit. [rect] is drawn rounded by the shared [inWholePixelsOf], the same
 * pixels every whole pixel backend draws.
 *
 * @property rect Where the frame lands, as a fraction of the frame drawn into.
 * @property size The size of the frame drawn into, whatever size the frame handed in is.
 */
internal data class PlacedFrame(
  val rect: NormalizedRect,
  val size: Size,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = PlacedFrameProgram(useHdr, this)

  override fun isNoOp(
    inputWidth: Int,
    inputHeight: Int,
  ): Boolean =
    size == Size(inputWidth, inputHeight) && rect.inWholePixelsOf(size) == PixelRect(0, 0, size.width, size.height)

  companion object {
    /**
     * The pass that builds [canvas] out of the frame entering the crop that makes it. The canvas is
     * [CanvasPlacement.size], the frame the planner measures every later effect against.
     */
    fun canvasOf(canvas: CanvasPlacement): PlacedFrame = PlacedFrame(canvas.contentInCanvas, canvas.size)
  }
}

/**
 * Runs one [PlacedFrame], drawing whatever frame it is handed into its fixed size.
 */
private class PlacedFrameProgram(
  useHdr: Boolean,
  private val placed: PlacedFrame,
) : BaseGlShaderProgram(useHdr, 1) {
  private val glProgram =
    try {
      GlProgram(FRAME_VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  init {
    glProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
  }

  // Texture coordinates run from the bottom left, so the rect's top and bottom swap over on the way in.
  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Media3Size {
    val output = placed.size
    val pixels = placed.rect.inWholePixelsOf(output)
    val width = output.width.toFloat()
    val height = output.height.toFloat()
    glProgram.setFloatsUniform("uRectMin", floatArrayOf(pixels.left / width, 1f - pixels.bottom / height))
    glProgram.setFloatsUniform("uRectMax", floatArrayOf(pixels.right / width, 1f - pixels.top / height))
    return Media3Size(output.width, output.height)
  }

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      glProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }
}

// A pixel whose centre falls inside the rect samples the frame, stretched over the rect, and every other pixel takes
// the bars. The rect sits on whole pixel edges, so no centre ever lands on one.
private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec2 uRectMin;
  uniform vec2 uRectMax;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec2 at = vTexSamplingCoord;
    if (all(greaterThanEqual(at, uRectMin)) && all(lessThan(at, uRectMax))) {
      gl_FragColor = texture2D(uTexSampler, (at - uRectMin) / (uRectMax - uRectMin));
    } else {
      gl_FragColor = vec4(0.0);
    }
  }
  """.trimIndent()
