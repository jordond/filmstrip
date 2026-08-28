package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.trackCodecOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.estimatedDataRate
import platform.AVFoundation.exportable
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.minFrameDuration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreFoundation.CFDictionaryGetTypeID
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMFormatDescriptionGetTypeID
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.kCMFormatDescriptionExtension_BitsPerComponent
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_2020
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferColorPrimaries_SMPTE_C
import platform.CoreVideo.kCVImageBufferPixelAspectRatioHorizontalSpacingKey
import platform.CoreVideo.kCVImageBufferPixelAspectRatioKey
import platform.CoreVideo.kCVImageBufferPixelAspectRatioVerticalSpacingKey
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_2100_HLG
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_2020
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_601_4
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.darwin.NSObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

// Reads metadata from AVURLAsset track properties, which are read-only AVFoundation symbols and so
// stay inside core's layering row.
@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformProber actual constructor() {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    withContext(Dispatchers.Default) {
      val url =
        when (source) {
          is MediaSource.Path -> {
            NSURL.fileURLWithPath(source.path)
          }
          is MediaSource.Uri -> {
            NSURL.URLWithString(source.uri)
          }
          is MediaSource.Bytes -> {
            return@withContext ProbeResult.Failure(
              ExportError.SourceUnreadable(
                source = "bytes",
                message =
                  "In-memory sources are written to a temporary file before probing, " +
                    "which is not implemented yet.",
              ),
            )
          }
        } ?: return@withContext ProbeResult.Failure(
          ExportError.SourceUnreadable(source.describe(), "The source is not a valid URL."),
        )

      val asset = AVURLAsset(uRL = url, options = null)
      val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
      val audioTrack = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack

      if (videoTrack == null && audioTrack == null) {
        return@withContext ProbeResult.Failure(
          ExportError.SourceUnreadable(
            source.describe(),
            "The asset has no readable video or audio track.",
          ),
        )
      }

      ProbeResult.Success(
        MediaInfo(
          duration = CMTimeGetSeconds(asset.duration).seconds,
          video = videoTrack?.toInfo(),
          audio =
            audioTrack?.let {
              AudioTrackInfo(
                codec = it.readFormat().codec,
                sampleRate = it.streamDescription { description -> description.mSampleRate.roundToInt() } ?: 0,
                channelCount = it.streamDescription { description -> description.mChannelsPerFrame.toInt() } ?: 0,
                bitrate =
                  it.estimatedDataRate
                    .takeIf { rate -> rate > 0f }
                    ?.let { rate -> Bitrate(rate.toLong()) },
              )
            },
          // AVAsset runs a DRM check while it loads, so protection is known before any encode.
          isExportable = asset.exportable,
        ),
      )
    }

  private fun AVAssetTrack.toInfo(): VideoTrackInfo {
    val rotation = rotationDegrees()
    val coded =
      naturalSize.useContents {
        Size(width = width.roundToInt(), height = height.roundToInt())
      }

    val format = readFormat()

    return VideoTrackInfo(
      codedSize = coded,
      displaySize = displaySizeOf(coded, rotation, format.pixelAspectRatio),
      rotationDegrees = rotation,
      pixelAspectRatio = format.pixelAspectRatio,
      frameRate = frameRate(),
      codec = format.codec,
      bitDepth = format.bitDepth,
      colorSpace = format.colorSpace,
      hdrTransfer = format.hdrTransfer,
      bitrate = estimatedDataRate.takeIf { it > 0f }?.let { Bitrate(it.toLong()) },
    )
  }

  // nominalFrameRate is zero for some assets, not absent, so fall back to the minimum frame
  // duration before giving up.
  private fun AVAssetTrack.frameRate(): Float? {
    val nominal = nominalFrameRate
    if (nominal > 0f) return nominal
    val seconds = CMTimeGetSeconds(minFrameDuration)
    return if (seconds > 0.0) (1.0 / seconds).toFloat() else null
  }

  // The preferred transform is the only place Apple records orientation, and the flag that applies
  // it is ignored once a video composition is set, so the rotation has to be baked into the pixels.
  private fun AVAssetTrack.rotationDegrees(): Int =
    preferredTransform.useContents {
      val degrees = atan2(b, a) * DEGREES_PER_RADIAN
      val normalised = ((degrees.roundToInt() % FULL_TURN) + FULL_TURN) % FULL_TURN
      QUARTER_TURNS.minByOrNull { abs(it - normalised) } ?: 0
    }

  /**
   * What a track's format description says about its codec and its colour.
   *
   * @property codec What the track is encoded with.
   * @property bitDepth Bits per colour component, or null when the description does not say.
   * @property colorSpace The colour space the track is tagged with.
   * @property hdrTransfer The HDR transfer function, or null for SDR and for untagged media.
   */
  private class TrackFormat(
    val codec: TrackCodec,
    val bitDepth: Int?,
    val colorSpace: ColorSpace,
    val hdrTransfer: HdrTransfer?,
    val pixelAspectRatio: Float,
  )

  /**
   * Reads the codec and colour of this track.
   *
   * One format description answers all four, so it is fetched once and released once, not per
   * field. A track without one reads as entirely unknown, which is not a failure. There is just
   * less to report about a source AVFoundation will not describe.
   */
  private fun AVAssetTrack.readFormat(): TrackFormat {
    val format = copyFormatDescription() ?: return UNKNOWN_FORMAT

    try {
      return TrackFormat(
        codec = format.codec(),
        bitDepth = format.bitsPerComponent(),
        colorSpace = format.colorSpace(),
        hdrTransfer = format.hdrTransfer(),
        pixelAspectRatio = format.pixelAspectRatio(),
      )
    } finally {
      CFRelease(format)
    }
  }

  /**
   * This track's format description, retained, or null when it carries none.
   *
   * `formatDescriptions` hands back its elements as Objective-C objects, so casting one straight to
   * [CMFormatDescriptionRef] yields null however it looks. Going back across the toll-free bridge
   * is the only way to recover the pointer, and it hands over a reference the caller has to
   * release.
   */
  @OptIn(BetaInteropApi::class)
  private fun AVAssetTrack.copyFormatDescription(): CMFormatDescriptionRef? {
    val bridged = CFBridgingRetain(formatDescriptions.firstOrNull() as? NSObject) ?: return null
    if (CFGetTypeID(bridged) != CMFormatDescriptionGetTypeID()) {
      CFRelease(bridged)
      return null
    }

    return bridged.reinterpret()
  }

  /**
   * The codec's four-character code, which is its four bytes packed big-endian into a word.
   */
  private fun CMFormatDescriptionRef.codec(): TrackCodec {
    val code = CMFormatDescriptionGetMediaSubType(this)
    val name =
      buildString {
        for (shift in FOUR_CC_SHIFTS) {
          append(((code shr shift) and BYTE_MASK).toInt().toChar())
        }
      }

    // Padded to four characters with spaces, which 'aac ' is the one that turns up.
    return trackCodecOf(name.trim())
  }

  /**
   * Bits per colour component, or null when the description does not carry it.
   *
   * CoreMedia fills this in from the HEVC configuration record and has no equivalent for H.264, so
   * an HEVC track answers and an H.264 one never does.
   */
  private fun CMFormatDescriptionRef.bitsPerComponent(): Int? {
    val value = CMFormatDescriptionGetExtension(this, kCMFormatDescriptionExtension_BitsPerComponent) ?: return null
    if (CFGetTypeID(value) != CFNumberGetTypeID()) return null

    return memScoped {
      val bits = alloc<IntVar>()
      if (CFNumberGetValue(value.reinterpret(), kCFNumberIntType, bits.ptr)) bits.value else null
    }
  }

  /**
   * The colour space the track is tagged with, or [ColorSpace.Unknown] when it carries no tag.
   */
  private fun CMFormatDescriptionRef.colorSpace(): ColorSpace {
    val matrix = CMFormatDescriptionGetExtension(this, kCVImageBufferYCbCrMatrixKey)
    if (matrix != null) {
      return when {
        matrix.matches(kCVImageBufferYCbCrMatrix_ITU_R_709_2) -> ColorSpace.Bt709
        matrix.matches(kCVImageBufferYCbCrMatrix_ITU_R_601_4) -> ColorSpace.Bt601
        matrix.matches(kCVImageBufferYCbCrMatrix_ITU_R_2020) -> ColorSpace.Bt2020
        else -> ColorSpace.Unknown
      }
    }

    // Encoders that leave the matrix unspecified sometimes still write primaries, so they are worth
    // a second look. They are never consulted when a matrix is present, since the two can disagree
    // and the matrix is what a YCbCr conversion actually uses and what the other backends report.
    val primaries = CMFormatDescriptionGetExtension(this, kCVImageBufferColorPrimariesKey)
    return when {
      primaries.matches(kCVImageBufferColorPrimaries_ITU_R_709_2) -> ColorSpace.Bt709
      primaries.matches(kCVImageBufferColorPrimaries_SMPTE_C) -> ColorSpace.Bt601
      primaries.matches(kCVImageBufferColorPrimaries_ITU_R_2020) -> ColorSpace.Bt2020
      else -> ColorSpace.Unknown
    }
  }

  private fun CMFormatDescriptionRef.hdrTransfer(): HdrTransfer? {
    val transfer = CMFormatDescriptionGetExtension(this, kCVImageBufferTransferFunctionKey)
    return when {
      transfer.matches(kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ) -> HdrTransfer.Pq
      transfer.matches(kCVImageBufferTransferFunction_ITU_R_2100_HLG) -> HdrTransfer.Hlg
      else -> null
    }
  }

  /**
   * Whether this extension value is the given CoreVideo constant.
   *
   * The constants and the values CoreMedia hands back are separate objects with the same contents,
   * so comparing the pointers is always false. Comparing the strings is the comparison that holds,
   * and `CFEqual` cannot be reached from Kotlin because cinterop marks it unimportable.
   */
  private fun COpaquePointer?.matches(constant: CFStringRef?): Boolean {
    if (this == null || constant == null) return false
    if (CFGetTypeID(this) != CFStringGetTypeID()) return false

    return CFStringCompare(reinterpret(), constant, 0uL) == kCFCompareEqualTo
  }

  /**
   * Width of a stored pixel over its height, or `1f` when the track does not carry the ratio.
   */
  private fun CMFormatDescriptionRef.pixelAspectRatio(): Float {
    val ratio = CMFormatDescriptionGetExtension(this, kCVImageBufferPixelAspectRatioKey) ?: return SQUARE
    if (CFGetTypeID(ratio) != CFDictionaryGetTypeID()) return SQUARE

    val dictionary: CFDictionaryRef = ratio.reinterpret()
    val horizontal = dictionary.int(kCVImageBufferPixelAspectRatioHorizontalSpacingKey) ?: return SQUARE
    val vertical = dictionary.int(kCVImageBufferPixelAspectRatioVerticalSpacingKey)?.takeIf { it != 0 } ?: return SQUARE

    return horizontal.toFloat() / vertical
  }

  private fun CFDictionaryRef.int(key: CFStringRef?): Int? {
    val value = CFDictionaryGetValue(this, key) ?: return null
    if (CFGetTypeID(value) != CFNumberGetTypeID()) return null

    return memScoped {
      val number = alloc<IntVar>()
      if (CFNumberGetValue(value.reinterpret(), kCFNumberIntType, number.ptr)) number.value else null
    }
  }

  /**
   * Reads [field] off this audio track's stream description, or null when it carries none.
   *
   * The description belongs to the format description and is handed over unretained, so it is only
   * valid inside the block.
   */
  private fun <T> AVAssetTrack.streamDescription(field: (AudioStreamBasicDescription) -> T): T? {
    val format = copyFormatDescription() ?: return null

    return try {
      CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointed?.let(field)
    } finally {
      CFRelease(format)
    }
  }

  private companion object {
    val UNKNOWN_FORMAT =
      TrackFormat(
        codec = trackCodecOf(""),
        bitDepth = null,
        colorSpace = ColorSpace.Unknown,
        hdrTransfer = null,
        pixelAspectRatio = SQUARE,
      )

    const val SQUARE = 1f
    val FOUR_CC_SHIFTS = intArrayOf(24, 16, 8, 0)
    const val BYTE_MASK = 0xFFu

    const val DEFAULT_BIT_DEPTH = 8
    const val FULL_TURN = 360
    const val DEGREES_PER_RADIAN = 180.0 / kotlin.math.PI
    val QUARTER_TURNS = listOf(0, 90, 180, 270, 360)
  }
}
