package dev.jordond.filmstrip.effects.filters.camcorder

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effects.filters.internal.FULL_FRAME_VERTEX_SHADER
import dev.jordond.filmstrip.effects.filters.internal.requireGles3
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.microseconds

/**
 * Draws a [Camcorder] as one media3 pass running [CamcorderShader] over the display [CamcorderOsd.rasterAt] draws.
 *
 * The planner tone maps an HDR source for a [Camcorder], so a frame processor in HDR here is a bug and is refused by
 * name. [CamcorderShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context, and a context below it
 * is refused by name too.
 */
internal class CamcorderEffect(
  private val osd: CamcorderOsd,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Camcorder requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Camcorder", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return CamcorderShaderProgram(osd)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean =
    this === other || (other is CamcorderEffect && osd.spec == other.osd.spec && osd.rate == other.osd.rate)

  override fun hashCode(): Int = 31 * osd.spec.hashCode() + osd.rate.hashCode()
}

/**
 * Lays the display over a frame the same size as the input, uploading it whole once for each size and then only the
 * region [CamcorderOsd.changedRegion] names when what it shows changes.
 *
 * A frame's display comes from its presentation time alone, so a seek lands on what an export draws there.
 */
internal class CamcorderShaderProgram(
  private val osd: CamcorderOsd,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private var width = 1
  private var height = 1
  private var drawn: OsdState? = null
  private var buffer: ByteBuffer = ByteBuffer.allocateDirect(0)
  private val display: Int = displayTexture()

  private val program =
    try {
      GlProgram(FULL_FRAME_VERTEX_SHADER, CamcorderShader.FRAGMENT).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size {
    if (inputWidth != width || inputHeight != height) drawn = null
    width = inputWidth
    height = inputHeight
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      val state = osd.stateAt(presentationTimeUs.microseconds)
      val previous = drawn
      if (previous == null) {
        uploadWhole(state)
      } else if (state != previous) {
        osd.changedRegion(previous, state, width, height)?.let { uploadRegion(state, it) }
      }
      drawn = state
      program.use()
      program.setSamplerTexIdUniform(CamcorderShader.FRAME, inputTexId, 0)
      program.setSamplerTexIdUniform(CamcorderShader.DISPLAY, display, 1)
      program.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException.from(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      program.delete()
      GLES20.glDeleteTextures(1, intArrayOf(display), 0)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  private fun uploadWhole(state: OsdState) {
    val raster = osd.rasterOf(state, width, height)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, display)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D,
      0,
      GLES20.GL_RGBA,
      width,
      height,
      0,
      GLES20.GL_RGBA,
      GLES20.GL_UNSIGNED_BYTE,
      filled(raster),
    )
  }

  private fun uploadRegion(
    state: OsdState,
    region: PixelRect,
  ) {
    val raster = osd.rasterOf(state, width, height, region)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, display)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    GLES20.glTexSubImage2D(
      GLES20.GL_TEXTURE_2D,
      0,
      region.left,
      region.top,
      region.width,
      region.height,
      GLES20.GL_RGBA,
      GLES20.GL_UNSIGNED_BYTE,
      filled(raster),
    )
  }

  // The one upload buffer holding [raster]'s RGBA bytes, grown only when a raster outgrows it.
  private fun filled(raster: OsdRaster): ByteBuffer {
    val bytes = raster.rgbaBytes()
    if (buffer.capacity() < bytes.size) buffer = ByteBuffer.allocateDirect(bytes.size)
    buffer.clear()
    buffer.put(bytes).flip()
    return buffer
  }

  private companion object {
    const val TEXTURE_POOL_CAPACITY = 1

    // A texture read with texelFetch alone, so nearest filtering with no mipmaps keeps it complete.
    fun displayTexture(): Int {
      val texture = IntArray(1)
      GLES20.glGenTextures(1, texture, 0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      return texture[0]
    }
  }
}
