package dev.jordond.filmstrip.media3.internal.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.view.Surface
import androidx.media3.common.util.MediaFormatUtil

/**
 * Measures the color the encoder [request] names writes by encoding one green frame through it and reading the codes
 * back, giving up at [deadline] in [System.nanoTime] terms or once the thread is interrupted.
 *
 * The encoder is configured at the largest size its capabilities list within the width and height of the request's
 * [SizeTier], or answers [WrittenColor.Unclear] when none of them falls in that tier. The frame is drawn as RGB onto
 * the encoder's input surface through OpenGL ES, the way media3 draws. The first coded frame is decoded to YCbCr and
 * its middle sample is named by [nearestColor], with the transfer the request asked for. Every codec wait polls in
 * steps of [DEQUEUE_US] and checks the deadline and the interrupt between them. The encoder, decoder, surface and EGL
 * context are released before this returns or throws.
 *
 * @param portrait Whether the frame is that size turned on its side, taller than it is wide.
 */
internal fun measureOnDevice(
  request: EncoderColorRequest,
  deadline: Long,
  portrait: Boolean = false,
): WrittenColor {
  val encoder = MediaCodec.createByCodecName(request.encoder)
  try {
    val noSize = WrittenColor.Unclear("it encodes no ${request.tier} size")
    val sizes = encoder.codecInfo.getCapabilitiesForType(request.mimeType).videoCapabilities ?: return noSize
    val (longer, shorter) = sizes.largestWithin(request.tier) ?: return noSize
    val (width, height) = if (portrait) shorter to longer else longer to shorter
    val noPortraitSize = WrittenColor.Unclear("it encodes no portrait ${request.tier} size")
    if (!sizes.isSizeSupported(width, height)) return noPortraitSize
    val format =
      MediaFormat.createVideoFormat(request.mimeType, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      }
    MediaFormatUtil.maybeSetColorInfo(format, request.requested)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = encoder.createInputSurface()
    try {
      encoder.start()
      drawGreen(surface, width, height)
      encoder.signalEndOfInputStream()
      val (outputFormat, frame) =
        firstFrame(encoder, deadline)
          ?: return WrittenColor.Unclear.outOfTime("it gave no coded frame before the deadline")
      val codes =
        decodedMiddle(request.mimeType, outputFormat, frame, deadline)
          ?: return WrittenColor.Unclear.outOfTime("its frame did not decode before the deadline")
      val color =
        nearestColor(codes, request.requested)
          ?: return WrittenColor.Unclear("it wrote green as $codes, near none of BT.601 and BT.709")
      return WrittenColor.Measured(color)
    } finally {
      surface.release()
    }
  } finally {
    encoder.release()
  }
}

// The tier's width and height, each cut to what this encoder takes and aligned down, when that size falls in the tier.
private fun MediaCodecInfo.VideoCapabilities.largestWithin(tier: SizeTier): Pair<Int, Int>? {
  val width = alignedDown(minOf(tier.width, supportedWidths.upper), widthAlignment)
  val heights =
    try {
      getSupportedHeightsFor(width)
    } catch (_: IllegalArgumentException) {
      return null
    }
  val height = alignedDown(minOf(tier.height, heights.upper), heightAlignment)
  return (width to height).takeIf { isSizeSupported(width, height) && SizeTier.of(width, height) == tier }
}

private fun alignedDown(
  value: Int,
  alignment: Int,
): Int = value - value % alignment

private fun drawGreen(
  surface: Surface,
  width: Int,
  height: Int,
) {
  val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "no EGL display" }
  val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
  val count = IntArray(1)
  EGL14.eglChooseConfig(display, CONFIG_ATTRIBUTES, 0, configs, 0, 1, count, 0)
  val config = checkNotNull(configs[0].takeIf { count[0] > 0 }) { "no recordable EGL config" }
  val context =
    EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, CONTEXT_ATTRIBUTES, 0)
  val window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
  try {
    check(EGL14.eglMakeCurrent(display, window, window, context)) { "the probe's EGL context did not go current" }
    GLES20.glViewport(0, 0, width, height)
    repeat(FRAMES) { frame ->
      GLES20.glClearColor(0f, 1f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      EGLExt.eglPresentationTimeANDROID(display, window, frame * FRAME_NANOS)
      EGL14.eglSwapBuffers(display, window)
    }
  } finally {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(display, window)
    EGL14.eglDestroyContext(display, context)
    // The default display is shared with media3 in this process, so it is never terminated here.
    EGL14.eglReleaseThread()
  }
}

