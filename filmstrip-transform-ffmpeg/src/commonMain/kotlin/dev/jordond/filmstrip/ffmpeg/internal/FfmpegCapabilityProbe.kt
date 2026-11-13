package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FfmpegEncoder
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.HDR_PROBE_SIZE
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration

/**
 * Measures what this toolchain's encoders can write by encoding a ladder of frames, since ffmpeg publishes no figure
 * for it.
 *
 * Each rung encodes a single frame as cheaply as that encoder allows. Every codec in the table is probed, including the
 * ones CODEC_LADDER leaves out of Auto, so a caller who names one still gets an answer. One encoder's ladder does not
 * wait on another's, or a build carrying six of them would spend a second of somebody's startup. A rung still encoding
 * at its deadline fails the whole measurement as [dev.jordond.filmstrip.FilmstripError.TimedOut], naming the encoder
 * and the size it was stopped at.
 */
internal suspend fun FfmpegRuntime.measureCapabilities(toolchain: Toolchain): CapabilitiesResult =
  coroutineScope {
    val measured =
      FFMPEG_ENCODERS.entries
        .flatMap { (codec, encoders) -> encoders.map { codec to it } }
        .filter { (_, encoder) -> toolchain.hasEncoder(encoder.name) }
        .map { (codec, encoder) -> async { videoCapability(toolchain, codec, encoder) } }
        .awaitAll()
    val video =
      measured
        .map { answer ->
          when (answer) {
            is ProbeAnswer.Expired -> return@coroutineScope answer.refusal()
            is ProbeAnswer.Answered -> answer.value
          }
        }.filterNotNull()

    // Probed at HDR_PROBE_SIZE, the one rung every backend asks this at.
    val hdrEncoder = video.hdrEncoder()
    val hdrProbe =
      hdrEncoder?.hdrPixelFormat?.let { pixelFormat ->
        canEncode(toolchain, hdrEncoder, HDR_PROBE_SIZE, pixelFormat, hdrEncoder.hdrProfile)
      }
    val supportsHdrEncoding =
      when (hdrProbe) {
        null -> false
        is ProbeAnswer.Expired -> return@coroutineScope hdrProbe.refusal()
        is ProbeAnswer.Answered -> hdrProbe.value
      }

    CapabilitiesResult.Success(
      DeviceCapabilities(
        video = video,
        audio =
          buildList {
            if (toolchain.hasEncoder("aac")) add(AudioEncoderCapability(AudioCodec.Aac, SAMPLE_RATES, MAX_CHANNELS))
            if (toolchain.hasEncoder("alac")) add(AudioEncoderCapability(AudioCodec.Alac, SAMPLE_RATES, MAX_CHANNELS))
          },
        supportsHdrEncoding = supportsHdrEncoding,
        // A process is not a hardware codec session, so there is no budget to report.
        concurrentSessionBudget = null,
      ),
    )
  }

/**
 * The encoder an HDR export opens out of these measured encoders, or null when none of them is on DEFAULT_HDR_LADDER.
 *
 * The planner pins a kept grade to DEFAULT_HDR_LADDER and then takes the first ranked encoder for it, so this is the
 * one whose ten-bit probe answers `supportsHdrEncoding`, not any encoder the build carries.
 */
internal fun List<VideoEncoderCapability>.hdrEncoder(): FfmpegEncoder? =
  DEFAULT_HDR_LADDER
    .firstNotNullOfOrNull { codec -> firstOrNull { it.codec == codec } }
    ?.encoderName
    ?.let { name -> FFMPEG_ENCODERS.values.flatten().firstOrNull { it.name == name } }

private fun ProbeAnswer.Expired.refusal(): CapabilitiesResult.Failure = CapabilitiesResult.Failure(timedOut())

private suspend fun FfmpegRuntime.videoCapability(
  toolchain: Toolchain,
  codec: VideoCodec,
  encoder: FfmpegEncoder,
): ProbeAnswer<VideoEncoderCapability?> {
  for (size in RESOLUTION_LADDER) {
    val encodes =
      when (val rung = canEncode(toolchain, encoder, size)) {
        is ProbeAnswer.Expired -> return rung
        is ProbeAnswer.Answered -> rung.value
      }
    if (encodes) {
      return ProbeAnswer.Answered(
        VideoEncoderCapability(
          codec = codec,
          encoderName = encoder.name,
          maxSize = size,
          // ffmpeg publishes neither a rate ceiling nor a bitrate ceiling, and inventing one would make
          // plan() refuse things that work.
          maxFrameRate = null,
          maxBitrate = null,
          isHardwareAccelerated = encoder.isHardwareAccelerated,
          sizeAlignment = SIZE_ALIGNMENT,
        ),
      )
    }
  }
  return ProbeAnswer.Answered(null)
}

private suspend fun FfmpegRuntime.canEncode(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
  size: Size,
  pixelFormat: String? = null,
  profile: String? = null,
): ProbeAnswer<Boolean> {
  val timeout = deadline(Duration.ZERO)
  val output =
    capture(
      buildList {
        add(toolchain.ffmpeg)
        add("-hide_banner")
        add("-loglevel")
        add("error")
        add("-nostdin")
        add("-f")
        add("lavfi")
        add("-i")
        add("color=s=${size.width}x${size.height}:d=0.04")
        pixelFormat?.let {
          add("-vf")
          add("format=$it")
        }
        add("-c:v")
        add(encoder.name)
        pixelFormat?.let {
          add("-pix_fmt")
          add(it)
        }
        addAll(encoder.probeArguments)
        (profile ?: encoder.profile)?.let {
          add("-profile:v")
          add(it)
        }
        add("-f")
        add("null")
        add("-")
      },
      timeout,
    )
  if (output.timedOut) {
    return ProbeAnswer.Expired(ProbeOperation.ENCODER_TEST, timeout, rungExpiredReason(encoder, size, timeout))
  }
  return ProbeAnswer.Answered(output.started && output.exitCode == 0)
}

private fun rungExpiredReason(
  encoder: FfmpegEncoder,
  size: Size,
  deadline: Duration,
): String =
  "Encoding one ${size.width}x${size.height} test frame with `${encoder.name}` was still running after $deadline and " +
    "was stopped, so filmstrip could not tell what this build can encode. Asking for the capabilities again measures " +
    "them again."

private const val SIZE_ALIGNMENT = 2
private const val MAX_CHANNELS = 8

private val SAMPLE_RATES = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 96_000)
