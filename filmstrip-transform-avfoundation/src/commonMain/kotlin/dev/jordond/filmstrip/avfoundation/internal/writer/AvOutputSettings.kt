package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleavedKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVOutputSettingsAssistant
import platform.AVFoundation.AVOutputSettingsPreset1280x720
import platform.AVFoundation.AVOutputSettingsPreset1920x1080
import platform.AVFoundation.AVOutputSettingsPreset3840x2160
import platform.AVFoundation.AVOutputSettingsPreset640x480
import platform.AVFoundation.AVOutputSettingsPreset960x540
import platform.AVFoundation.AVOutputSettingsPresetHEVC1920x1080
import platform.AVFoundation.AVOutputSettingsPresetHEVC3840x2160
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCodecTypeHEVC
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.setSourceVideoFormat
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreMedia.CMVideoFormatDescriptionRef
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange

/**
 * The settings the video writer input is opened with.
 *
 * Seeded from [AVOutputSettingsAssistant], which is where Apple publishes a sane bitrate and
 * profile level for a given frame. Everything the plan settles then overwrites the seed, so what is
 * left of it is only ever the parts filmstrip has no opinion about.
 *
 * @param sourceFormat The first clip's video format, when there is one. It lets the assistant seed
 *   against the real source, not the preset's nominal one.
 * @param encodesHdr Whether an HDR grade reaches the encoder. False for an SDR source whatever the
 *   plan asked for, since there is no grade to keep.
 * @param transfer The source's HDR transfer function, which decides HLG against PQ.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun videoWriterSettings(
  output: OutputFormat,
  encodesHdr: Boolean,
  transfer: HdrTransfer?,
  sourceFormat: CMVideoFormatDescriptionRef?,
): Map<Any?, Any?> {
  val seed = assistantSettings(output, sourceFormat)
  val compression =
    (seed[AVVideoCompressionPropertiesKey] as? Map<*, *>).orEmptyMap() +
      buildMap {
        output.bitrate?.let { put(AVVideoAverageBitRateKey, it.bitsPerSecond) }
        put(AVVideoExpectedSourceFrameRateKey, output.frameRate)
        // Ten-bit needs a profile that can carry it, and VideoToolbox's constants are CFStringRef,
        // which does not bridge into a Kotlin dictionary. The literal is the documented value of
        // kVTProfileLevel_HEVC_Main10_AutoLevel.
        if (encodesHdr) put(AVVideoProfileLevelKey, HEVC_MAIN_10)
      }

  return seed +
    mapOf(
      AVVideoCodecKey to output.videoCodec.toAvCodecType(),
      AVVideoWidthKey to output.size.width,
      AVVideoHeightKey to output.size.height,
      AVVideoCompressionPropertiesKey to compression,
      AVVideoColorPropertiesKey to colorProperties(encodesHdr, transfer),
    )
}

/**
 * The settings the audio writer input is opened with, or null when the output carries no audio.
 */
internal fun audioWriterSettings(output: OutputFormat): Map<Any?, Any?>? {
  if (output.audioCodec == AudioCodec.None) return null
  val format = output.audioFormat ?: return null

  return mapOf(
    // Auto and Alac never reach here. The planner resolves both against the device's real encoder
    // list, and AAC is the only one this platform advertises.
    AVFormatIDKey to kAudioFormatMPEG4AAC,
    AVSampleRateKey to format.sampleRate.toDouble(),
    AVNumberOfChannelsKey to format.channelCount,
    AVEncoderBitRateKey to AAC_BITRATE,
  )
}

/**
 * The settings the audio reader output is opened with.
 *
 * Uncompressed, because an [platform.AVFoundation.AVAudioMix] applies its gain to samples and there
 * are none to apply it to while the stream is still AAC.
 */
internal fun pcmReaderSettings(output: OutputFormat): Map<Any?, Any?> {
  val format = output.audioFormat

  return mapOf(
    AVFormatIDKey to kAudioFormatLinearPCM,
    AVSampleRateKey to (format?.sampleRate ?: DEFAULT_SAMPLE_RATE).toDouble(),
    AVNumberOfChannelsKey to (format?.channelCount ?: MAX_CHANNELS),
    AVLinearPCMBitDepthKey to PCM_BIT_DEPTH,
    AVLinearPCMIsFloatKey to false,
    AVLinearPCMIsBigEndianKey to false,
    AVLinearPCMIsNonInterleavedKey to false,
  )
}

