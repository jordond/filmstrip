package dev.jordond.filmstrip.test

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * True when this device has a decoder that advertises a ten-bit HEVC profile, which is what reading an HDR clip
 * needs. It asks for the profile rather than the codec, since the emulator's `OMX.google.hevc.decoder` is an HEVC
 * decoder that only reads eight-bit streams.
 */
@InternalFilmstripApi
public fun decodesTenBitHevc(): Boolean =
  MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
    !codec.isEncoder &&
      codec.supportedTypes.any { it.equals(HEVC_MIME, ignoreCase = true) } &&
      codec.getCapabilitiesForType(HEVC_MIME).profileLevels.any { it.profile in TEN_BIT_HEVC_PROFILES }
  }

private const val HEVC_MIME = "video/hevc"

private val TEN_BIT_HEVC_PROFILES =
  setOf(
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
  )
