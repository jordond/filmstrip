package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.await

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
      // a grade. It is gated on the compositor being able to build one: without a float framebuffer
      // there is nowhere to composite linear light, and claiming HDR would tag an SDR file as HDR.
      // Whether a given source's grade can be read is a separate question, asked per source.
      supportsHdrEncoding = browserCanRenderFloat() && browserEncodesHdrVp9(),
      concurrentSessionBudget = null,
    )
  }

  private suspend fun probeVideo(codec: VideoCodec): VideoEncoderCapability? {
    val largest =
      RESOLUTION_LADDER.firstOrNull { size ->
        encoderSupports(webCodecString(codec, size), size)
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
    // Every browser encoder config is even-dimensioned, and odd sizes are rejected outright.
    const val SIZE_ALIGNMENT = 2

    const val MAX_CHANNELS = 2

    val SUPPORTED_SAMPLE_RATES = listOf(44_100, 48_000)

    const val AAC_CODEC = "mp4a.40.2"

    const val PROBE_SAMPLE_RATE = 48_000
    const val PROBE_AUDIO_BITRATE = 128_000

    val SUPPORTED_CODECS = listOf(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9)
  }
}

/**
 * Whether the browser's encoder takes [codec] at [size].
 *
 * The bitrate and frame rate are the probe's own, chosen to be unremarkable, since what is being
 * asked about is the codec and the size.
 */
internal suspend fun encoderSupports(
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

/**
 * Whether this browser encodes the VP9 Profile 2 an HDR export is pinned to.
 *
 * Asked at the smallest rung, so a browser that takes the profile at all answers yes. This is half
 * of what [DeviceCapabilities.supportsHdrEncoding] rests on, and it is a function rather than a
 * figure so a test can ask the same question rather than rebuilding the config from copies of it.
 */
internal suspend fun browserEncodesHdrVp9(): Boolean = encoderSupports(HDR_VP9_CODEC, RESOLUTION_LADDER.last())

// Probed largest first, since the probe stops at the first size that succeeds.
private val RESOLUTION_LADDER =
  listOf(
    Size(3840, 2160),
    Size(1920, 1080),
    Size(1280, 720),
    Size(640, 480),
  )

private const val PROBE_BITRATE = 8_000_000
private const val PROBE_FRAME_RATE = 30
