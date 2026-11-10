package dev.jordond.filmstrip.media3.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
    // media3's limit: a grade kept through a re-encode is decoded first, and media3 decodes no HDR below API 29.
    supportsHdrEncoding = codecs.anyHdrEncoder() && hdrDecodeLimit(Build.VERSION.SDK_INT) == null,
    concurrentSessionBudget = null,
  )
}

/**
 * Why media3 cannot decode an HDR source on [sdkInt], or null when it can.
 *
 * media3's `DefaultDecoderFactory` refuses an HDR decoder below API 29, so there neither a tone map nor a kept grade
 * that re-encodes has frames to work on. A copy decodes nothing and keeps the grade on any level.
 */
internal fun hdrDecodeLimit(sdkInt: Int): String? =
  if (sdkInt < Build.VERSION_CODES.Q) {
    "media3 decodes no HDR source below API 29 and this device runs API $sdkInt, so the grade can be neither tone " +
      "mapped nor re-encoded. Export a copy that keeps the grade, or run on API 29 or later."
  } else {
    null
  }

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
