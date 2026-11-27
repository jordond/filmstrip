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
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.HDR_PROBE_SIZE
import dev.jordond.filmstrip.transform.internal.ProbeOperation
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import dev.jordond.filmstrip.transform.internal.smallestAccepted
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
 *
 * Only a caller asking for the capabilities runs all of it. An export reads its device through [EncoderReading], which
 * encodes only what its own plan needs.
 */
internal suspend fun FfmpegRuntime.measureCapabilities(toolchain: Toolchain): CapabilitiesResult =
  coroutineScope {
    val measured =
      toolchain
        .videoEncoders()
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
    val supportsHdrEncoding =
      when (val hdrProbe = video.hdrEncoder()?.let { encodesHdr(toolchain, it) }) {
        null -> false
        is ProbeAnswer.Expired -> return@coroutineScope hdrProbe.refusal()
        is ProbeAnswer.Answered -> hdrProbe.value
      }

    CapabilitiesResult.Success(
      DeviceCapabilities(
        video = video,
        audio = toolchain.audioEncoders(),
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

/**
 * Every video encoder in the table that this build carries, with the codec it writes, in the table's order.
 */
internal fun Toolchain.videoEncoders(): List<Pair<VideoCodec, FfmpegEncoder>> =
  FFMPEG_ENCODERS.entries
    .flatMap { (codec, encoders) -> encoders.map { codec to it } }
    .filter { (_, encoder) -> hasEncoder(encoder.name) }

/**
 * The audio encoders this build carries. Nothing is encoded to find them.
 */
internal fun Toolchain.audioEncoders(): List<AudioEncoderCapability> =
  buildList {
    if (hasEncoder("aac")) add(AudioEncoderCapability(AudioCodec.Aac, SAMPLE_RATES, MAX_CHANNELS))
    if (hasEncoder("alac")) add(AudioEncoderCapability(AudioCodec.Alac, SAMPLE_RATES, MAX_CHANNELS))
  }

/**
 * What [encoder] reports once its floor and ceiling are known.
 */
internal fun capabilityOf(
  codec: VideoCodec,
  encoder: FfmpegEncoder,
  floor: Size,
  ceiling: Size,
): VideoEncoderCapability =
  VideoEncoderCapability(
    codec = codec,
    encoderName = encoder.name,
    maxSize = ceiling,
    minSize = floor,
    // ffmpeg publishes neither a rate ceiling nor a bitrate ceiling, and inventing one would make plan() refuse
    // things that work.
    maxFrameRate = null,
    maxBitrate = null,
    isHardwareAccelerated = encoder.isHardwareAccelerated,
    sizeAlignment = ENCODER_SIZE_ALIGNMENT,
  )

/**
 * The largest rung of RESOLUTION_LADDER [encoder] opens at, walked from the top, or null when it opens at none.
 */
internal suspend fun FfmpegRuntime.ceilingOf(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
): ProbeAnswer<Size?> {
  for (size in RESOLUTION_LADDER) {
    when (val rung = canEncode(toolchain, encoder, size)) {
      is ProbeAnswer.Expired -> return rung
      is ProbeAnswer.Answered -> if (rung.value) return ProbeAnswer.Answered(size)
    }
  }
  return ProbeAnswer.Answered(null)
}

/**
 * The smallest frame [encoder] opens at, the first rung of FLOOR_LADDER it takes, or null when it takes none.
 *
 * ffmpeg publishes no floor either, so the walk is the only answer.
 */
internal suspend fun FfmpegRuntime.floorOf(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
): ProbeAnswer<Size?> {
  val floor =
    smallestAccepted { rung ->
      when (val answer = canEncode(toolchain, encoder, rung)) {
        is ProbeAnswer.Expired -> return answer
        is ProbeAnswer.Answered -> answer.value
      }
    }
  return ProbeAnswer.Answered(floor)
}

/**
 * Whether [encoder] opens its ten-bit profile at HDR_PROBE_SIZE. An encoder with no such profile answers false.
 */
internal suspend fun FfmpegRuntime.encodesHdr(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
): ProbeAnswer<Boolean> {
  val pixelFormat = encoder.hdrPixelFormat ?: return ProbeAnswer.Answered(false)
  return canEncode(toolchain, encoder, HDR_PROBE_SIZE, pixelFormat, encoder.hdrProfile)
}

/**
 * Whether [encoder] writes one frame of [size], through [FfmpegRuntime.testEncode] so a rung is encoded once.
 */
internal suspend fun FfmpegRuntime.canEncode(
  toolchain: Toolchain,
  encoder: FfmpegEncoder,
  size: Size,
  pixelFormat: String? = null,
  profile: String? = null,
): ProbeAnswer<Boolean> {
  val command =
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
    }
  return testEncode(command) { deadline ->
    ProbeAnswer.Expired(ProbeOperation.ENCODER_TEST, deadline, rungExpiredReason(encoder, size, deadline))
  }
}

private fun ProbeAnswer.Expired.refusal(): CapabilitiesResult.Failure = CapabilitiesResult.Failure(timedOut())

private suspend fun FfmpegRuntime.videoCapability(
  toolchain: Toolchain,
  codec: VideoCodec,
  encoder: FfmpegEncoder,
): ProbeAnswer<VideoEncoderCapability?> {
  val ceiling =
    when (val answer = ceilingOf(toolchain, encoder)) {
      is ProbeAnswer.Expired -> return answer
      is ProbeAnswer.Answered -> answer.value ?: return ProbeAnswer.Answered(null)
    }
  val floor =
    when (val answer = floorOf(toolchain, encoder)) {
      is ProbeAnswer.Expired -> return answer
      is ProbeAnswer.Answered -> answer.value ?: return ProbeAnswer.Answered(null)
    }
  return ProbeAnswer.Answered(capabilityOf(codec, encoder, floor, ceiling))
}

private fun rungExpiredReason(
  encoder: FfmpegEncoder,
  size: Size,
  deadline: Duration,
): String =
  "Encoding one ${size.width}x${size.height} test frame with `${encoder.name}` was still running after $deadline and " +
    "was stopped, so filmstrip could not tell what this build can encode. Asking for the capabilities again measures " +
    "them again."

// Each side of a frame these encoders take is a multiple of this.
internal const val ENCODER_SIZE_ALIGNMENT: Int = 2

private const val MAX_CHANNELS = 8

private val SAMPLE_RATES = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 96_000)
