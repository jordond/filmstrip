package dev.jordond.filmstrip.media3.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.VideoCodec
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
    supportsHdrEncoding = codecs.anyHdrEncoder(),
    concurrentSessionBudget = null,
  )
}

/**
 * What media3's effect pipeline can draw for an offline export.
 */
internal fun media3RenderCapabilities(
  outputSize: Size,
  hdr: Boolean,
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.OpenGlEs,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    // True only once the grade reaches the encoder, which is what [hdr] answers.
    supportsHdr = hdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxOf(outputSize.width, outputSize.height, MAX_TEXTURE_FLOOR),
    realtimeBudgetNanos = null,
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
  encodersFor(mime).sortedByDescending { it.isHardwareAccelerated }.mapNotNull { info ->
    val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return@mapNotNull null

    VideoEncoderCapability(
      codec = codec,
      // MediaCodecInfo has a name, but Transformer picks the encoder from the format rather than
      // being handed one, so naming it here would name an encoder the export may not run.
      encoderName = null,
      maxSize = Size(video.supportedWidths.upper, video.supportedHeights.upper),
      maxFrameRate = video.supportedFrameRates.upper,
      maxBitrate = Bitrate(video.bitrateRange.upper.toLong()),
      isHardwareAccelerated = info.isHardwareAccelerated,
      // Encoders require aligned dimensions. Odd ones fail or produce garbage.
      sizeAlignment = maxOf(video.widthAlignment, video.heightAlignment),
    )
  }

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

private const val MAX_TEXTURE_FLOOR = 4_096

private val VIDEO_MIME_TYPES =
  listOf(
    VideoCodec.H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
    VideoCodec.Hevc to MediaFormat.MIMETYPE_VIDEO_HEVC,
  )

private val AUDIO_MIME_TYPES = listOf(AudioCodec.Aac to MediaFormat.MIMETYPE_AUDIO_AAC)
