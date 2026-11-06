package dev.jordond.filmstrip.media3

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The first frame of a video the way Android plays it, decoded to a [Surface] and drawn through an external sampler,
 * so the OS picks the matrix.
 */
internal class SampledFrame(
  private val width: Int,
  private val height: Int,
  private val rgba: ByteBuffer,
) {
  /**
   * The red, green and blue codes averaged over a small patch centred on the fractions [fx] and [fy] of the frame.
   */
  fun rgbAt(
    fx: Float,
    fy: Float,
  ): IntArray {
    val cx = (fx * width).roundToInt()
    val cy = (fy * height).roundToInt()
    val sums = LongArray(RGB_CHANNELS)
    for (y in cy - SAMPLED_PATCH / 2 until cy + SAMPLED_PATCH / 2) {
      for (x in cx - SAMPLED_PATCH / 2 until cx + SAMPLED_PATCH / 2) {
        sums.indices.forEach { sums[it] += rgba.get((y * width + x) * RGBA_BYTES + it).toLong() and BYTE_MASK }
      }
    }
    return IntArray(RGB_CHANNELS) { (sums[it].toFloat() / (SAMPLED_PATCH * SAMPLED_PATCH)).roundToInt() }
  }
}

/**
 * Draws the first frame of [video] through `samplerExternalOES`, the sampler a player and media3 both read through.
 */
internal fun sampledFirstFrame(video: File): SampledFrame {
  val extractor = MediaExtractor()
  extractor.setDataSource(video.path)
  val track =
    (0 until extractor.trackCount).first {
      extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    }
  extractor.selectTrack(track)
  val format = extractor.getTrackFormat(track)
  val width = format.getInteger(MediaFormat.KEY_WIDTH)
  val height = format.getInteger(MediaFormat.KEY_HEIGHT)

  val gl = PbufferGl(width, height)
  val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
  val thread = HandlerThread("sampled-frame").apply { start() }
  val available = CountDownLatch(1)
  val surfaceTexture = SurfaceTexture(texture)
  surfaceTexture.setOnFrameAvailableListener({ available.countDown() }, Handler(thread.looper))
  val surface = Surface(surfaceTexture)
  val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
  try {
    codec.configure(format, surface, null, 0)
    codec.start()
    codec.renderFirstFrame(extractor)
    check(available.await(FRAME_WAIT_SECONDS, TimeUnit.SECONDS)) { "no frame reached the sampler from $video" }
    surfaceTexture.updateTexImage()
    val transform = FloatArray(MATRIX_SIZE).also(surfaceTexture::getTransformMatrix)
    return SampledFrame(width, height, gl.drawExternal(texture, transform))
  } finally {
    runCatching { codec.stop() }
    codec.release()
    extractor.release()
    surface.release()
    surfaceTexture.release()
    thread.quitSafely()
    gl.release()
  }
}

private fun MediaCodec.renderFirstFrame(extractor: MediaExtractor) {
  val info = MediaCodec.BufferInfo()
  var inputDone = false
  val deadline = System.nanoTime() + DECODE_BUDGET_NS
  while (System.nanoTime() < deadline) {
    if (!inputDone) {
      val input = dequeueInputBuffer(WAIT_US)
      if (input >= 0) {
        val size = extractor.readSampleData(checkNotNull(getInputBuffer(input)), 0)
        if (size < 0) {
          queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          inputDone = true
        } else {
          queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
          extractor.advance()
        }
      }
    }
    val output = dequeueOutputBuffer(info, WAIT_US)
    if (output < 0) continue
    val hasFrame = info.size > 0
    releaseOutputBuffer(output, hasFrame)
    if (hasFrame) return
  }
  error("no frame decoded in time")
}

/**
 * An OpenGL ES context current on the calling thread, over an RGBA pbuffer of [width] by [height].
 */
private class PbufferGl(
  private val width: Int,
  private val height: Int,
) {
  private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  private val context: android.opengl.EGLContext
  private val surface: android.opengl.EGLSurface

  init {
    check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "no EGL display" }
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    EGL14.eglChooseConfig(display, CONFIG_ATTRIBUTES, 0, configs, 0, 1, count, 0)
    val config = checkNotNull(configs[0].takeIf { count[0] > 0 }) { "no pbuffer EGL config" }
    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, CONTEXT_ATTRIBUTES, 0)
    val attributes = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
    surface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
    check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "the sampled frame's context did not go current" }
  }

  fun drawExternal(
    texture: Int,
    transform: FloatArray,
  ): ByteBuffer {
    val program = program()
    GLES20.glUseProgram(program)
    GLES20.glViewport(0, 0, width, height)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
    val position = GLES20.glGetAttribLocation(program, "aPosition")
    val quad =
      ByteBuffer
        .allocateDirect(QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD)
    quad.position(0)
    GLES20.glEnableVertexAttribArray(position)
    GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD.size / 2)
    val pixels = ByteBuffer.allocateDirect(width * height * RGBA_BYTES)
    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
    check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "the sampled frame did not draw" }
    return pixels
  }

  fun release() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(display, surface)
    EGL14.eglDestroyContext(display, context)
    // The default display is shared with media3 in this process, so it is never terminated here.
    EGL14.eglReleaseThread()
  }

  private fun program(): Int {
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
    GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
    GLES20.glLinkProgram(program)
    return program
  }

  private fun shader(
    kind: Int,
    source: String,
  ): Int =
    GLES20.glCreateShader(kind).also {
      GLES20.glShaderSource(it, source)
      GLES20.glCompileShader(it)
    }
}

private val CONFIG_ATTRIBUTES =
  intArrayOf(
    EGL14.EGL_RENDERABLE_TYPE,
    EGL14.EGL_OPENGL_ES2_BIT,
    EGL14.EGL_SURFACE_TYPE,
    EGL14.EGL_PBUFFER_BIT,
    EGL14.EGL_RED_SIZE,
    8,
    EGL14.EGL_GREEN_SIZE,
    8,
    EGL14.EGL_BLUE_SIZE,
    8,
    EGL14.EGL_ALPHA_SIZE,
    8,
    EGL14.EGL_NONE,
  )
private val CONTEXT_ATTRIBUTES = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

private const val VERTEX =
  "attribute vec2 aPosition;\n" +
    "uniform mat4 uTransform;\n" +
    "varying vec2 vTexCoord;\n" +
    "void main() {\n" +
    "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
    "  vTexCoord = (uTransform * vec4(aPosition * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
    "}\n"
private const val FRAGMENT =
  "#extension GL_OES_EGL_image_external : require\n" +
    "precision mediump float;\n" +
    "uniform samplerExternalOES uTexture;\n" +
    "varying vec2 vTexCoord;\n" +
    "void main() {\n" +
    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
    "}\n"

private const val SAMPLED_PATCH = 16
private const val RGB_CHANNELS = 3
private const val RGBA_BYTES = 4
private const val BYTE_MASK = 0xFFL
private const val MATRIX_SIZE = 16
private const val FRAME_WAIT_SECONDS = 10L
private const val WAIT_US = 10_000L
private const val DECODE_BUDGET_NS = 10_000_000_000L
