package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * A [Filmstrip] with no backend under it, for testing code that probes, plans, exports, previews or renders frames.
 *
 * Every operation answers from a script, which is one of the `on` properties here. Left alone each one answers the
 * way a capable device would for a ten second 1080p file, built with the `test` builders, so a test sets only the
 * answers it is about. A script is handed what was asked, so it can answer one source or one position differently
 * from the rest.
 *
 * Every call the code under test makes is kept in [calls]. An export is driven by hand through its [FakeExport] in
 * [exports], and a preview is a [FakeVideoPlayer] kept in [players].
 *
 * Nothing is registered in [components], and no media is read, decoded or written.
 *
 * @param scope The test's scope, whose scheduler is the clock of every player [preview] makes.
 */
public class FakeFilmstrip(
  private val scope: TestScope,
) : Filmstrip {
  private val recorded = mutableListOf<FilmstripCall>()
  private val driven = mutableListOf<FakeExport>()
  private val opened = mutableListOf<FakeVideoPlayer>()
  private val turn = Mutex()

  override val components: ComponentRegistry = ComponentRegistry.Builder().build()

  /**
   * Every call made on the fake so far, oldest first.
   */
  public val calls: List<FilmstripCall> get() = recorded.toList()

  /**
   * The handle of every export that was asked for, oldest first.
   *
   * An export of a composition that [onPlan] answers with [Verdict.Incapable] fails by itself and has no handle.
   */
  public val exports: List<FakeExport> get() = driven.toList()

  /**
   * Every player [preview] handed out, oldest first.
   */
  public val players: List<FakeVideoPlayer> get() = opened.toList()

  /**
   * Answers each `probe`.
   */
  public var onProbe: (source: MediaSource) -> ProbeResult = { ProbeResult.Success(testMediaInfo()) }

  /**
   * Answers each `capabilities`.
   */
  public var onCapabilities: () -> CapabilitiesResult = { CapabilitiesResult.Success(testDeviceCapabilities()) }

  /**
   * Answers each `plan`, and decides how an export of a composition goes.
   */
  public var onPlan: (composition: Composition, spec: ExportSpec) -> Verdict =
    { composition, spec -> Verdict.Capable(testExportPlan(composition, spec)) }

  /**
   * Answers each `parityOf`.
   */
  public var onParityOf: (specId: EffectId) -> EffectParity? = { EffectParity.Exact }

  /**
   * Answers each `frame`, and each position `frames` is asked for.
   *
   * The default makes a picture with [testFrame], which the Android host cannot do without a runtime that has a real
   * `Bitmap`. A test that runs there answers with a [FrameResult.Failure] or brings such a runtime.
   */
  public var onFrame: (composition: Composition, at: Duration) -> FrameResult = { _, at -> testFrame(at) }

  /**
   * Makes the player each `preview` hands out.
   *
   * A test that wants a player it already holds returns it from here, and one that wants a failed preview makes a
   * player and calls [FakeVideoPlayer.fail] on it before returning it.
   */
  public var onPreview: (composition: Composition, config: PlayerConfig) -> FakeVideoPlayer =
    { composition, _ -> FakeVideoPlayer(scope, composition) }

  override suspend fun probe(source: MediaSource): ProbeResult {
    recorded += FilmstripCall.Probe(source)
    return onProbe(source)
  }

  override suspend fun capabilities(): CapabilitiesResult {
    recorded += FilmstripCall.Capabilities
    return onCapabilities()
  }

  override suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict {
    recorded += FilmstripCall.Plan(composition, spec)
    return onPlan(composition, spec)
  }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> {
    recorded += FilmstripCall.ExportOfPlan(plan, to)
    return drive(plan, to, opening = emptyList())
  }

  override fun export(
    composition: Composition,
    spec: ExportSpec,
    to: MediaSink,
  ): Flow<ExportStatus> {
    recorded += FilmstripCall.ExportOfComposition(composition, spec, to)
    return when (val verdict = onPlan(composition, spec)) {
      is Verdict.Capable -> drive(verdict.plan, to, opening = emptyList())
      is Verdict.Degraded -> drive(verdict.plan, to, opening = listOf(ExportStatus.Adjusted(verdict.adjustments)))
      is Verdict.Incapable -> flow { emit(ExportStatus.Failure(verdict.reasons)) }
    }
  }

  override fun parityOf(specId: EffectId): EffectParity? {
    recorded += FilmstripCall.ParityOf(specId)
    return onParityOf(specId)
  }

  override suspend fun frame(
    composition: Composition,
    at: Duration,
    heightPx: Int?,
    exportSpec: ExportSpec,
  ): FrameResult {
    recorded += FilmstripCall.Frame(composition, at, heightPx, exportSpec)
    outputHeightRefusal(heightPx)?.let { return FrameResult.Failure(it) }
    return onFrame(composition, at)
  }

  override fun frames(
    composition: Composition,
    at: List<Duration>,
    heightPx: Int?,
    exportSpec: ExportSpec,
  ): Flow<FrameResult> {
    recorded += FilmstripCall.Frames(composition, at, heightPx, exportSpec)
    val refusal = outputHeightRefusal(heightPx)
    return at.asFlow().map { if (refusal != null) FrameResult.Failure(refusal) else onFrame(composition, it) }
  }

  override fun preview(
    composition: Composition,
    config: PlayerConfig,
  ): VideoPlayer {
    recorded += FilmstripCall.Preview(composition, config)
    return onPreview(composition, config).also { opened += it }
  }

  private fun drive(
    plan: ExportPlan,
    to: MediaSink,
    opening: List<ExportStatus>,
  ): Flow<ExportStatus> = FakeExport(plan, to, turn, opening).also { driven += it }.flow
}
