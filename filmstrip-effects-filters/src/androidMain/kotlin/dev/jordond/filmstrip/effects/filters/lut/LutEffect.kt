package dev.jordond.filmstrip.effects.filters.lut

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

/**
 * Draws a [Lut] as one media3 pass running [LutShader] over its [LutAtlas].
 *
 * The planner tone maps an HDR source for a [Lut], so a frame processor in HDR here is a bug and is refused by name. A
 * context below OpenGL ES 3, or a table whose atlas does not fit the context's `MAX_TEXTURE_SIZE`, is the device's
 * limit and is refused by name too.
 */
internal class LutEffect(
  private val lut: Lut,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Lut requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Lut", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    val maxTextureSize = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
    requireLutFits(lut.table.size, maxTextureSize[0])
    return LutShaderProgram(lut)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is LutEffect && lut == other.lut)

  override fun hashCode(): Int = lut.hashCode()
}

/**
 * Refuses a table of [size] grid points by name when its atlas does not fit [maxTextureSize].
 */
internal fun requireLutFits(
  size: Int,
  maxTextureSize: Int,
) {
  lutAtlasLimit(size, maxTextureSize)?.let { throw VideoFrameProcessingException(it) }
}

/**
 * Runs [LutShader] over a frame the same size as the input, with the atlas uploaded once for the life of the program.
 */
internal class LutShaderProgram(
  lut: Lut,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private val atlas = lutAtlas(lut.table.blended(lut.intensity))
  private val uniforms = LutShader.uniforms(atlas)

  private val program =
    try {
      GlProgram(FULL_FRAME_VERTEX_SHADER, LutShader.FRAGMENT).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  private val table = uploadAtlas(atlas)

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size = Size(inputWidth, inputHeight)

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      program.use()
      program.setSamplerTexIdUniform(LutShader.TEXTURE, inputTexId, 0)
      program.setSamplerTexIdUniform(LutShader.TABLE, table, 1)
      uniforms.forEach { (name, value) -> program.setFloatsUniform(name, value) }
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
      GLES20.glDeleteTextures(1, intArrayOf(table), 0)
      program.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  private fun uploadAtlas(atlas: LutAtlas): Int {
    val texture = IntArray(1)
    GLES20.glGenTextures(1, texture, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D,
      0,
      GLES20.GL_RGBA,
      atlas.width,
      atlas.height,
      0,
      GLES20.GL_RGBA,
      GLES20.GL_UNSIGNED_BYTE,
      ByteBuffer.wrap(atlas.bytes),
    )
    try {
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return texture[0]
  }

  private companion object {
    const val TEXTURE_POOL_CAPACITY = 1
  }
}
