package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.CONVERTS_IT
import dev.jordond.filmstrip.transform.internal.FakeResolver
import dev.jordond.filmstrip.transform.internal.ROUNDS_IT
import dev.jordond.filmstrip.transform.internal.fakePlatformEffect
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.refusal
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a chain of effect stages is folded, ordered and lowered through the resolver chain, and what each lowering
 * reports back.
 */
class StageLoweringTest {
  @Test
  fun `a run of color effects lowers once and stands for every effect the caller wrote`() {
    val recorder = Recorder()

    val lowered = lower(StageLowering(listOf(recorder), UNCLAIMED), listOf(Brightness(4f), Sepia(1f))).single()

    recorder.specs.map { it.id } shouldBe listOf(EffectIds.COLOR_MATRIX)
    lowered.spec.id shouldBe EffectIds.COLOR_MATRIX
    lowered.sources.map { it.id } shouldBe listOf(Brightness(4f), Sepia(1f)).inCanonicalOrder().map { it.id }
    lowered.resolvedEffect.shouldNotBeNull().specId shouldBe lowered.sources.joinToString(", ") { it.id }
  }

  @Test
  fun `an effect nobody claims is reported against the ids the caller wrote`() {
    val lowering = StageLowering(listOf(FakeResolver(declines = setOf(EffectIds.COLOR_MATRIX))), UNCLAIMED)

    val lowered = lower(lowering, listOf(Brightness(4f), Sepia(1f))).single()

    lowered.unsupported shouldBe UNCLAIMED(lowered.sources.joinToString(", ") { it.id })
    lowered.resolvedEffect.shouldBeNull()
  }

  @Test
  fun `a refusal keeps the resolver's own message`() {
    val lowering = StageLowering(listOf(FakeResolver(refuse = setOf(EffectIds.SEPIA))), UNCLAIMED)

    val lowered = lower(lowering, listOf(Sepia(1f))).single()

    lowered.unsupported shouldBe "this resolver owns it and cannot render it"
    lowered.resolvedEffect.shouldBeNull()
  }

  @Test
  fun `a degraded fold pairs every authored effect with every cause in the resolver's order`() {
    val resolver = FakeResolver(degrade = setOf(EffectIds.COLOR_MATRIX), causes = listOf(ROUNDS_IT, CONVERTS_IT))

    val lowered = lower(StageLowering(listOf(resolver), UNCLAIMED), listOf(Brightness(4f), Sepia(1f))).single()

    lowered.degraded shouldBe
      lowered.sources.flatMap { source -> listOf(source.id to ROUNDS_IT.message, source.id to CONVERTS_IT.message) }
    lowered.resolvedEffect.shouldNotBeNull()
  }

  // A resolver ranked above the catalogue would lose its claim inside the ColorMatrix the fold makes, so the run folds
  // around what it claims, and the resolution it already gave is kept rather than asked for again.
  @Test
  fun `a resolver ahead of the catalogue claims inside a run once and the run folds around it`() {
    val claimer = Recorder(claims = setOf(EffectIds.SEPIA))
    val effects = listOf(Brightness(4f), Sepia(1f))

    val lowered = lower(StageLowering(listOf(claimer, BuiltInEffectResolver()), UNCLAIMED), effects)

    lowered.map { entry -> entry.sources.map { it.id } } shouldBe effects.inCanonicalOrder().map { listOf(it.id) }
    lowered.single { it.spec.id == EffectIds.SEPIA }.resolvedEffect?.specId shouldBe EffectIds.SEPIA
    claimer.specs.count { it.id == EffectIds.SEPIA } shouldBe 1
  }

  @Test
  fun `with no catalogue registered nothing outranks the fold`() {
    val claimer = Recorder(claims = setOf(EffectIds.SEPIA, EffectIds.COLOR_MATRIX))

    val lowered = lower(StageLowering(listOf(claimer), UNCLAIMED), listOf(Brightness(4f), Sepia(1f)))

    lowered.map { it.spec.id } shouldBe listOf(EffectIds.COLOR_MATRIX)
    claimer.specs.map { it.id } shouldBe listOf(EffectIds.COLOR_MATRIX)
  }

  @Test
  fun `geometry hands every later effect the frame and layout it leaves behind`() {
    val recorder = Recorder()
    val scale = Scale(720)
    val input = Size(1920, 1080)
    val layout = Size(3840, 2160)

    StageLowering(listOf(recorder), UNCLAIMED)
      .lowerStages(listOf(listOf(scale), listOf(Brightness(0.5f))), input, layout, SPAN, loweringTarget())

    recorder.attributes.getValue(EffectIds.SCALE).inputSize shouldBe input
    recorder.attributes.getValue(EffectIds.BRIGHTNESS).inputSize shouldBe frameAfter(scale, input)
    recorder.attributes.getValue(EffectIds.BRIGHTNESS).layoutSize shouldBe frameAfter(scale, layout)
  }

