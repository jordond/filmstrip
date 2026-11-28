package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.compose.overlay.OverlayBaker
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * How the demo's export folds baked story elements into the plan.
 */
@OptIn(InternalFilmstripApi::class)
class StoryExportTest {
  @Test
  fun storiesBakeOnTheFrameTheFirstPlanNamesAndThePlanIsAskedAgainWithThem() {
    val engine = PlanningEngine()
    val bakedOn = mutableListOf<Size>()
    val session = session(engine) { output -> listOf(BAKED).also { bakedOn += output } }

    session.plan()

    bakedOn shouldContainExactly listOf(OUTPUT)
    engine.asked.map { it.effects } shouldContainExactly listOf(emptyList(), listOf<EffectSpec>(BAKED))
    session.verdict.shouldNotBeNull()
  }

  @Test
  fun aStoryThatFailsToBakeIsShownAsAPlanFailureRatherThanThrown() {
    val session = session(PlanningEngine()) { error(BAKE_FAILED) }

    session.plan()

    session.verdict.shouldBeNull()
    session.planFailure shouldBe BAKE_FAILED
    session.planning shouldBe false
  }

  @Test
  fun everyStoryChangeIsReportedSoThePlanBakedFromTheOldOnesIsDropped() {
    val stories = StoryOverlays()
    var changes = 0
    stories.onChange = { changes++ }

    stories.addCaption()
    stories.update(stories.elements.single().id) { it.copy(timed = true) }
    stories.remove(stories.elements.single().id)
    stories.clear()

    changes shouldBe 4
  }

  private fun session(
    engine: PlanningEngine,
    overlays: OverlayBaker,
  ): ExportSession =
    ExportSession(
      filmstrip = filmstrip(engine),
      scope = CoroutineScope(Dispatchers.Unconfined),
      recorder = DiagnosticsRecorder(),
      release = { },
      composition = { Composition(tracks = emptyList()) },
      spec = { ExportSpec() },
      overlays = overlays,
      onStart = { },
      onSucceeded = { },
    )

  private fun filmstrip(engine: PlanningEngine): Filmstrip =
    Filmstrip { autoRegister(false).addExportEngineFactory { engine } }

  private class PlanningEngine : ExportEngine {
    val asked = mutableListOf<Composition>()

    override suspend fun capabilities(): CapabilitiesResult = fail("The test asks for no capabilities.")

    override suspend fun plan(
      composition: Composition,
      spec: ExportSpec,
    ): Verdict {
      asked += composition
      return Verdict.Capable(planFor(composition, spec))
    }

    override fun export(
      plan: ExportPlan,
      to: MediaSink,
    ): Flow<ExportStatus> = fail("The test only plans.")

    override fun parityOf(specId: EffectId): EffectParity? = null
  }

  private companion object {
    val OUTPUT = Size(1280, 720)
    val BAKED = ImageOverlay(ImageSource.ofBytes(ByteArray(1)), OverlayPosition.At(Anchor(0.3f, 0.6f)))
    const val BAKE_FAILED = "The stage left composition before the bake finished."

    fun planFor(
      composition: Composition,
      spec: ExportSpec,
    ): ExportPlan =
      ExportPlan(
        path = ExportPath.Transcode,
        output = OutputFormat(OUTPUT, VideoCodec.H264, AudioCodec.Aac, null, 30, null),
        effectOrder = emptyList(),
        estimate = ExportEstimate(null, null, null, isCopy = false),
        parity = EffectParity.Exact,
        duration = 4.seconds,
        copyBlockedBy = emptyList(),
        composition = composition,
        spec = spec,
      )
  }
}