/**
 * The pixel format the video reader output hands the writer.
 *
 * Ten-bit biplanar for an HDR grade, which BGRA cannot carry, and BGRA otherwise because that is
 * what Core Image renders into fastest.
 */
internal fun videoReaderSettings(encodesHdr: Boolean): Map<Any?, Any?> {
  val format = if (encodesHdr) kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange else kCVPixelFormatType_32BGRA
  return mapOf(PIXEL_FORMAT_KEY to format.toInt())
}

/**
 * The codec type string the writer names, which is always one the device advertised.
 */
internal fun VideoCodec.toAvCodecType(): String =
  when (this) {
    VideoCodec.H264 -> checkNotNull(AVVideoCodecTypeH264)
    VideoCodec.Hevc -> checkNotNull(AVVideoCodecTypeHEVC)
    // The planner resolves Auto against the device's real encoder list before a plan exists, and
    // AVFoundation's ladder never offers Vp9, Vp8 or Av1, so none of them should reach here.
    // The error is what makes a ladder change that forgets this function fail loudly.
    VideoCodec.Auto, VideoCodec.Vp9, VideoCodec.Vp8, VideoCodec.Av1 -> error("AVFoundation has no encoder for $this.")
  }

@OptIn(ExperimentalForeignApi::class)
private fun assistantSettings(
  output: OutputFormat,
  sourceFormat: CMVideoFormatDescriptionRef?,
): Map<Any?, Any?> {
  val assistant =
    AVOutputSettingsAssistant.outputSettingsAssistantWithPreset(output.preset())
      ?: return emptyMap()
  if (sourceFormat != null) assistant.setSourceVideoFormat(sourceFormat)

  return assistant.videoSettings.orEmptyMap()
}

/**
 * The preset whose frame is closest to the output's without being smaller than it.
 *
 * Only the seed depends on this. Width and height are overwritten from the plan either way, so a
 * frame that sits between two presets takes the larger one's bitrate.
 */
private fun OutputFormat.preset(): String? {
  val side = maxOf(size.width, size.height)
  val hevc = videoCodec == VideoCodec.Hevc

  return when {
    hevc && side > FULL_HD_SIDE -> AVOutputSettingsPresetHEVC3840x2160
    hevc -> AVOutputSettingsPresetHEVC1920x1080
    side > FULL_HD_SIDE -> AVOutputSettingsPreset3840x2160
    side > HD_SIDE -> AVOutputSettingsPreset1920x1080
    side > QHD_SIDE -> AVOutputSettingsPreset1280x720
    side > SD_SIDE -> AVOutputSettingsPreset960x540
    else -> AVOutputSettingsPreset640x480
  }
}

/**
 * The color target the encoder is pinned to.
 *
 * Tone mapping falls out of this. Core Image renders into the working
 * space the context was built with, and naming Rec.709 here is what makes a BT.2020 source land
 * inside it.
 */
private fun colorProperties(
  encodesHdr: Boolean,
  transfer: HdrTransfer?,
): Map<Any?, Any?> =
  if (encodesHdr) {
    mapOf(
      AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_2020,
      AVVideoTransferFunctionKey to
        if (transfer == HdrTransfer.Pq) {
          AVVideoTransferFunction_SMPTE_ST_2084_PQ
        } else {
          AVVideoTransferFunction_ITU_R_2100_HLG
        },
      AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_2020,
    )
  } else {
    mapOf(
      AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
      AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
      AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_709_2,
    )
  }

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>?.orEmptyMap(): Map<Any?, Any?> = this as? Map<Any?, Any?> ?: emptyMap()

private const val DEFAULT_SAMPLE_RATE = 44_100
private const val PCM_BIT_DEPTH = 16
private const val AAC_BITRATE = 128_000

private const val FULL_HD_SIDE = 1_920
private const val HD_SIDE = 1_280
private const val QHD_SIDE = 960
private const val SD_SIDE = 640

private const val HEVC_MAIN_10 = "HEVC_Main10_AutoLevel"
