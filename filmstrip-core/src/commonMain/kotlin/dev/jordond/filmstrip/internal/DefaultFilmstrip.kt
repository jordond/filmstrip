package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

// Dispatches the facade's operations to whatever was registered. Probe is the only one core can
// answer on its own, and only on the targets whose OS hands the metadata over for free.
// Everything else is a typed failure until its artifact is added.
@OptIn(InternalFilmstripApi::class)
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

  override fun composition(block: CompositionBuilder.() -> Unit): EditComposition =
    compositionBuilder().apply(block).build()

  override fun compositionBuilder(): CompositionBuilder = CompositionBuilder()

  override suspend fun probe(source: MediaSource): ProbeResult = prober.probe(source)

  override suspend fun capabilities(): CapabilitiesResult {
    val engine = exportEngine ?: return CapabilitiesResult.Failure(missingTransform("capabilities"))
    return engine.capabilities()
  }

  override suspend fun plan(
    composition: EditComposition,
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
        return@flow
      }
      emitAll(engine.export(plan, to))
    }

  override fun export(
    composition: EditComposition,
    spec: ExportSpec,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      val engine = exportEngine
      if (engine == null) {
        emit(ExportStatus.Failure(missingTransform("export")))
        return@flow
      }
      when (val verdict = engine.plan(composition, spec)) {
        is Verdict.Capable -> {
          emitAll(engine.export(verdict.plan, to))
        }
        is Verdict.Degraded -> {
          // Adjustments reach the caller before any progress does.
          emit(ExportStatus.Adjusted(verdict.adjustments))
          emitAll(engine.export(verdict.plan, to))
        }
        is Verdict.Incapable -> {
          emit(ExportStatus.Failure(verdict.reasons.first()))
        }
      }
    }

  override suspend fun frame(
    composition: EditComposition,
    at: Duration,
  ): FrameResult =
    thumbnails.frame(ThumbnailRequest(composition, at, heightPx = 0, effectsRevision = composition.effectsRevision()))

  override fun frames(
    composition: EditComposition,
    at: List<Duration>,
    heightPx: Int,
  ): Flow<FrameResult> = thumbnails.frames(composition, at, heightPx)

  override fun preview(
    composition: EditComposition,
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

  private fun missingTransform(operation: String): ExportError.BackendMissing =
    ExportError.BackendMissing(
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