private fun waiting(deadline: Long): Boolean = System.nanoTime() < deadline && !Thread.currentThread().isInterrupted

private fun firstFrame(
  encoder: MediaCodec,
  deadline: Long,
): Pair<MediaFormat, ByteArray>? {
  val info = MediaCodec.BufferInfo()
  var outputFormat: MediaFormat? = null
  while (waiting(deadline)) {
    val index = encoder.dequeueOutputBuffer(info, DEQUEUE_US)
    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = encoder.outputFormat
    if (index < 0) continue
    val buffer = checkNotNull(encoder.getOutputBuffer(index))
    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
    val frame =
      if (!config && info.size > 0) {
        ByteArray(info.size).also {
          buffer.position(info.offset)
          buffer.get(it)
        }
      } else {
        null
      }
    encoder.releaseOutputBuffer(index, false)
    if (frame != null) return outputFormat?.let { it to frame }
    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
  }
  return null
}

private fun decodedMiddle(
  mimeType: String,
  format: MediaFormat,
  frame: ByteArray,
  deadline: Long,
): Triple<Int, Int, Int>? {
  val decoder =
    softwareDecoder(mimeType)?.let(MediaCodec::createByCodecName) ?: MediaCodec.createDecoderByType(mimeType)
  try {
    decoder.configure(format, null, null, 0)
    decoder.start()
    val info = MediaCodec.BufferInfo()
    var queued = false
    var ended = false
    while (waiting(deadline)) {
      if (!ended) {
        val input = decoder.dequeueInputBuffer(DEQUEUE_US)
        if (input >= 0) {
          if (!queued) {
            checkNotNull(decoder.getInputBuffer(input)).put(frame)
            decoder.queueInputBuffer(input, 0, frame.size, 0, 0)
            queued = true
          } else {
            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            ended = true
          }
        }
      }
      val index = decoder.dequeueOutputBuffer(info, DEQUEUE_US)
      if (index < 0) continue
      val codes = if (info.size > 0) decoder.getOutputImage(index)?.use { it.middleCodes() } else null
      decoder.releaseOutputBuffer(index, false)
      if (codes != null) return codes
      if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
    }
    return null
  } finally {
    decoder.release()
  }
}

private fun softwareDecoder(mimeType: String): String? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
  return MediaCodecList(MediaCodecList.REGULAR_CODECS)
    .codecInfos
    .firstOrNull { !it.isEncoder && it.isSoftwareOnly && mimeType in it.supportedTypes }
    ?.name
}

private fun android.media.Image.middleCodes(): Triple<Int, Int, Int> {
  fun sample(
    plane: Int,
    x: Int,
    y: Int,
  ): Int {
    val row = planes[plane]
    return row.buffer.get(y * row.rowStride + x * row.pixelStride).toInt() and BYTE_MASK
  }
  val x = width / 2
  val y = height / 2
  return Triple(sample(0, x, y), sample(1, x / 2, y / 2), sample(2, x / 2, y / 2))
}

private const val DEQUEUE_US = 10_000L
private const val FRAMES = 2
private const val FRAME_RATE = 30
private const val FRAME_NANOS = 1_000_000_000L / FRAME_RATE
private const val BIT_RATE = 2_000_000
private const val BYTE_MASK = 0xFF
private const val EGL_RECORDABLE_ANDROID = 0x3142

private val CONFIG_ATTRIBUTES =
  intArrayOf(
    EGL14.EGL_RED_SIZE,
    8,
    EGL14.EGL_GREEN_SIZE,
    8,
    EGL14.EGL_BLUE_SIZE,
    8,
    EGL14.EGL_ALPHA_SIZE,
    8,
    EGL14.EGL_RENDERABLE_TYPE,
    EGL14.EGL_OPENGL_ES2_BIT,
    EGL_RECORDABLE_ANDROID,
    1,
    EGL14.EGL_NONE,
  )

private val CONTEXT_ATTRIBUTES = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
