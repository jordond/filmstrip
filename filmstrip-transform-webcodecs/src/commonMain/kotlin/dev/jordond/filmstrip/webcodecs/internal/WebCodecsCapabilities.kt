package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.await

/**
 * Whether the WebGL pass can hand the encoder ten-bit samples.
 *
 * It renders into an 8-bit canvas and reads back through `VideoFrame(canvas)`, which is always
 * BGRA, so it cannot. Flip this with the compositor, never on its own: it is what keeps an SDR
 * frame from being written into a file tagged BT.2020 PQ, and it is also what keeps every effect
 * with a lowering of its own for a kept grade off a path that has none here.
 */
internal const val COMPOSITOR_WRITES_TEN_BIT: Boolean = false

/**
 * What the browser's encoders can do, on WebCodecs.
 *
 * `capabilities()` is real: `VideoEncoder.isConfigSupported` answers the same ladder question the
 * Apple driver asks VideoToolbox. Encoding lives in [BrowserExportEngine], which owns the whole
 * pipeline.
 */
internal class WebCodecsCapabilities {
  private var cached: DeviceCapabilities? = null

  suspend fun capabilities(): DeviceCapabilities = cached ?: probeCapabilities().also { cached = it }

  private suspend fun probeCapabilities(): DeviceCapabilities {
    if (!hasVideoEncoder()) {
      return DeviceCapabilities(
        video = emptyList(),
        audio = emptyList(),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      )
    }

    val video = SUPPORTED_CODECS.mapNotNull { codec -> probeVideo(codec) }
    val audio =
      if (hasAudioEncoder() && probeAudio()) {
        listOf(
          AudioEncoderCapability(
            codec = AudioCodec.Aac,
            sampleRates = SUPPORTED_SAMPLE_RATES,
            maxChannelCount = MAX_CHANNELS,
          ),
        )
      } else {
        emptyList()
      }

    return DeviceCapabilities(
      video = video,
      audio = audio,
      // The config carries no HDR transfer function of its own, but the frame does and the
      // container tags it, so VP9 Profile 2 support is what decides whether the encoder could take
      // a grade. It is gated on the compositor being able to hand it one: the WebGL pass reads back
      // through an 8-bit canvas, so claiming HDR here would tag an SDR file as HDR.
      supportsHdrEncoding = COMPOSITOR_WRITES_TEN_BIT && supportsVideo(HDR_VP9_CODEC, RESOLUTION_LADDER.last()),
      concurrentSessionBudget = null,
    )
  }

  private suspend fun probeVideo(codec: VideoCodec): VideoEncoderCapability? {
    val largest =
      RESOLUTION_LADDER.firstOrNull { size ->
        supportsVideo(webCodecString(codec, size), size)
      }
    return largest?.let {
      VideoEncoderCapability(
        codec = codec,
        // A browser takes a codec string and picks the encoder behind it without saying which.
        encoderName = null,
        maxSize = it,
        // A browser publishes neither a rate ceiling nor a bitrate ceiling, exactly as Apple does.
        maxFrameRate = null,
        maxBitrate = null,
        // The config isConfigSupported resolves only ever echoes the hardwareAcceleration that
        // was requested, or the "no-preference" default when none was, never what the browser
        // actually granted. There is no config that reads this back, so it is left unknown.
        isHardwareAccelerated = null,
        sizeAlignment = SIZE_ALIGNMENT,
      )
    }
  }

  private suspend fun supportsVideo(
    codec: String,
    size: Size,
  ): Boolean =
    runCatching {
      VideoEncoder
        .isConfigSupported(
          JsOptions()
            .put("codec", codec)
            .put("width", size.width)
            .put("height", size.height)
            .put("bitrate", PROBE_BITRATE)
            .put("framerate", PROBE_FRAME_RATE)
            .build(),
        ).await()
        .supported
    }.getOrDefault(false)

  private suspend fun probeAudio(): Boolean =
    runCatching {
      AudioEncoder
        .isConfigSupported(
          JsOptions()
            .put("codec", AAC_CODEC)
            .put("sampleRate", PROBE_SAMPLE_RATE)
            .put("numberOfChannels", MAX_CHANNELS)
            .put("bitrate", PROBE_AUDIO_BITRATE)
            .build(),
        ).await()
        .supported
    }.getOrDefault(false)

  private companion object {
    // Probed largest first, since the probe stops at the first size that succeeds.
    val RESOLUTION_LADDER =
      listOf(
        Size(3840, 2160),
        Size(1920, 1080),
        Size(1280, 720),
        Size(640, 480),
      )

    // Every browser encoder config is even-dimensioned, and odd sizes are rejected outright.
    const val SIZE_ALIGNMENT = 2

    const val MAX_CHANNELS = 2

    val SUPPORTED_SAMPLE_RATES = listOf(44_100, 48_000)

    const val AAC_CODEC = "mp4a.40.2"

    const val PROBE_BITRATE = 8_000_000
    const val PROBE_FRAME_RATE = 30
    const val PROBE_SAMPLE_RATE = 48_000
    const val PROBE_AUDIO_BITRATE = 128_000

    val SUPPORTED_CODECS = listOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9)
  }
}
