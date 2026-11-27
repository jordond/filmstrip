package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.compose.overlay.OVERLAY_LAYOUT_DENSITY
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCaptureHost
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.boundsOn
import dev.jordond.filmstrip.compose.overlay.rememberOverlayCapturer
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
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.stories.ui.exportLine
import dev.jordond.filmstrip.sample.ui.SampleTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a story's export bakes and how it folds the baked elements into the plan.
 */
@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class StoryExportTest {
  @Test
  fun aCaptionAStickerAndAStrokeBakeToThreeOverlaysInDrawOrderWhereBoundsOnPutsThem(): TestResult =
    runComposeUiTest {
      val placed = OverlayTransform(Anchor(0.3f, 0.4f), scale = 1.5f, rotationDegrees = 30f)
      val elements =
        listOf(
          StoryElement(0, StoryContent.Caption("Hello"), OverlayTransform(Anchor(0.5f, 0.2f))),
          StoryElement(1, StoryContent.Sticker(StoryStickerKind.Heart), placed),
        )
      val strokes = listOf(StoryStroke(listOf(Anchor(0.2f, 0.8f), Anchor(0.8f, 0.8f))))

      val baked = bake(elements, strokes)

      baked shouldHaveSize 3
      val (drawing, caption, sticker) = baked
      drawing.position shouldBe OverlayPosition.At(Anchor.Center)
      drawing.scale shouldBe 1f
      caption.position
        .shouldBeInstanceOf<OverlayPosition.At>()
        .center.x shouldBe 0.5f
      val side = (STICKER_DP * OVERLAY_LAYOUT_DENSITY).toInt()
      val bounds = placed.boundsOn(IntSize(side, side), CANVAS, OUTPUT).shouldNotBeNull()
      sticker.position shouldBe OverlayPosition.At(bounds.center)
      sticker.scale shouldBe bounds.raster.width.toFloat() / OUTPUT.width
    }

  @Test
  fun aTimedCaptionCarriesItsWindowAndItsEntrance(): TestResult =
    runComposeUiTest {
      val timed =
        StoryElement(
          id = 0,
          content = StoryContent.Caption("Later", entrance = StoryEntrance.Rise),
          timed = true,
          startSeconds = 1.5f,
          endSeconds = 3f,
        )

      val overlay = bake(listOf(timed), emptyList()).single()

      overlay.visibleDuring shouldBe timed.window.shouldNotBeNull()
      overlay.animation shouldBe StoryEntrance.Rise.animation
    }

  @Test
  fun anElementOffTheFrameAndAnEmptyDrawingAreLeftOut(): TestResult =
    runComposeUiTest {
      val off = StoryElement(0, StoryContent.Sticker(StoryStickerKind.Star), OverlayTransform(Anchor(3f, 3f)))
      val on = StoryElement(1, StoryContent.Sticker(StoryStickerKind.Star))

      val baked = bake(listOf(off, on), emptyList())

      baked shouldHaveSize 1
      baked.single().visibleDuring.shouldBeNull()
      baked.single().animation.shouldBeNull()
    }

  @Test
  fun theStoryBakesOnTheFrameTheFirstPlanNamesAndThePlanIsAskedAgainWithIt() =
    runTest {
      val engine = PlanningEngine()
      val bakedOn = mutableListOf<Size>()

      val verdict = planStory(filmstrip(engine), EDIT) { output -> listOf(BAKED).also { bakedOn += output } }

      bakedOn shouldContainExactly listOf(OUTPUT)
      engine.asked.map { it.effects } shouldContainExactly listOf(EDIT.effects, EDIT.effects + BAKED)
      engine.specs.toSet() shouldBe setOf(STORY_SPEC)
      verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.composition.effects shouldBe EDIT.effects + BAKED
    }

  @Test
  fun aStoryWithNothingToBakeKeepsTheFirstPlan() =
    runTest {
      val engine = PlanningEngine()

      planStory(filmstrip(engine), EDIT) { emptyList() }

      engine.asked shouldHaveSize 1
    }

  @Test
  fun theStoryIsExportedNoTallerThanItsMaximumAndTheSheetSaysHowFarAlongItIs() {
    STORY_SPEC shouldBe ExportSpec(heightPx = STORY_MAX_HEIGHT)
    exportLine(ExportPhase.Running(0.5f)) shouldContain "50%"
    exportLine(ExportPhase.Failed("No encoder")) shouldBe "No encoder"
  }

  private suspend fun ComposeUiTest.bake(
    elements: List<StoryElement>,
    strokes: List<StoryStroke>,
  ): List<ImageOverlay> =
    coroutineScope {
      lateinit var capturer: OverlayCapturer
      setContent {
        SampleTheme {
          capturer = rememberOverlayCapturer()
          OverlayCaptureHost(capturer)
        }
      }
      awaitIdle()
      val work = async { bakeStory(capturer, elements, strokes, CANVAS, OUTPUT) }
      while (!work.isCompleted) {
        mainClock.advanceTimeByFrame()
        awaitIdle()
        withContext(Dispatchers.Default) { delay(1) }
      }
      work.await()
    }

  private fun filmstrip(engine: PlanningEngine): Filmstrip =
    Filmstrip { autoRegister(false).addExportEngineFactory { engine } }

  private class PlanningEngine : ExportEngine {
    val asked = mutableListOf<Composition>()
    val specs = mutableListOf<ExportSpec>()

    override suspend fun capabilities(): CapabilitiesResult = fail("The test asks for no capabilities.")

    override suspend fun plan(
      composition: Composition,
      spec: ExportSpec,
    ): Verdict {
      asked += composition
      specs += spec
      return Verdict.Capable(planFor(composition, spec))
    }

    override fun export(
      plan: ExportPlan,
      to: MediaSink,
    ): Flow<ExportStatus> = fail("The test only plans.")

    override fun parityOf(specId: EffectId): EffectParity? = null
  }

  private companion object {
    val OUTPUT = Size(360, 640)
    val CANVAS = OverlayCanvas()
    const val STICKER_DP = 72
    val EDIT = storyComposition(MediaSource.of("/tmp/story.mp4"), StoryLook.Mono)
    val BAKED: EffectSpec = ImageOverlay(ImageSource.ofBytes(ByteArray(1)), OverlayPosition.At(Anchor(0.3f, 0.6f)))

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
