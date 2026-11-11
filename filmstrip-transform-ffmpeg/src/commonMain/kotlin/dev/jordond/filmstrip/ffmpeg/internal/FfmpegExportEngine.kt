package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.diagnostics.report
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.probe.drift
import dev.jordond.filmstrip.ffmpeg.internal.process.LIVE_FEED_FAILED
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.ProgressParser
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.ffmpeg.internal.process.SOURCE_GONE
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.StderrTail
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.internal.process.chooseForm
import dev.jordond.filmstrip.ffmpeg.internal.process.classifyExit
import dev.jordond.filmstrip.ffmpeg.internal.process.interruptibly
import dev.jordond.filmstrip.ffmpeg.internal.process.liveFeedFailure
import dev.jordond.filmstrip.ffmpeg.internal.process.missingSource
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.ffmpeg.internal.process.scratchFailure
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.sinkParentRefusal
import dev.jordond.filmstrip.transform.internal.EngineLowering
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.LoweringExportEngine
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.copy.CopyAsk
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.withCopyPackets
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.probe.OpeningRead
import dev.jordond.filmstrip.transform.internal.probe.PlanningReadResult
import dev.jordond.filmstrip.transform.internal.probe.PlanningReads
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.refusedLowering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.time.Duration

/**
 * Drives ffmpeg as a separate program.
 *
 * Nothing here fails to construct. The binaries are resolved on first use and their absence is an
 * [FilmstripError.ToolchainMissing] value, so registering the backend on a machine without ffmpeg is
 * as harmless as registering any other backend that cannot run.
 */
