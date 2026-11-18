package dev.jordond.filmstrip.media3.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import androidx.media3.common.util.GlUtil
import androidx.media3.transformer.EncoderUtil
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import kotlin.concurrent.thread

/**
 * Reads what this device's encoders publish.
 *
 * `REGULAR_CODECS` rather than `ALL_CODECS`, so what comes back is what an app is allowed to use.
 */
internal fun encoderCapabilities(): DeviceCapabilities {
  val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

  return DeviceCapabilities(
    video = VIDEO_MIME_TYPES.flatMap { (codec, mime) -> codecs.videoCapabilities(codec, mime) },
    audio = AUDIO_MIME_TYPES.mapNotNull { (codec, mime) -> codecs.audioCapability(codec, mime) },
    supportsHdrEncoding = codecs.anyHdrEncoder(),
    concurrentSessionBudget = null,
  )
}

/**
 * [capabilities] as a grade kept through a re-encode can have them on this device.
 *
 * media3 decodes a source before it re-encodes it, and draws no HDR it cannot decode, so a device that cannot draw one
 * cannot keep its grade through an encoder either. See [hdrDrawLimit].
 *
 * @param yuvTarget Whether this device's OpenGL ES offers `EXT_YUV_target`, or null where that is not known.
 */
internal fun DeviceCapabilities.keepingGradeOnly(
  sdkInt: Int,
  yuvTarget: () -> Boolean?,
): DeviceCapabilities =
  DeviceCapabilities(
    video = video,
    audio = audio,
    supportsHdrEncoding = supportsHdrEncoding && hdrDrawLimit(sdkInt, yuvTarget) == null,
    concurrentSessionBudget = concurrentSessionBudget,
  )

/**
 * Why media3 cannot draw an HDR source on [sdkInt], or null when it can or when that is not known.
 *
 * media3's `DefaultDecoderFactory` refuses an HDR decoder below API 29, so there neither a tone map nor a kept grade
 * that re-encodes has frames to work on. Its frame processor also draws HDR input only through the `EXT_YUV_target`
 * extension of OpenGL ES 3, which [yuvTarget] reports. A copy decodes nothing and keeps the grade on any level.
 *
 * @param yuvTarget Whether this device's OpenGL ES offers `EXT_YUV_target`, asked of the device unless given. Null
 * where the device could not be asked, which refuses nothing: a refusal on a guess would stop an HDR edit a device may
 * draw.
 */
internal fun hdrDrawLimit(
  sdkInt: Int,
  yuvTarget: () -> Boolean? = ::deviceDrawsYuvTarget,
): String? =
  when {
    sdkInt < Build.VERSION_CODES.Q -> {
      "media3 decodes no HDR source below API 29 and this device runs API $sdkInt, so the grade can be neither tone " +
        "mapped nor re-encoded. Export a copy that keeps the grade, or run on API 29 or later."
    }
    yuvTarget() == false -> {
      "media3 draws an HDR source through the EXT_YUV_target extension of OpenGL ES 3 and this device offers no such " +
        "extension, so the grade can be neither tone mapped nor re-encoded. Export a copy that keeps the grade."
    }
    else -> {
      null
    }
  }

private val deviceYuvTarget = YuvTargetProbe(probe = ::probeYuvTarget)

/**
 * Whether this device's OpenGL ES 3 offers `EXT_YUV_target`, or null where the device could not be asked.
 *
 * See [YuvTargetProbe] for how long an answer is kept.
 */
internal fun deviceDrawsYuvTarget(): Boolean? = deviceYuvTarget.ask()

/**
 * Asks a device whether it offers `EXT_YUV_target` and keeps the answer once it is a yes or a no.
 *
 * A [probe] that could not ask answers null, which is not kept, so the next call asks again. After [attempts] asks
 * with no answer the device is not asked any more and every call answers null.
 *
 * @param attempts How many times the device is asked before an unknown stands.
 * @param probe Asks the device, answering null where it could not be asked.
 */
internal class YuvTargetProbe(
  private val attempts: Int = PROBE_ATTEMPTS,
  private val probe: () -> Boolean?,
) {
  private var answer: Boolean? = null
  private var asked = 0

  @Synchronized
  fun ask(): Boolean? {
    answer?.let { return it }
    if (asked >= attempts) return null
    asked++
    return probe().also { answer = it }
  }
}

