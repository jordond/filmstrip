package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngineFactory
import dev.jordond.filmstrip.player.PreviewFactory
import dev.jordond.filmstrip.still.FrameRenderer
import dev.jordond.filmstrip.still.StillResult
import dev.jordond.filmstrip.still.StillSpec
import dev.jordond.filmstrip.still.still
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailSourceFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFilmstripTest {
  @Test
  fun withNoEngineEveryExportCallNamesThisTargetsBackendAndTheFactoriesItAsked() =
    runTest {
      val filmstrip = filmstripOf(ExportEngineFactory { null }, ExportEngineFactory { null })

      val capabilities = filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>().error
      val plan = filmstrip.plan(COMPOSITION, SPEC).shouldBeInstanceOf<Verdict.Incapable>()
      val planned = filmstrip.export(planOf(COMPOSITION), SINK).toList()
      val direct = filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      for (error in listOf(capabilities, plan.reasons.single(), failureOf(planned), failureOf(direct))) {
        val missing = error.shouldBeInstanceOf<FilmstripError.BackendMissing>()
        missing.artifact shouldBe EXPORT_BACKEND.artifact
        withClue(missing.message) { (EXPORT_BACKEND.registration in missing.message).shouldBeTrue() }
        withClue(missing.message) { ("Registered export engine factories: 2." in missing.message).shouldBeTrue() }
      }
      ("`plan`" in plan.reasons.single().message).shouldBeTrue()
      plan.withoutUnsupported.shouldBeNull()
      filmstrip.parityOf("blur").shouldBeNull()
    }

  @Test
  fun theFirstFactoryThatDoesNotDeclineBuildsTheEngineAndOnlyOnce() =
    runTest {
      val chosen = FakeExportEngine(Verdict.Capable(planOf(COMPOSITION)))
      val behind = FakeExportEngine(Verdict.Capable(planOf(COMPOSITION)))
      var declinerAsked = 0
      var chosenAsked = 0
      var behindAsked = 0
      val filmstrip =
        filmstripOf(
          ExportEngineFactory {
            declinerAsked++
            null
          },
          ExportEngineFactory {
            chosenAsked++
            chosen
          },
          ExportEngineFactory {
            behindAsked++
            behind
          },
        )

      filmstrip.capabilities()
      filmstrip.plan(COMPOSITION, SPEC)
      filmstrip.parityOf("blur")

      listOf(declinerAsked, chosenAsked, behindAsked) shouldBe listOf(1, 1, 0)
      chosen.calls shouldBe listOf("capabilities", "plan", "parity blur")
      behind.calls shouldBe emptyList()
    }

  @Test
  fun aCapablePlanRunsAsTheEngineReportsIt() =
    runTest {
      val plan = planOf(COMPOSITION)
      val engine = FakeExportEngine(Verdict.Capable(plan))
      val filmstrip = filmstripOf(ExportEngineFactory { engine })

      val statuses = filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      statuses shouldBe engine.statuses
      engine.exported.single() shouldBeSameInstanceAs plan
    }

  @Test
  fun aDegradedPlanReportsItsAdjustmentsBeforeAnyProgress() =
    runTest {
      val plan = planOf(COMPOSITION)
      val adjustments = listOf(Adjustment(AdjustmentKind.CodecFallback, "hevc", "h264", "No HEVC encoder."))
      val engine = FakeExportEngine(Verdict.Degraded(plan, adjustments))
      val filmstrip = filmstripOf(ExportEngineFactory { engine })

      val statuses = filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      statuses shouldBe listOf<ExportStatus>(ExportStatus.Adjusted(adjustments)) + engine.statuses
      engine.exported.single() shouldBeSameInstanceAs plan
    }

  @Test
  fun anIncapablePlanFailsWithEveryReasonAndRunsNothing() =
    runTest {
      val first = FilmstripError.InvalidComposition("The composition has no tracks.")
      val second = FilmstripError.InvalidComposition("And no duration either.")
      val engine = FakeExportEngine(Verdict.Incapable(listOf(first, second), withoutUnsupported = null))
      val filmstrip = filmstripOf(ExportEngineFactory { engine })

      val statuses = filmstrip.export(COMPOSITION, SPEC, SINK).toList()

      val failure = statuses.single().shouldBeInstanceOf<ExportStatus.Failure>()
      failure.reasons shouldBe listOf(first, second)
      failure.error shouldBe first
      engine.exported shouldBe emptyList()
    }

  @Test
  fun aFailureWithOneReasonReadsItAsItsError() {
    val error = FilmstripError.InvalidComposition("Stopped.")

    ExportStatus.Failure(error).reasons shouldBe listOf<FilmstripError>(error)
    ExportStatus.Failure(error) shouldBe ExportStatus.Failure(listOf(error))
    assertFailsWith<IllegalArgumentException> { ExportStatus.Failure(emptyList()) }
  }

  @Test
  fun aDegradedVerdictWithNoAdjustmentsIsRefused() {
    assertFailsWith<IllegalArgumentException> { Verdict.Degraded(planOf(COMPOSITION), emptyList()) }
  }

  @Test
  fun aPlanHandedInRunsWithoutBeingPlannedAgain() =
    runTest {
      val plan = planOf(COMPOSITION)
      val engine = FakeExportEngine(Verdict.Capable(plan))
      val filmstrip = filmstripOf(ExportEngineFactory { engine })

      val statuses = filmstrip.export(plan, SINK).toList()

      statuses shouldBe engine.statuses
      engine.calls shouldBe listOf("export")
    }

  // Two instances, because the encoder sessions the lock protects are shared by every instance in
  // the process rather than by one.
  @Test
  fun aSecondExportDoesNotStartUntilTheFirstHasFinished() =
    runTest {
      val gate = CompletableDeferred<Unit>()
      val heard = mutableListOf<String>()
      val first = filmstripOf(ExportEngineFactory { GatedExportEngine(gate) })
      val second = filmstripOf(ExportEngineFactory { GatedExportEngine(null) })

      val running = launch { first.export(planOf(COMPOSITION), SINK).collect { heard += "first ${it.label}" } }
      runCurrent()
      val queued = launch { second.export(planOf(COMPOSITION), SINK).collect { heard += "second ${it.label}" } }
      runCurrent()

      heard shouldBe listOf("first started")
      gate.complete(Unit)
      joinAll(running, queued)

      heard shouldBe listOf("first started", "first finished", "second started", "second finished")
    }

  // The engine runs the plan with the dropped effects already gone, so only the plan knows they
  // were ever there.
  @Test
  fun aPlanWithDroppedEffectsReportsThemWhenItSucceeds() =
    runTest {
      val fallback = Adjustment(AdjustmentKind.CodecFallback, "hevc", "h264", "No HEVC encoder.")
      val dropped = Adjustment(AdjustmentKind.EffectDropped, "rotate", "removed", "rotate cannot run here.")
      val engine = SucceedingExportEngine(listOf(fallback))
      val filmstrip = filmstripOf(ExportEngineFactory { engine })

      val statuses = filmstrip.export(planOf(COMPOSITION, droppedEffects = listOf(dropped)), SINK).toList()

      statuses.last().shouldBeInstanceOf<ExportStatus.Success>().adjustments shouldBe listOf(fallback, dropped)
    }

  @Test
  fun parityIsTheEnginesAnswer() {
    val engine = FakeExportEngine(Verdict.Capable(planOf(COMPOSITION)), parity = EffectParity.Approximate)
    val filmstrip = filmstripOf(ExportEngineFactory { engine })

    filmstrip.parityOf("blur") shouldBe EffectParity.Approximate
  }

  // One frame is a poster and has to be exact, a strip is a run where a sync sample will do.
  @Test
  fun asAFrameRendererAFrameIsPreciseAtTheOutputHeightAndAStripIsNot() =
    runTest {
      val asked = mutableListOf<ThumbnailRequest>()
      val renderer: FrameRenderer = filmstripOf(thumbnails = failingSource(asked))

      renderer.frame(COMPOSITION, 1.seconds)
      renderer.frames(COMPOSITION, listOf(0.milliseconds, 500.milliseconds), heightPx = 90).toList()

      asked.map { it.precise } shouldBe listOf(true, false, false)
      asked.map { it.heightPx } shouldBe listOf(null, 90, 90)
      asked.map { it.position } shouldBe listOf(1.seconds, 0.milliseconds, 500.milliseconds)
    }

  @Test
  fun aHeightOfZeroOrLessFailsAFrameAndAStripWithoutAskingTheSource() =
    runTest {
      val asked = mutableListOf<ThumbnailRequest>()
      val renderer: FrameRenderer = filmstripOf(thumbnails = failingSource(asked))

      val frame = renderer.frame(COMPOSITION, 1.seconds, heightPx = 0)
      val strip = renderer.frames(COMPOSITION, listOf(0.milliseconds, 500.milliseconds), heightPx = -90).toList()

      frame.shouldBeInstanceOf<FrameResult.Failure>().error.shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      strip.size shouldBe 2
      strip.forEach {
        it.shouldBeInstanceOf<FrameResult.Failure>().error.shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      }
      asked.isEmpty().shouldBeTrue()
    }

  @Test
  fun aStillAskedForAtANegativeHeightFailsWithoutAskingTheSource() =
    runTest {
      val asked = mutableListOf<ThumbnailRequest>()
      val renderer: FrameRenderer = filmstripOf(thumbnails = failingSource(asked))

      val result = renderer.still(COMPOSITION, 1.seconds, MediaSink.Temporary, StillSpec(heightPx = -1))

      result.shouldBeInstanceOf<StillResult.Failure>().error.shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      asked.isEmpty().shouldBeTrue()
    }

  @Test
  fun aFrameTheSourceCouldNotProduceComesBackAsItsFailure() =
    runTest {
      val renderer: FrameRenderer = filmstripOf(thumbnails = failingSource(mutableListOf()))

      val result = renderer.frame(COMPOSITION, 1.seconds)

      result.shouldBeInstanceOf<FrameResult.Failure>().error shouldBeSameInstanceAs SOURCE_FAILURE
    }

  @Test
  fun asAPreviewFactoryAPlayerIsBuiltWithTheDefaultConfigAndLoadsTheComposition() {
    val engine = FakePlayerEngine()
    val configs = mutableListOf<PlayerConfig>()
    val registry =
      ComponentRegistry
        .Builder()
        .addPlayerEngineFactory(
          PlayerEngineFactory { config, _ ->
            configs += config
            engine
          },
        ).build()
    val previews: PreviewFactory = DefaultFilmstrip(registry)

    previews.preview(COMPOSITION).close()

    configs shouldBe listOf(PlayerConfig())
    engine.requests.single().composition shouldBeSameInstanceAs COMPOSITION
    engine.disposed.shouldBeTrue()
  }

  @Test
  fun withNoPlayerEngineAPreviewReportsTheMissingBackendOnItsState() {
    val previews: PreviewFactory = filmstripOf()

    val status =
      previews
        .preview(COMPOSITION)
        .state.value.status
        .shouldBeInstanceOf<PlaybackStatus.Error>()

    val missing = status.error.shouldBeInstanceOf<PlaybackError.BackendMissing>()
    missing.artifact shouldBe "dev.jordond.filmstrip:filmstrip-player"
    withClue(missing.message) { ("playerBackend()" in missing.message).shouldBeTrue() }
  }

  private fun filmstripOf(
    vararg engines: ExportEngineFactory,
    thumbnails: ThumbnailSource? = null,
  ): DefaultFilmstrip {
    val builder = ComponentRegistry.Builder()
    engines.reversed().forEach { builder.addExportEngineFactory(it) }
    thumbnails?.let { source -> builder.addThumbnailSourceFactory(ThumbnailSourceFactory { _, _ -> source }) }
    return DefaultFilmstrip(builder.build())
  }

  private fun failingSource(asked: MutableList<ThumbnailRequest>): ThumbnailSource =
    ThumbnailSource { request, callback ->
      asked += request
      callback.onThumbnail(ThumbnailResult.Failure(SOURCE_FAILURE))
      Cancellable { }
    }

  private fun failureOf(statuses: List<ExportStatus>): FilmstripError =
    statuses.single().shouldBeInstanceOf<ExportStatus.Failure>().error

  private companion object {
    val COMPOSITION: Composition = compositionOf { clip(MediaSource.of("/fixtures/clip.mp4")) }

    val SPEC = ExportSpec(heightPx = 720)

    val SINK: MediaSink = MediaSink.of("/out/clip.mp4")

    val SOURCE_FAILURE = FilmstripError.SourceUnreadable("/fixtures/clip.mp4", "The decoder went away.")

    fun planOf(
      composition: Composition,
      droppedEffects: List<Adjustment> = emptyList(),
    ): ExportPlan =
      ExportPlan(
        path = ExportPath.Transcode,
        output = OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.Aac, null, 30, null),
        effectOrder = emptyList(),
        estimate = ExportEstimate(null, null, null, isCopy = false),
        parity = EffectParity.Exact,
        duration = 4.seconds,
        copyBlockedBy = emptyList(),
        composition = composition,
        spec = SPEC,
        droppedEffects = droppedEffects,
      )

    val ExportStatus.label: String
      get() = if (this is ExportStatus.Finished) "finished" else "started"
  }
}

