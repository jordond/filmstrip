package dev.jordond.filmstrip.effects.filters

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * Gives the 2D texture bound now one level of 32 bit float storage, [width] by [height], for a test to draw into and
 * read back as floats.
 *
 * The level comes from `glTexImage2D`, which tells the emulator's GL encoder the texture holds floats. The encoder
 * never sets the type it keeps for a `glTexStorage2D` texture, and it refuses a float read with an invalid operation
 * whenever that unset memory happens to hold an integer type.
 */
internal fun allocateFloatTarget(
  width: Int,
  height: Int,
) {
  GLES20.glTexImage2D(
    GLES20.GL_TEXTURE_2D,
    0,
    GLES30.GL_RGBA32F,
    width,
    height,
    0,
    GLES20.GL_RGBA,
    GLES20.GL_FLOAT,
    null,
  )
  GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, 0)
}