// The extension is only readable with a context current, and the context media3 draws HDR on is an OpenGL ES 3 one, so
// the probe makes one of that kind. media3 keeps a grade on a ten-bit config and tone maps on an eight-bit one, so a
// device with no ten-bit config is asked on the eight-bit one. It runs on a thread of its own, so it neither reads nor
// unbinds a context the caller has current.
private fun probeYuvTarget(): Boolean? {
  var answer: Boolean? = null
  val probe =
    thread(name = PROBE_THREAD) {
      answer =
        PROBE_CONFIGS.firstNotNullOfOrNull { config ->
          try {
            val display = GlUtil.getDefaultEglDisplay()
            val context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, OPEN_GL_ES_3, config)
            try {
              GlUtil.createFocusedPlaceholderEglSurface(context, display)
              GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains(YUV_TARGET_EXTENSION)
            } finally {
              GlUtil.destroyEglContext(display, context)
            }
          } catch (unavailable: Exception) {
            // Whatever stopped this config, the device was not asked on it, which is unknown and not a refusal.
            null
          }
        }
    }
  probe.join()
  return answer
}

private val PROBE_CONFIGS = listOf(GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)

private const val PROBE_THREAD = "filmstrip-yuv-target-probe"
private const val PROBE_ATTEMPTS = 3
private const val OPEN_GL_ES_3 = 3
private const val YUV_TARGET_EXTENSION = "GL_EXT_YUV_target"

/**
 * What media3's effect pipeline can draw for an offline export.
 *
 * @param maxTextureSide The most pixels a texture holds on a side, [media3MaxTextureSide] by default.
 */
internal fun media3RenderCapabilities(
  hdr: Boolean,
  maxTextureSide: Int = media3MaxTextureSide(),
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.OpenGlEs,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    // True only once the grade reaches the encoder, which is what [hdr] answers.
    supportsHdr = hdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxTextureSide,
    features =
      buildSet {
        add(RenderFeature.MultipassRender)
        add(RenderFeature.ExternalTexture)
        add(RenderFeature.TextRendering)
        // Transformer tone-maps through its own OpenGL pipeline, so this does not depend on the
        // encoder. It is not [hdr], which is true when a grade is being kept and so when nothing
        // is being tone-mapped at all.
        add(RenderFeature.HdrToneMapping)
      },
  )

private fun Array<MediaCodecInfo>.videoCapabilities(
  codec: VideoCodec,
  mime: String,
): List<VideoEncoderCapability> =
// MediaCodecList order is the device's, not a preference, and a phone lists a software encoder
// for a codec it also has silicon for. The hardware one is the faster of the two here, unlike on
  // a desktop ffmpeg build, so it leads.
  encodersFor(mime).sortedByDescending { it.isHardwareFor(mime) }.mapNotNull { info ->
    val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return@mapNotNull null

    VideoEncoderCapability(
      codec = codec,
      // MediaCodecInfo has a name, but Transformer picks the encoder from the format rather than
      // being handed one, so naming it here would name an encoder the export may not run.
      encoderName = null,
      maxSize = Size(video.supportedWidths.upper, video.supportedHeights.upper),
      maxFrameRate = video.supportedFrameRates.upper,
      maxBitrate = Bitrate(video.bitrateRange.upper.toLong()),
      isHardwareAccelerated = info.isHardwareFor(mime),
      // Encoders require aligned dimensions. Odd ones fail or produce garbage.
      sizeAlignment = maxOf(video.widthAlignment, video.heightAlignment),
    )
  }

// MediaCodecInfo.isHardwareAccelerated is API 29. media3 reads it there and judges older devices by the codec name.
private fun MediaCodecInfo.isHardwareFor(mime: String): Boolean = EncoderUtil.isHardwareAccelerated(this, mime)

private fun Array<MediaCodecInfo>.audioCapability(
  codec: AudioCodec,
  mime: String,
): AudioEncoderCapability? {
  val info = encoderFor(mime) ?: return null
  val audio = info.getCapabilitiesForType(mime).audioCapabilities ?: return null

  return AudioEncoderCapability(
    codec = codec,
    sampleRates = audio.supportedSampleRates?.toList() ?: emptyList(),
    maxChannelCount = audio.maxInputChannelCount,
  )
}

private fun Array<MediaCodecInfo>.encoderFor(mime: String): MediaCodecInfo? =
  firstOrNull { info ->
    info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
  }

private fun Array<MediaCodecInfo>.encodersFor(mime: String): List<MediaCodecInfo> =
  filter { info ->
    info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
  }

private fun Array<MediaCodecInfo>.anyHdrEncoder(): Boolean =
  any { info ->
    info.isEncoder &&
      info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
      info
        .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        .profileLevels
        .any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
  }

private val VIDEO_MIME_TYPES =
  listOf(
    VideoCodec.H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
    VideoCodec.Hevc to MediaFormat.MIMETYPE_VIDEO_HEVC,
  )

private val AUDIO_MIME_TYPES = listOf(AudioCodec.Aac to MediaFormat.MIMETYPE_AUDIO_AAC)
