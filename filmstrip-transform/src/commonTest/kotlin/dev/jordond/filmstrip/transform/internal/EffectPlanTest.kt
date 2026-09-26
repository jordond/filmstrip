package dev.jordond.filmstrip.transform.internal

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
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.motion.Easing
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    val incapable = assertIs<Verdict.Incapable>(verdict)
    assertIs<ExportError.UnsupportedEffect>(incapable.reasons.single()).specId shouldBe Rotate(90).id
    assertNotNull(incapable.withoutUnsupported).effectOrder shouldBe emptyList()
  }

  @Test
  fun `a claimed but refused effect keeps the resolver's message`() {
    val verdict =
      fixture.plan(
        composition(fixture.clip(effects = listOf(Rotate(90)))),
        resolvers = listOf(FakeResolver(refuse = setOf(Rotate(90).id))),
      )

    val incapable = assertIs<Verdict.Incapable>(verdict)
    incapable.reasons.single().message shouldBe "this resolver owns it and cannot render it"
  }

  @Test
  fun `a rotation swaps the output frame`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(Rotate(90)))))

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(1080, 1920)
  }

  // 1920x1080 at 480 high is 853.3 wide, and an encoder that wants even sides cannot have 853.
  @Test
  fun `a scale sets the output height and the alignment rounding is reported`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(Scale(targetHeight = 480)))))

    val degraded = assertIs<Verdict.Degraded>(verdict)
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
        .negotiate(composition, ExportSpec(), fixture.device(), fixture.infos(composition))
        .verdict

    assertIs<Verdict.Incapable>(verdict)
    verdict.reasons.map { (it as ExportError.UnsupportedEffect).specId } shouldBe
      listOf(EffectIds.BRIGHTNESS, EffectIds.SEPIA)
    verdict.reasons.forEach { reason ->
      val message = (reason as ExportError.UnsupportedEffect).message
      assertTrue(message.contains(EffectIds.BRIGHTNESS), "the message reads $message")
      assertTrue(message.contains(EffectIds.SEPIA), "the message reads $message")
      assertTrue(!message.contains(EffectIds.COLOR_MATRIX), "the message names the id the fold made up: $message")
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

    val resolved = fixture.resolve(composition, ExportSpec(targetHeight = 540))

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
        tracks = listOf(Track(listOf(fixture.clip(effects = listOf(Scale(targetHeight = 540)))))),
        effects = listOf(Rotate(90)),
      )

    val verdict = fixture.plan(composition)

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(540, 960)
  }

  // CropRect resolves eagerly against spec.rect and never reads attributes.inputSize. This pins
  // that it still works once geometry is staged.
  @Test
  fun `a composition-level crop rect still sets the output frame`() {
    val rect = NormalizedRect(left = 0f, top = 0f, right = 0.5f, bottom = 1f)
    val composition = EditComposition(listOf(Track(listOf(fixture.clip()))), effects = listOf(CropRect(rect)))

    val verdict = fixture.plan(composition)

    assertIs<Verdict.Capable>(verdict).plan.output.size shouldBe Size(960, 1080)
  }

  @Test
  fun `a clip-only effect plans capable on a clip`() {
    val verdict = fixture.plan(composition(fixture.clip(effects = listOf(PUSH_IN))))

    assertIs<Verdict.Capable>(verdict).plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.KEN_BURNS)
  }

  // The effect is defined on geometry rather than on stills, and a slow push over footage is an
  // ordinary edit, so a video clip takes one on the same terms a photo does.
  @Test
  fun `a clip-only effect plans capable over a video clip and over a photo alike`() {
    val over = composition(fixture.clip(effects = listOf(PUSH_IN)), fixture.imageClip().withEffects(listOf(PUSH_IN)))

    val plan = assertIs<Verdict.Capable>(fixture.plan(over)).plan
    plan.path shouldBe ExportPath.Transcode
    plan.effectOrder.map { it.spec.id } shouldBe listOf(EffectIds.KEN_BURNS)
  }

  @Test
  fun `a clip-only effect declared on a track is refused by name`() {
    val verdict =
      fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), effects = listOf(PUSH_IN)))))

    val refused = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.UnsupportedEffect>(refused).specId shouldBe EffectIds.KEN_BURNS
  }

  @Test
  fun `a clip-only effect declared on the composition is refused by name`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()))), effects = listOf(PUSH_IN)))

    val refused = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.UnsupportedEffect>(refused).specId shouldBe EffectIds.KEN_BURNS
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

    val refused = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.UnsupportedEffect>(refused).specId shouldBe EffectIds.KEN_BURNS
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

    val degraded = assertIs<Verdict.Degraded>(verdict)
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