@InternalFilmstripApi
public class FfmpegExportEngine internal constructor(
  internal val components: EngineComponents,
  internal val runtime: FfmpegRuntime,
  internal val scratchRoot: Path = SystemTemporaryDirectory,
) : LoweringExportEngine() {
  private val config: FfmpegConfig get() = runtime.config
  private val reportLock = Mutex()

  // Held at its own graph type as well, since a preview opens the graph an export would run.
  internal val runs = EngineLowering(components.effectResolvers, ::grade, ::lower, ::run)
  override val lowering: EngineLowering<*> get() = runs

  // Registered probers answer first, the way they do on every other engine, and ffprobe answers last. A copy opening
  // is read the same way a source is and cached on the runtime as well, since one edit is negotiated to plan it, to
  // resolve it for a preview and again to export it, and an answer that moved between those would open the three in
  // different places.
  private val reads =
    PlanningReads(
      prober = chainedProber(components, last = FfmpegProber(runtime)),
      readDevice = { device(foundToolchain()) },
      readOpening = ::openingAtOrBefore,
    )

  private suspend fun openingAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): OpeningRead {
    val path =
      try {
        withContext(Dispatchers.IO) { readablePath(source, runtime.bytesRoot) }
      } catch (_: MediaBytesFileFailure) {
        // The planner resolves the same path next and refuses with the reason.
        null
      } ?: return OpeningRead.At(null)
    return when (val read = runtime.syncSampleAtOrBefore(foundToolchain(), path, cut)) {
      is ProbeAnswer.Answered -> OpeningRead.At(read.value)
      is ProbeAnswer.Expired -> OpeningRead.Failed(read.timedOut())
    }
  }

  // Whether the last toolchain answer reported was one with binaries in it, or null before any was.
  // A missing toolchain is looked for again on every call, so one installed later is announced too.
  private var reportedAvailable: Boolean? = null

  override suspend fun capabilities(): CapabilitiesResult =
    when (val result = toolchain()) {
      is ToolchainResult.Unavailable -> {
        CapabilitiesResult.Failure(result.error)
      }
      is ToolchainResult.Available -> {
        device(result.toolchain)
      }
    }

  override fun parityOf(specId: String): EffectParity? = FfmpegParity.of(specId)

  /**
   * Resolves the binaries, announcing what was found the first time anything asks.
   *
   * The banner is the thing a desktop bug report is asked for and the only place it exists is the
   * output of the spawn that resolved the toolchain, which nothing else returns. A toolchain that was
   * missing and then turns up is announced again, once, when it does.
   */
  private suspend fun toolchain(): ToolchainResult {
    val result = runtime.toolchain()
    val available = result is ToolchainResult.Available

    reportLock.withLock {
      if (reportedAvailable == available) return@withLock
      reportedAvailable = available

      when (result) {
        is ToolchainResult.Unavailable -> {
          components.report(
            source = BACKEND,
            name = DiagnosticEvent.TOOLCHAIN,
            detail =
              mapOf(
                Detail.FOUND to (result.error.foundVersion ?: "nothing"),
                Detail.REASON to result.error.message,
              ),
          )
        }
        is ToolchainResult.Available -> {
          components.report(
            source = BACKEND,
            name = DiagnosticEvent.TOOLCHAIN,
            detail =
              mapOf(
                Detail.FFMPEG to result.toolchain.ffmpeg,
                Detail.FFPROBE to result.toolchain.ffprobe,
                Detail.VERSION to result.toolchain.version.printed,
                Detail.BANNER to result.toolchain.version.banner,
              ),
          )
        }
      }
    }

    return result
  }

  private suspend fun lower(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size?,
    grade: ResolvedHdr?,
  ): LoweredExport<FfmpegRun> {
    val toolchain =
      when (val result = toolchain()) {
        is ToolchainResult.Unavailable -> return refusedLowering(result.error)
        is ToolchainResult.Available -> result.toolchain
      }

    val read =
      when (val result = reads.read(composition)) {
        is PlanningReadResult.Failed -> return refusedLowering(result.error)
        is PlanningReadResult.Read -> result
      }
    val colors =
      when (val result = runtime.sourceColorsOf(toolchain, composition, ::sourcePath)) {
        is SourceColorRead.Refused -> return refusedLowering(result.error)
        is SourceColorRead.Read -> result
      }
    val planner =
      FfmpegPlanner(toolchain, components.effectResolvers, runtime.bytesRoot, components.audioEffectResolvers)
    val asked = Ask(spec, read.device, read.infos, read.openings, layoutSize, grade)
    val ask =
      when (val withPackets = askWithCopyPackets(toolchain, planner, composition, asked)) {
        is CopyAsk.Refused -> return refusedLowering(withPackets.error)
        is CopyAsk.Asked -> withPackets.ask
      }
    val lowered =
      interruptibly {
        planner.lower(
          composition,
          ask,
          sdColors = colors.sdColors,
          bt709Sources = colors.bt709Sources,
          tailFormatSources = colors.tailFormatSources,
        )
      }
    if (lowered.export.fallbackCopyWindow != null) {
      val export = planner.withFallbackCopyPackets(composition, ask, lowered.export) { readCopyPackets(toolchain, it) }
      return LoweredExport(export, null)
    }
    val graph = lowered.graph
    val negotiated = lowered.export.composition
    if (graph == null || negotiated == null) return LoweredExport(lowered.export, null)
    return LoweredExport(lowered.export, FfmpegRun(toolchain, graph, negotiated.adjustments))
  }

  /**
   * [ask] with the video packets of the copy it plans read in through [withCopyPackets], for a copy whose window ends
   * before its source does.
   */
  private suspend fun askWithCopyPackets(
    toolchain: Toolchain,
    planner: FfmpegPlanner,
    composition: Composition,
    ask: Ask,
  ): CopyAsk {
    val window = interruptibly { planner.copyPacketWindowOf(composition, ask) }
    return ask.withCopyPackets(window) { readCopyPackets(toolchain, it) }
  }

  private suspend fun readCopyPackets(
    toolchain: Toolchain,
    window: CopyPacketWindow,
  ): CopyPacketRead =
    sourcePath(window.source)?.let { runtime.copyPackets(toolchain, it, window.from, window.to) }
      ?: CopyPacketRead.Failed(READS_FILES)

  private suspend fun sourcePath(source: MediaSource): String? =
    try {
      withContext(Dispatchers.IO) { readablePath(source, runtime.bytesRoot) }
    } catch (_: MediaBytesFileFailure) {
      // The planner resolves the same path next and refuses with the reason.
      null
    }

  private suspend fun grade(
    composition: Composition,
    spec: ExportSpec,
  ): ExportGrade {
    val toolchain =
      when (val result = toolchain()) {
        is ToolchainResult.Unavailable -> return ExportGrade.Refused(result.error)
        is ToolchainResult.Available -> result.toolchain
      }
    return when (val read = reads.read(composition)) {
      is PlanningReadResult.Failed -> {
        ExportGrade.Refused(read.error)
      }
      is PlanningReadResult.Read -> {
        val planner =
          FfmpegPlanner(toolchain, components.effectResolvers, runtime.bytesRoot, components.audioEffectResolvers)
        val asked = Ask(spec, read.device, read.infos, read.openings)
        when (val withPackets = askWithCopyPackets(toolchain, planner, composition, asked)) {
          is CopyAsk.Refused -> ExportGrade.Refused(withPackets.error)
          is CopyAsk.Asked -> interruptibly { planner.exportGradeOf(composition, withPackets.ask) }
        }
      }
    }
  }

  // The reads only run once lower has found a toolchain, and the locator holds on to one it found.
  private suspend fun foundToolchain(): Toolchain =
    checkNotNull((runtime.toolchain() as? ToolchainResult.Available)?.toolchain) { NO_TOOLCHAIN }

  // channelFlow rather than flow: the reader callbacks run on their own coroutines, and progress that only arrives
  // once the process has exited is a progress bar that jumps from nothing to done.
  private fun run(
    graph: FfmpegRun,
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> = channelFlow { runInvocation(graph, plan, to) }.buffer(Channel.UNLIMITED)

  private suspend fun ProducerScope<ExportStatus>.runInvocation(
    graph: FfmpegRun,
    plan: ExportPlan,
    to: MediaSink,
  ) {
    val (toolchain, planned, adjustments) = graph

    // ffmpeg opens its inputs and its output only once it runs, and a missing file reads the same from either side on
    // every release, so both are looked for here before anything is spawned.
    val outputPath =
      when (val sink = Scratch.resolveSink(to)) {
        is Scratch.SinkResult.Refused -> return send(ExportStatus.Failure(sink.error))
        is Scratch.SinkResult.Ready -> sink.path
      }
    sinkParentRefusal(outputPath)?.let { return send(ExportStatus.Failure(it)) }

    val scratch =
      try {
        Scratch.create(scratchRoot)
      } catch (failure: IOException) {
        return send(ExportStatus.Failure(scratchFailure(failure)))
      }
    val chosen = chooseForm(planned, Duration.ZERO, runtime.bindFeeds)
    val invocation = chosen.invocation
    try {
      val inputs =
        try {
          invocation.inputs.map { scratch.resolve(it.source, chosen.feeds) }
        } catch (failure: IOException) {
          return send(ExportStatus.Failure(scratchFailure(failure)))
        }
      inputs.missingSource()?.let {
        return send(
          ExportStatus.Failure(FilmstripError.SourceUnreadable(it, SOURCE_GONE)),
        )
      }

      val (command, spawned) =
        try {
          val resolvedSidecars = invocation.sidecars.map { scratch.write(it) }
          val command = invocation.arguments(toolchain, config, inputs.map { it.path }, resolvedSidecars, outputPath)
          command to command.readingGraphFrom(scratch)
        } catch (failure: IOException) {
          return send(ExportStatus.Failure(scratchFailure(failure)))
        }

      send(ExportStatus.Started)

      val progress = ProgressParser(invocation.duration.inWholeMicroseconds)
      val stderr = StderrTail()
      components.report(BACKEND, DiagnosticEvent.INVOCATION, invocationDetails(command, chosen.fallback))

      chosen.feeds?.start()
      val exitCode =
        runtime.stream(
          command = spawned,
          onStdout = { line -> progress.accept(line)?.let { trySend(it) } },
          onStderr = stderr::accept,
        )

      // ffmpeg reads a feed that failed as a stream that ended, and can exit cleanly on a short file.
      chosen.feeds?.failure()?.let {
        return send(ExportStatus.Failure(classifyExit(LIVE_FEED_FAILED, liveFeedFailure(it), outputPath)))
      }

      if (exitCode != 0) {
        // Looked for again, since a file can go between the check above and ffmpeg opening it.
        val error =
          classifyExit(
            exitCode = exitCode,
            stderr = stderr.text(),
            sink = outputPath,
            inputs = inputs,
            missingSource = inputs.missingSource(),
            sinkParentMissing = sinkParentRefusal(outputPath) != null,
          )
        return send(ExportStatus.Failure(error))
      }

      val written =
        when (val read = runtime.reprobe(toolchain, outputPath)) {
          is ProbeAnswer.Expired -> {
            return send(ExportStatus.Failure(read.timedOut()))
          }
          is ProbeAnswer.Answered -> {
            read.value ?: return send(ExportStatus.Failure(FilmstripError.SinkUnwritable(outputPath, NOT_READABLE)))
          }
        }

      // ffmpeg falls back silently when it cannot honour a request, so the exit code is not enough. The probe is on
      // the critical path anyway, because Success carries a MediaInfo.
      drift(plan, written)?.let {
        return send(ExportStatus.Failure(FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, it)))
      }

      send(
        ExportStatus.Success(
          output = MediaSink.Path(outputPath),
          info = written,
          adjustments = adjustments,
        ),
      )
    } finally {
      chosen.feeds?.close()
      scratch.delete()
    }
  }

  // Memoised on the runtime, so every engine built over one config reaches the ladder that was
  // already measured.
  private suspend fun device(toolchain: Toolchain): CapabilitiesResult =
    runtime.capabilities { runtime.measureCapabilities(toolchain) }

  // What an export runs: the graph, the toolchain it was lowered for, and what the negotiation changed to get there.
  internal data class FfmpegRun(
    val toolchain: Toolchain,
    val invocation: Invocation,
    val adjustments: List<Adjustment>,
  )

  internal companion object {
    const val BACKEND = "ffmpeg"

    /**
     * What a run reports about its command, and why it draws a look's map with its own graph when it does.
     */
    fun invocationDetails(
      command: List<String>,
      fallback: String?,
    ): Map<String, String> =
      buildMap {
        put(Detail.COMMAND, command.joinToString(" "))
        fallback?.let { put(Detail.REASON, it) }
      }

    private const val NO_TOOLCHAIN = "The sources were read before a toolchain was found."

    private const val NOT_READABLE =
      "ffmpeg reported success but the output could not be probed, so nothing usable was written."
  }
}
