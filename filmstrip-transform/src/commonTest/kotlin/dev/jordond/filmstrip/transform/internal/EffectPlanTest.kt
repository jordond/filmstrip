package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner resolves effects and the output frame they leave behind.
 *
 * Covers unclaimed and refused effects, rotation and scale reshaping the frame, folded runs of stacked effects, the
 * order clip and composition geometry run in, and where clip-only effects are refused.
 */
class EffectPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `an unclaimed effect is refused by id and offers a plan without it`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(Rotate(90)))), resolvers = emptyList())

    val incapable = verdict.shouldBeInstanceOf<Verdict.Incapable>()
    incapable.reasons
      .single()
      .shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
      .specId shouldBe Rotate(90).id
    val fallback = incapable.withoutUnsupported.shouldNotBeNull()
    fallback.plan.effectOrder shouldBe emptyList()
    fallback.adjustments.map { it.kind to it.requested } shouldBe listOf(AdjustmentKind.EffectDropped to Rotate(90).id)
  }

  @Test
  fun `a claimed but refused effect keeps the resolver's message`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(effects = listOf(Rotate(90)))),
        resolvers = listOf(FakeResolver(refuse = setOf(Rotate(90).id))),
      )

    val incapable = verdict.shouldBeInstanceOf<Verdict.Incapable>()
    incapable.reasons.single().message shouldBe "this resolver owns it and cannot render it"
  }

  @Test
  fun `an effect degraded for three causes reports each as its own approximation in order`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(effects = listOf(Rotate(90)))),
        resolvers = listOf(FakeResolver(degrade = setOf(Rotate(90).id), causes = THREE_CAUSES)),
      )

    val adjustments = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments
    val approximated = adjustments.filter { it.kind == AdjustmentKind.EffectApproximated }
    approximated.map { it.requested to it.message } shouldBe THREE_CAUSES.map { Rotate(90).id to it.message }
  }

  // Every effect the caller wrote in the run is told about every cause, each source in turn.
  @Test
  fun `a folded run degraded for three causes reports each cause against every effect the caller wrote`() {
    val composition = composition(fixture.clip(effects = listOf(Brightness(4f), Contrast(2f), Sepia(1f))))
    val resolver = FakeResolver(degrade = setOf(EffectIds.COLOR_MATRIX), causes = THREE_CAUSES)
    val verdict =
      fixture
        .planner(resolvers = listOf(resolver))
        .negotiate(composition, Ask(ExportSpec(), fixture.device(), fixture.infos(composition)))
        .verdict

    val adjustments = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments
    val approximated = adjustments.filter { it.kind == AdjustmentKind.EffectApproximated }
    approximated.map { it.requested to it.message } shouldBe
      listOf(EffectIds.BRIGHTNESS, EffectIds.CONTRAST, EffectIds.SEPIA).flatMap { id ->
        THREE_CAUSES.map { id to it.message }
      }
  }

  // A track effect lowers once per clip, so each cause is reached on both clips and reported once.
  @Test
  fun `three causes reached on every clip are each reported once`() {
    val track = Track(listOf(fixture.clip(), fixture.clip()), effects = listOf(Rotate(90)))
    val verdict =
      fixture.plan(
        EditComposition(listOf(track)),
        resolvers = listOf(FakeResolver(degrade = setOf(Rotate(90).id), causes = THREE_CAUSES)),
      )

    val adjustments = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments
    val approximated = adjustments.filter { it.kind == AdjustmentKind.EffectApproximated }
    approximated.map { it.message } shouldBe THREE_CAUSES.map { it.message }
  }

  @Test
  fun `a rotation swaps the output frame`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(Rotate(90)))))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(1080, 1920)
  }

  // 1920x1080 at 480 high is 853.3 wide, and an encoder that wants even sides cannot have 853.
  @Test
  fun `a scale sets the output height and the alignment rounding is reported`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(Scale(heightPx = 480)))))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.plan.output.size shouldBe Size(852, 480)
    degraded.adjustments.single().kind shouldBe AdjustmentKind.ResolutionClamped
  }

  // The crop's target aspect equals the output frame's own aspect, so measuring it against the
  // output rather than the frame clip geometry leaves behind would see no aspect mismatch and crop
  // nothing.
  @Test
  fun `composition geometry resolves against the frame clip geometry leaves behind`() {
    val recorder = RecordingResolver()
    val crop = Crop(aspect = AspectRatio.Square, fit = Fit.Crop)
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(fixture.clip(effects = listOf(Rotate(90)))))),
        effects = listOf(crop),
      )

    val resolved = fixture.resolve(composition, resolvers = listOf(recorder))

    recorder.inputSizes.getValue(crop.id) shouldBe Size(1080, 1920)
    resolved.output.size shouldBe Size(1080, 1080)
  }

  // Where a run of brightnesses stops being several multiplies. Backends disagree about where they
  // clamp, so a backend only ever sees the product.
  @Test
  fun `stacked brightnesses reach a resolver as one`() {
    val recorder = RecordingResolver()
    val composition = composition(fixture.clip(effects = listOf(Brightness(4f), Brightness(0.25f))))

    fixture.resolve(composition, resolvers = listOf(recorder))

    recorder.specs.filterIsInstance<Brightness>().map { it.factor } shouldBe listOf(1f)
  }

  // A resolver registered after the catalogue outranks it for the effects the caller wrote, and the
  // fold would hand it one ColorMatrix it never asked for instead. The run folds around what it
  // claims rather than swallowing the claim.
  @Test
  fun `a resolver ahead of the catalogue is asked for the effects the caller wrote`() {
    val recorder = RecordingResolver()
    val composition = composition(fixture.clip(effects = listOf(Brightness(4f), Sepia(1f))))

    fixture.resolve(composition, resolvers = listOf(recorder, BuiltInEffectResolver()))

    recorder.specs.map { it.id } shouldBe listOf(EffectIds.BRIGHTNESS, EffectIds.SEPIA)
  }

  // The other order: registered after the catalogue, it never wins for those effects anyway, so the
  // run folds the way it does for every backend. Rendered with Software, which no target's catalogue
  // claims, so the folded run falls through to the recorder on every target. Android's catalogue
  // would claim it under OpenGlEs and the recorder would never be asked.
  @Test
  fun `a resolver behind the catalogue still sees the folded run`() {
    val recorder = RecordingResolver()
    val composition = composition(fixture.clip(effects = listOf(Brightness(4f), Sepia(1f))))

    fixture.resolve(composition, resolvers = listOf(BuiltInEffectResolver(), recorder), renderApi = RenderApi.Software)

    recorder.specs.map { it.id } shouldBe listOf(EffectIds.COLOR_MATRIX)
  }

  // Nothing claimed the folded run, and the id the fold made up is not one the caller ever wrote.
  @Test
  fun `an unclaimed folded run is reported against the effects the caller wrote`() {
    val composition = composition(fixture.clip(effects = listOf(Brightness(4f), Sepia(1f))))
    val verdict =
      fixture
        .planner(resolvers = listOf(FakeResolver(declines = setOf(EffectIds.COLOR_MATRIX))))
        .negotiate(composition, Ask(ExportSpec(), fixture.device(), fixture.infos(composition)))
        .verdict

    verdict.shouldBeInstanceOf<Verdict.Incapable>()
    verdict.reasons.map { (it as FilmstripError.UnsupportedEffect).specId } shouldBe
      listOf(EffectIds.BRIGHTNESS, EffectIds.SEPIA)
    verdict.reasons.forEach { reason ->
      val message = (reason as FilmstripError.UnsupportedEffect).message
      withClue("the message reads $message") { message.contains(EffectIds.BRIGHTNESS).shouldBeTrue() }
      withClue("the message reads $message") { message.contains(EffectIds.SEPIA).shouldBeTrue() }
      withClue("the message names the id the fold made up: $message") {
        (!message.contains(EffectIds.COLOR_MATRIX)).shouldBeTrue()
      }
    }
  }

  // The middle of the range a composition crop can see: no clip geometry runs first, so the frame
  // it measures against is the source frame itself.
  @Test
  fun `composition geometry resolves against the source frame when there is no clip geometry`() {
    val recorder = RecordingResolver()
    val crop = Crop(aspect = AspectRatio.Classic, fit = Fit.Crop)
    val composition = EditComposition(listOf(Track(listOf(fixture.clip()))), effects = listOf(crop))

    val resolved = fixture.resolve(composition, resolvers = listOf(recorder))

    recorder.inputSizes.getValue(crop.id) shouldBe Size(1920, 1080)
    resolved.output.size shouldBe Size(1440, 1080)
  }

  @Test
  fun `compositionInputSize is the frame before composition geometry runs and neither the source nor the output`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(fixture.clip(effects = listOf(Rotate(90)))))),
        effects = listOf(Crop(aspect = AspectRatio.Square, fit = Fit.Crop)),
      )

    val resolved = fixture.resolve(composition, ExportSpec(heightPx = 540))

    resolved.compositionInputSize shouldBe Size(1080, 1920)
    resolved.output.size shouldBe Size(540, 540)
  }

  // Clip geometry always finishes before the composition's own runs. Sorting a composition-level
  // Rotate ahead of a clip-level Scale by rank alone is an order no backend ever runs, and lands on
  // a different frame.
  @Test
  fun `composition geometry runs after clip geometry rather than by merged rank`() {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(fixture.clip(effects = listOf(Scale(heightPx = 540)))))),
        effects = listOf(Rotate(90)),
      )

    val verdict = fixture.plan(composition)

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(540, 960)
  }

  // CropRect resolves eagerly against spec.rect and never reads attributes.inputSize. This pins
  // that it still works once geometry is staged.
  @Test
  fun `a composition-level crop rect still sets the output frame`() {
    val rect = NormalizedRect(left = 0f, top = 0f, right = 0.5f, bottom = 1f)
    val composition = EditComposition(listOf(Track(listOf(fixture.clip()))), effects = listOf(CropRect(rect)))

    val verdict = fixture.plan(composition)

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.size shouldBe Size(960, 1080)
  }

  @Test
  fun `a clip-only effect plans capable on a clip`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(PUSH_IN))))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.effectOrder
      .map { it.spec.id } shouldBe
      listOf(EffectIds.KEN_BURNS)
  }

  // The effect is defined on geometry rather than on stills, and a slow push over footage is an
  // ordinary edit, so a video clip takes one on the same terms a photo does.
  @Test
  fun `a clip-only effect plans capable over a video clip and over a photo alike`() {
    val over = composition(fixture.clip(effects = listOf(PUSH_IN)), fixture.imageClip().withEffects(listOf(PUSH_IN)))

    val plan = fixture.plan(over).shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Transcode
    plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.KEN_BURNS)
  }

  @Test
  fun `a clip-only effect declared on a track is refused by name`() {
    val verdict =
      fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), effects = listOf(PUSH_IN)))))

    val refused = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    refused.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().specId shouldBe EffectIds.KEN_BURNS
  }

  @Test
  fun `a clip-only effect declared on the composition is refused by name`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()))), effects = listOf(PUSH_IN)))

    val refused = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    refused.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().specId shouldBe EffectIds.KEN_BURNS
  }

  @Test
  fun `a clip-only effect on a looping track is refused by name`() {
    val verdict =
      fixture.plan(
        EditComposition(
          listOf(
            Track(listOf(fixture.clip(effects = listOf(PUSH_IN))), looping = true),
            Track(listOf(fixture.audioOnlyClip()), content = TrackContent.Audio),
          ),
        ),
      )

    val refused = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    refused.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().specId shouldBe EffectIds.KEN_BURNS
  }

  /**
   * The slot every backend measures a clip effect against, derived here and nowhere else.
   */
  @Test
  fun `each clip's effects are resolved against that clip's own slot on the timeline`() {
    val recording = RecordingResolver()
    val first = fixture.clip(duration = 2_000.milliseconds, trim = TimeRange.of(Duration.ZERO, 2_000.milliseconds))
    val second = fixture.imageClip(duration = 3_000.milliseconds)
    val third = fixture.clip(duration = 4_000.milliseconds, trim = TimeRange.of(Duration.ZERO, 4_000.milliseconds))
    val composition =
      EditComposition(
        listOf(
          Track(
            listOf(
              first.withEffects(listOf(Rotate(90))),
              second.withEffects(listOf(PUSH_IN)),
              third.withEffects(listOf(CropRect(NormalizedRect(0f, 0f, 0.5f, 1f)))),
            ),
            start = 1_000.milliseconds,
          ),
        ),
      )

    val resolved = fixture.resolve(composition, resolvers = listOf(recording))

    resolved.tracks
      .single()
      .clips
      .map { it.span } shouldBe
      listOf(
        TimeRange.of(1_000.milliseconds, 3_000.milliseconds),
        TimeRange.of(3_000.milliseconds, 6_000.milliseconds),
        TimeRange.of(6_000.milliseconds, 10_000.milliseconds),
      )
    recording.spans shouldBe
      resolved.tracks
        .single()
        .clips
        .map { it.span }
  }

  @Test
  fun `a composition-level effect is resolved against the whole composition`() {
    val recording = RecordingResolver()
    val composition =
      EditComposition(
        listOf(
          Track(
            listOf(fixture.clip(duration = 2_000.milliseconds, trim = TimeRange.of(Duration.ZERO, 2_000.milliseconds))),
          ),
        ),
        effects = listOf(Brightness(0.5f)),
      )

    fixture.resolve(composition, resolvers = listOf(recording))

    recording.spans.last() shouldBe TimeRange.of(Duration.ZERO, 2_000.milliseconds)
  }

  /**
   * A pan is what a big photo is used with, so it has to survive the clamp a photo-framed
   * composition is held to.
   */
  @Test
  fun `a pan over a photo survives the still frame clamp`() {
    val verdict = fixture.plan(composition(fixture.imageClip(size = Size(4032, 3024)).withEffects(listOf(PUSH_IN))))

    val degraded = verdict.shouldBeInstanceOf<Verdict.Degraded>()
    degraded.adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.ResolutionClamped)
    degraded.plan.output.size shouldBe Size(2880, 2160)
    degraded.plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.KEN_BURNS)
  }
}

// A push in from the whole frame to a centred window, which is the shape a pan is normally written
// as.
private val PUSH_IN =
  KenBurns(NormalizedRect.Full, NormalizedRect(0.15f, 0.15f, 0.85f, 0.85f), Easing.EaseInOut)

/**
 * Hands back opaque handles like [FakeResolver], and keeps the frame each spec was measured
 * against so a plan's geometry chain is assertable.
 */
private class RecordingResolver : EffectResolver {
  val inputSizes = mutableMapOf<String, Size>()
  val outputSizes = mutableMapOf<String, Size>()
  val spans = mutableListOf<TimeRange>()
  val specs = mutableListOf<EffectSpec>()

  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    specs += spec
    inputSizes[spec.id] = attributes.inputSize
    outputSizes[spec.id] = attributes.outputSize
    spans += attributes.span
    return EffectResolution.Resolved(fakePlatformEffect())
  }
}

// Sorted by neither reason nor message, so a planner that reorders the causes fails.
private val THREE_CAUSES = listOf(CONVERTS_IT, ROUNDS_IT, CLAMPS_IT)