/**
 * An engine that answers every plan with [verdict] and every run with the same three statuses, recording what it was
 * asked in order.
 */
private class FakeExportEngine(
  private val verdict: Verdict,
  private val parity: EffectParity? = null,
) : ExportEngine {
  val calls: MutableList<String> = mutableListOf()
  val exported: MutableList<ExportPlan> = mutableListOf()
  val statuses: List<ExportStatus> =
    listOf(
      ExportStatus.Started,
      ExportStatus.Progress(0.5f, 2.seconds, null),
      ExportStatus.Failure(FilmstripError.InvalidComposition("Stopped at the half way mark.")),
    )

  override suspend fun capabilities(): CapabilitiesResult {
    calls += "capabilities"
    return CapabilitiesResult.Failure(FilmstripError.InvalidComposition("Not asked."))
  }

  override suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict {
    calls += "plan"
    return verdict
  }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> {
    calls += "export"
    exported += plan
    return flowOf(*statuses.toTypedArray())
  }

  override fun parityOf(specId: String): EffectParity? {
    calls += "parity $specId"
    return parity
  }
}

/**
 * An engine whose run starts at once and finishes only when [gate] opens, or straight away without one.
 */
private class GatedExportEngine(
  private val gate: CompletableDeferred<Unit>?,
) : ExportEngine by SucceedingExportEngine(emptyList()) {
  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      emit(ExportStatus.Started)
      gate?.await()
      emit(ExportStatus.Failure(FilmstripError.InvalidComposition("Stopped once the gate opened.")))
    }
}

/**
 * An engine whose every run succeeds, reporting [adjustments] as what it changed.
 */
private class SucceedingExportEngine(
  private val adjustments: List<Adjustment>,
) : ExportEngine {
  override suspend fun capabilities(): CapabilitiesResult =
    CapabilitiesResult.Failure(FilmstripError.InvalidComposition("Not asked."))

  override suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict = Verdict.Incapable(emptyList(), withoutUnsupported = null)

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flowOf(
      ExportStatus.Started,
      ExportStatus.Success(to, MediaInfo(4.seconds, null, null, isExportable = true), adjustments),
    )

  override fun parityOf(specId: String): EffectParity? = null
}
