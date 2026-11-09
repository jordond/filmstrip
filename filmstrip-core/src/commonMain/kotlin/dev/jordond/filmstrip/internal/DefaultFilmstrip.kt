package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

// Dispatches the facade's operations to whatever was registered. Probe is the only one core can
// answer on its own, and only on the targets whose OS hands the metadata over for free.
// Everything else is a typed failure until its artifact is added.
internal class DefaultFilmstrip(
  override val components: ComponentRegistry,
) : Filmstrip {
  // Registered probers first, core's own last. A module that demuxes the container knows more than
  // a read-only OS API does, and on the JVM and the web it is the only thing that knows anything.
  // Shared with the export backends, so a plan reads a source the same way this does.
  private val prober: MediaProber by lazy { chainedProber(components) }

  private val exportEngine: ExportEngine? by lazy {
    components.exportEngineFactories.firstNotNullOfOrNull { it.create(components) }
  }

  override suspend fun probe(source: MediaSource): ProbeResult = prober.probe(source)

  override suspend fun capabilities(): CapabilitiesResult =
    exportEngine?.capabilities() ?: CapabilitiesResult.Failure(missingTransform("capabilities"))

  override suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict {
    val engine =
      exportEngine
        ?: return Verdict.Incapable(listOf(missingTransform("plan")), withoutUnsupported = null)
    return engine.plan(composition, spec)
  }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      val engine = exportEngine
      if (engine == null) {
        emit(ExportStatus.Failure(missingTransform("export")))
      } else {
        emitAll(engine.run(plan, to).withDropped(plan))
      }
    }

  override fun export(
    composition: Composition,
    spec: ExportSpec,
    to: MediaSink,
  ): Flow<ExportStatus> {
    val engine = exportEngine ?: return flowOf(ExportStatus.Failure(missingTransform("export")))
    return flow {
      when (val verdict = engine.plan(composition, spec)) {
        is Verdict.Capable -> {
          emitAll(engine.run(verdict.plan, to))
        }
        is Verdict.Degraded -> {
          // Adjustments reach the caller before any progress does.
          emit(ExportStatus.Adjusted(verdict.adjustments))
          emitAll(engine.run(verdict.plan, to))
        }
        is Verdict.Incapable -> {
          emit(ExportStatus.Failure(verdict.reasons))
        }
      }
    }
  }

  override suspend fun frame(
    composition: Composition,
    at: Duration,
    heightPx: Int?,
    exportSpec: ExportSpec,
  ): FrameResult {
    outputHeightRefusal(heightPx)?.let { return FrameResult.Failure(it) }
    return thumbnails.frame(
      ThumbnailRequest(
        composition = composition,
        position = at,
        heightPx = heightPx,
        effectsRevision = composition.effectsRevision(),
        precise = true,
        exportSpec = exportSpec,
      ),
    )
  }

  override fun frames(
    composition: Composition,
    at: List<Duration>,
    heightPx: Int?,
    exportSpec: ExportSpec,
  ): Flow<FrameResult> {
    val refusal = outputHeightRefusal(heightPx) ?: return thumbnails.frames(composition, at, heightPx, exportSpec)
    return at.map { FrameResult.Failure(refusal) }.asFlow()
  }

  override fun preview(
    composition: Composition,
    config: PlayerConfig,
  ): VideoPlayer {
    val engine =
      components.playerEngineFactories.firstNotNullOfOrNull { it.create(config, components) }
        ?: return MissingEnginePlayer(MISSING_PLAYER)
    return EngineVideoPlayer(engine, composition)
  }

  // Parity is a property of the pipeline that renders an effect, so the engine answers. With no
  // engine registered there is no pipeline and therefore no answer, which `null` already covers.
  override fun parityOf(specId: String): EffectParity? = exportEngine?.parityOf(specId)

  private val thumbnails = ThumbnailDispatcher(components)

  // Every engine runs behind the one lock, so Started marks the moment an export leaves the queue
  // on every backend rather than on the one whose encoder sessions run out.
  private fun ExportEngine.run(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> = flow { exportLock.withLock { emitAll(export(plan, to)) } }

  // An engine runs a plan with its dropped effects already gone, so it has nothing to say about
  // them. They are carried on the plan and added back to what it finished with.
  private fun Flow<ExportStatus>.withDropped(plan: ExportPlan): Flow<ExportStatus> =
    if (plan.droppedEffects.isEmpty()) {
      this
    } else {
      map { status ->
        if (status is ExportStatus.Success) {
          ExportStatus.Success(status.output, status.info, status.adjustments + plan.droppedEffects)
        } else {
          status
        }
      }
    }

  private fun missingTransform(operation: String): FilmstripError.BackendMissing =
    FilmstripError.BackendMissing(
      artifact = EXPORT_BACKEND.artifact,
      message =
        "`$operation` needs an export backend. Add ${EXPORT_BACKEND.artifact} and register it " +
          "with Filmstrip { ${EXPORT_BACKEND.registration} }. Registered export engine " +
          "factories: ${components.exportEngineFactories.size}.",
    )

  private companion object {
    val MISSING_PLAYER =
      "`preview` needs a playback backend. Add dev.jordond.filmstrip:filmstrip-player and " +
        "register it with Filmstrip { playerBackend() }."
  }
}

/**
 * Holds exports to one at a time across the process.
 *
 * Hardware encode sessions are a small pool every [Filmstrip] instance shares, and a device that
 * has none left refuses the session instead of queueing for one. Running exports one at a time
 * turns that into a wait.
 */
private val exportLock = Mutex()
