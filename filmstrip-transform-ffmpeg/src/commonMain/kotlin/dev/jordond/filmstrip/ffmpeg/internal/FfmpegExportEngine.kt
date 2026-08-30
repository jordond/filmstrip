package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.diagnostics.report
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.videoCodecOf
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Drives ffmpeg as a separate program.
 *
 * Nothing here fails to construct. The binaries are resolved on first use and their absence is an
 * [ExportError.ToolchainMissing] value, so registering the backend on a machine without ffmpeg is
 * as harmless as registering any other backend that cannot run.
 */
@OptIn(InternalFilmstripApi::class)
internal class FfmpegExportEngine(
  private val components: ComponentRegistry,
  private val runtime: FfmpegRuntime,
) : ExportEngine {
  private val config: FfmpegConfig get() = runtime.config
  private val capabilityLock = Mutex()
  private var deviceCapabilities: DeviceCapabilities? = null
  private val reportLock = Mutex()
  private var toolchainReported = false

  override suspend fun capabilities(): CapabilitiesResult =
    when (val result = toolchain()) {
      is ToolchainResult.Unavailable -> {
        CapabilitiesResult.Failure(result.error)
      }
      is ToolchainResult.Available -> {
        CapabilitiesResult.Success(device(result.toolchain))
      }
    }

  override suspend fun plan(
    composition: EditComposition,
    spec: ExportSpec,
  ): Verdict =
    when (val lowering = lower(composition, spec)) {
      is LoweringResult.Failed -> Verdict.Incapable(listOf(lowering.error), null)
      is LoweringResult.Done -> lowering.lowering.verdict
    }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    channelFlow {
      val toolchain =
        when (val result = toolchain()) {
          is ToolchainResult.Unavailable -> {
            send(ExportStatus.Failure(result.error))
            return@channelFlow
          }
          is ToolchainResult.Available -> {
            result.toolchain
          }
        }

      // The plan carries the composition and the spec it was resolved from, so the graph is rebuilt
      // rather than carried across the call. Probes are cached, so this costs nothing.
      val lowered =
        when (val result = lower(plan.composition, plan.spec)) {
          is LoweringResult.Failed -> {
            send(ExportStatus.Failure(result.error))
            return@channelFlow
          }
          is LoweringResult.Done -> {
            result.lowering
          }
        }

      val invocation =
        lowered.invocation ?: run {
          val reason =
            (lowered.verdict as? Verdict.Incapable)?.reasons?.firstOrNull()
              ?: ExportError.InvalidComposition("The composition no longer plans to anything runnable.")
          send(ExportStatus.Failure(reason))
          return@channelFlow
        }

      val scratch = Scratch.create()
      try {
        val outputPath = Scratch.resolveSink(to)
        val resolvedInputs =
          invocation.inputs.map { input ->
            when (val source = input.source) {
              is InputSource.OfPath -> source.path
              is InputSource.OfImage -> scratch.materialise(source.image)
              is InputSource.Generated -> source.description
            }
          }

        send(ExportStatus.Started)

        val progress = ProgressParser(invocation.duration.inWholeMicroseconds)
        val stderr = StderrTail()

        // channelFlow rather than flow: the reader callbacks run on their own coroutines, and
        // progress that only arrives once the process has exited is a progress bar that jumps from
        // nothing to done.
        val command = invocation.arguments(toolchain, config, resolvedInputs, outputPath)
        components.report(BACKEND, "invocation", mapOf("command" to command.joinToString(" ")))

        val exitCode =
          runtime.stream(
            command = command,
            onStdout = { line -> progress.accept(line)?.let { trySend(it) } },
            onStderr = stderr::accept,
          )

        if (exitCode != 0) {
          send(ExportStatus.Failure(classifyExit(exitCode, stderr.text(), outputPath)))
          return@channelFlow
        }

        val written = runtime.reprobe(toolchain, outputPath)
        if (written == null) {
          send(ExportStatus.Failure(ExportError.SinkUnwritable(outputPath, NOT_READABLE)))
          return@channelFlow
        }

        // ffmpeg falls back silently when it cannot honour a request, so the exit code is not
        // enough. The probe is on the critical path anyway, because Success carries a MediaInfo.
        drift(plan, written)?.let {
          send(ExportStatus.Failure(ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, it)))
          return@channelFlow
        }

        send(
          ExportStatus.Success(
            output = MediaSink.Path(outputPath),
            info = written,
            adjustments = (lowered.verdict as? Verdict.Degraded)?.adjustments.orEmpty(),
          ),
        )
      } finally {
        scratch.delete()
      }
    }.buffer(Channel.UNLIMITED)

  override fun parityOf(specId: String): EffectParity? = FfmpegParity.of(specId)

  /**
   * Resolves the binaries, announcing what was found the first time anything asks.
   *
   * The banner is the thing a desktop bug report is asked for and the only place it exists is the
   * output of the spawn that resolved the toolchain, which nothing else returns.
   */
  private suspend fun toolchain(): ToolchainResult {
    val result = runtime.toolchain()

    reportLock.withLock {
      if (toolchainReported) return@withLock
      toolchainReported = true

      when (result) {
        is ToolchainResult.Unavailable -> {
          components.report(
            source = BACKEND,
            name = "toolchain",
            detail = mapOf("found" to (result.error.foundVersion ?: "nothing"), "reason" to result.error.message),
          )
        }
        is ToolchainResult.Available -> {
          components.report(
            source = BACKEND,
            name = "toolchain",
            detail =
              mapOf(
                "ffmpeg" to result.toolchain.ffmpeg,
                "ffprobe" to result.toolchain.ffprobe,
                "version" to result.toolchain.version.printed,
                "banner" to result.toolchain.version.banner,
              ),
          )
        }
      }
    }

    return result
  }

  private suspend fun lower(
    composition: EditComposition,
    spec: ExportSpec,
  ): LoweringResult {
    val toolchain =
      when (val result = toolchain()) {
        is ToolchainResult.Unavailable -> return LoweringResult.Failed(result.error)
        is ToolchainResult.Available -> result.toolchain
      }

    val infos = mutableMapOf<MediaSource, MediaInfo>()
    composition.tracks.flatMap { it.clips }.forEach { clip ->
      val path =
        readablePath(clip.source)
          ?: return LoweringResult.Failed(ExportError.SourceUnreadable(clip.source.describe(), READS_FILES))
      val info =
        runtime.probe(toolchain, path)
          ?: return LoweringResult.Failed(ExportError.SourceUnreadable(clip.source.describe(), UNREADABLE))
      infos[clip.source] = info
    }

    return LoweringResult.Done(
      FfmpegPlanner(toolchain, components.effectResolvers)
        .lower(composition, spec, device(toolchain), infos),
    )
  }

  // Held across the probe rather than around the assignment, so two exports planned at once spawn
  // one ladder between them instead of one each.
  private suspend fun device(toolchain: Toolchain): DeviceCapabilities =
    capabilityLock.withLock {
      deviceCapabilities ?: measureCapabilities(toolchain).also { deviceCapabilities = it }
    }

  // A ladder rather than a published number, because ffmpeg does not publish one. Each rung encodes
  // a single frame as cheaply as that encoder allows, and the whole probe is cached for the life of
  // the engine. Every codec in the table is probed, including the ones CODEC_LADDER leaves out of
  // Auto, so a caller who names one still gets an answer. One encoder's ladder does not wait on
  // another's, or a build carrying six of them would spend a second of somebody's startup.
  private suspend fun measureCapabilities(toolchain: Toolchain): DeviceCapabilities =
    coroutineScope {
      val video =
        FFMPEG_ENCODERS.entries
          .flatMap { (codec, encoders) -> encoders.map { codec to it } }
          .filter { (_, encoder) -> toolchain.hasEncoder(encoder.name) }
          .map { (codec, encoder) -> async { videoCapability(toolchain, codec, encoder) } }
          .awaitAll()
          .filterNotNull()

      // Answered for the encoder an HDR export actually opens, not for any encoder the build
      // carries. The planner pins a kept grade to DEFAULT_HDR_LADDER and then takes the first
      // ranked encoder for it, so probing a different one would claim a grade this backend goes on
      // to hand to an encoder that cannot write it.
      //
      // Probed at the smallest resolution rung, since this answers a format question rather than a
      // size one: an encoder that lands Main10 at 720p is trusted to land it at whatever size the
      // plan asks for, and probing every rung would cost another 8K encode for nothing.
      val hdrEncoder =
        DEFAULT_HDR_LADDER
          .firstNotNullOfOrNull { codec -> video.firstOrNull { it.codec == codec } }
          ?.encoderName
          ?.let { name -> FFMPEG_ENCODERS.values.flatten().firstOrNull { it.name == name } }
      val supportsHdrEncoding =
        hdrEncoder?.hdrPixelFormat?.let { pixelFormat ->
          canEncode(toolchain, hdrEncoder, RESOLUTION_LADDER.last(), pixelFormat, hdrEncoder.hdrProfile)
        } ?: false

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
      )
    }

  private suspend fun videoCapability(
    toolchain: Toolchain,
    codec: VideoCodec,
    encoder: FfmpegEncoder,
  ): VideoEncoderCapability? {
    val largest = RESOLUTION_LADDER.firstOrNull { canEncode(toolchain, encoder, it) } ?: return null
    return VideoEncoderCapability(
      codec = codec,
      encoderName = encoder.name,
      maxSize = largest,
      // ffmpeg publishes neither a rate ceiling nor a bitrate ceiling, and inventing one would make
      // plan() refuse things that work.
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = encoder.isHardwareAccelerated,
      sizeAlignment = SIZE_ALIGNMENT,
    )
  }

  private suspend fun canEncode(
    toolchain: Toolchain,
    encoder: FfmpegEncoder,
    size: Size,
    pixelFormat: String? = null,
    profile: String? = null,
  ): Boolean {
    val output =
      runtime.capture(
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
          profile?.let {
            add("-profile:v")
            add(it)
          }
          add("-f")
          add("null")
          add("-")
        },
      )
    return output.started && output.exitCode == 0
  }

  private fun drift(
    plan: ExportPlan,
    written: MediaInfo,
  ): String? {
    val expected = plan.output
    // AudioSpec.AudioOnly still resolves a videoCodec and size, since OutputFormat has no way to
    // say there is none, but this backend never writes a video track for it. Comparing against a
    // track the plan was never going to produce would fail every audio-only export.
    if (plan.composition.audio == AudioSpec.AudioOnly) {
      if (written.video != null) return "The written file has a video track, and the plan asked for audio only."
    } else {
      val video = written.video
      if (video == null) return "The written file has no video track, but the plan asked for one."
      if (video.displaySize != expected.size) {
        return "The written file is ${video.displaySize.width}x${video.displaySize.height}, and the " +
          "plan asked for ${expected.size.width}x${expected.size.height}."
      }
      // Only a codec CodecKind actually names is compared. A kind it does not recognise, such as
      // ALAC, cannot be checked either way, and treating that as drift would fail a file that
      // matches the plan exactly.
      val writtenVideoCodec = runCatching { videoCodecOf(video.codec.kind) }.getOrNull()
      if (writtenVideoCodec != null && writtenVideoCodec != expected.videoCodec) {
        return "The written file's video codec is ${video.codec.name}, and the plan asked for ${expected.videoCodec}."
      }
      expected.frameRate?.let { planned ->
        val actual = video.frameRate ?: return@let
        if (abs(actual - planned) > FRAME_RATE_TOLERANCE) {
          return "The written file runs at $actual fps, and the plan asked for $planned."
        }
      }
    }
    expected.audioFormat?.let { planned ->
      val audio = written.audio ?: return "The written file has no audio track, but the plan asked for one."
      if (audio.sampleRate != planned.sampleRate || audio.channelCount != planned.channelCount) {
        return "The written file's audio is ${audio.sampleRate} Hz with ${audio.channelCount} " +
          "channels, and the plan asked for ${planned.sampleRate} Hz with ${planned.channelCount}."
      }
      val writtenAudioCodec = runCatching { audioCodecOf(audio.codec.kind) }.getOrNull()
      if (writtenAudioCodec != null && writtenAudioCodec != expected.audioCodec) {
        return "The written file's audio codec is ${audio.codec.name}, and the plan asked for ${expected.audioCodec}."
      }
    }
    return null
  }

  private sealed interface LoweringResult {
    class Done(
      val lowering: Lowering,
    ) : LoweringResult

    class Failed(
      val error: ExportError,
    ) : LoweringResult
  }

  private companion object {
    const val BACKEND = "ffmpeg"
    const val SIZE_ALIGNMENT = 2
    const val MAX_CHANNELS = 8
    const val FRAME_RATE_TOLERANCE = 0.5f

    val SAMPLE_RATES = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 96_000)

    val RESOLUTION_LADDER =
      listOf(Size(7680, 4320), Size(3840, 2160), Size(1920, 1080), Size(1280, 720))

    const val UNREADABLE = "ffprobe could not read the source."

    const val NOT_READABLE =
      "ffmpeg reported success but the output could not be probed, so nothing usable was written."
  }
}
