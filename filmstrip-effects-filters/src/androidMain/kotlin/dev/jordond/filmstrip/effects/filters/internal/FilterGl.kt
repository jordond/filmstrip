package dev.jordond.filmstrip.effects.filters.internal

import androidx.media3.common.VideoFrameProcessingException

/**
 * Refuses the filter [name] by name on a GL context below the OpenGL ES 3 its GLSL is written for.
 */
internal fun requireGles3(
  name: String,
  contextMajorVersion: Long,
) {
  if (contextMajorVersion < MIN_GLES_MAJOR) {
    throw VideoFrameProcessingException(
      "$name needs OpenGL ES $MIN_GLES_MAJOR, and this device's context is OpenGL ES $contextMajorVersion.",
    )
  }
}

/**
 * The vertex shader a filter's full frame draw runs on, which hands its fragment shader the `vUv` varying.
 */
internal val FULL_FRAME_VERTEX_SHADER =
  """
  #version 300 es
  in vec4 aFramePosition;
  out vec2 vUv;
  void main() {
    gl_Position = aFramePosition;
    vUv = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

private const val MIN_GLES_MAJOR = 3L