  @Test
  fun `the stored frame reaches color and spatial effects until a geometry effect squares it`() {
    val recorder = Recorder()
    val scale = Scale(720)
    val input = Size(1920, 1080)
    val stored = Size(960, 1080)

    StageLowering(listOf(recorder), UNCLAIMED).lowerStages(
      listOf(listOf(Sepia(1f), Vignette()), listOf(scale), listOf(Brightness(0.5f))),
      input,
      input,
      SPAN,
      loweringTarget(),
      stored,
    )

    recorder.attributes.getValue(EffectIds.SEPIA).storedSize shouldBe stored
    recorder.attributes.getValue(EffectIds.VIGNETTE).storedSize shouldBe stored
    recorder.attributes.getValue(EffectIds.SCALE).storedSize shouldBe input
    recorder.attributes.getValue(EffectIds.BRIGHTNESS).storedSize shouldBe frameAfter(scale, input)
  }

  @Test
  fun `a composite effect and every effect after it are handed the frame squared`() {
    val recorder = Recorder()
    val input = Size(1920, 1080)

    StageLowering(listOf(recorder), UNCLAIMED).lowerStages(
      listOf(listOf(Vignette(), TextOverlay("Wide")), listOf(Brightness(0.5f))),
      input,
      input,
      SPAN,
      loweringTarget(),
      Size(960, 1080),
    )

    recorder.attributes.getValue(EffectIds.TEXT_OVERLAY).storedSize shouldBe input
    recorder.attributes.getValue(EffectIds.BRIGHTNESS).storedSize shouldBe input
  }

  @Test
  fun `every resolver is handed the target's frame rate span and grade`() {
    val recorder = Recorder()
    val lowering = StageLowering(listOf(recorder), UNCLAIMED)
    val output = Size(1280, 720)

    lowering.lowerStages(
      listOf(listOf(Sepia(1f))),
      SIZE,
      SIZE,
      SPAN,
      loweringTarget(output, 24, HdrTransfer.Hlg),
    )
    val hdr = recorder.attributes.getValue(EffectIds.SEPIA)
    lowering.lowerStages(listOf(listOf(Brightness(0.5f))), SIZE, SIZE, SPAN, loweringTarget(output, 24))
    val sdr = recorder.attributes.getValue(EffectIds.BRIGHTNESS)

    hdr.outputSize shouldBe output
    hdr.frameRate shouldBe 24f
    hdr.span shouldBe SPAN
    hdr.hdrTransfer shouldBe HdrTransfer.Hlg
    hdr.colorSpace shouldBe ColorSpace.Bt2020
    sdr.hdrTransfer.shouldBeNull()
    sdr.colorSpace shouldBe ColorSpace.Bt709
  }

  @Test
  fun `an opaque source tells each effect its whole frame is opaque and a source with alpha tells it nothing`() {
    val recorder = Recorder()
    val lowering = StageLowering(listOf(recorder), UNCLAIMED)

    lowering.lowerStages(listOf(listOf(Sepia(1f))), SIZE, SIZE, SPAN, loweringTarget())
    val opaque = recorder.attributes.getValue(EffectIds.SEPIA)
    lowering.lowerStages(listOf(listOf(Brightness(0.5f))), SIZE, SIZE, SPAN, loweringTarget(), sourceHasAlpha = true)
    val clear = recorder.attributes.getValue(EffectIds.BRIGHTNESS)

    opaque.opaque shouldBe NormalizedRect.Full
    clear.opaque.shouldBeNull()
  }

  private fun lower(
    lowering: StageLowering,
    effects: List<EffectSpec>,
  ): List<LoweredEffect> = lowering.lowerStages(listOf(effects), SIZE, SIZE, SPAN, loweringTarget())

  /**
   * Keeps every spec it is asked about and the attributes it was asked with, claiming only [claims] when it is set.
   */
  private class Recorder(
    private val claims: Set<String>? = null,
  ) : EffectResolver {
    val specs = mutableListOf<EffectSpec>()
    val attributes = mutableMapOf<String, Attributes>()

    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? {
      specs += spec
      this.attributes[spec.id] = attributes
      return if (claims == null || spec.id in claims) EffectResolution.Resolved(fakePlatformEffect()) else null
    }
  }

  private companion object {
    val SIZE = Size(1920, 1080)
    val SPAN = TimeRange.of(500.milliseconds, 2_500.milliseconds)
    val UNCLAIMED: (String) -> String = { "nothing claimed $it" }
  }
}
