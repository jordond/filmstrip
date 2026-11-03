package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FillOrderTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a color effect runs under the fill and the overlays run over it in declared order`() {
    val negotiated = negotiate(effects = listOf(Brightness(1.2f), FakeOverlay(FIRST), FakeOverlay(SECOND)))

    negotiated.effectsUnderFill.ids() shouldBe listOf(EffectIds.BRIGHTNESS)
    negotiated.effectsOverFill.ids() shouldBe listOf(FIRST, SECOND)
    (negotiated.effectsUnderFill + negotiated.effectsOverFill) shouldBe negotiated.compositionEffects
  }

  @Test
  fun `an overlay written before a color effect still runs over the fill`() {
    val negotiated = negotiate(effects = listOf(FakeOverlay(FIRST), Brightness(1.2f)))

    negotiated.effectsUnderFill.ids() shouldBe listOf(EffectIds.BRIGHTNESS)
    negotiated.effectsOverFill.ids() shouldBe listOf(FIRST)
    (negotiated.effectsUnderFill + negotiated.effectsOverFill) shouldBe negotiated.compositionEffects
  }

  @Test
  fun `a folded color run takes the stage of the effects it was folded from`() {
    val negotiated = negotiate(effects = listOf(Brightness(1.2f), Sepia(), FakeOverlay(FIRST)))

    val folded = negotiated.effectsUnderFill.single()
    folded.specId shouldBe "${EffectIds.BRIGHTNESS}, ${EffectIds.SEPIA}"
    folded.stage shouldBe EffectStage.Color
    negotiated.effectsOverFill.ids() shouldBe listOf(FIRST)
  }

  @Test
  fun `no composition effects leaves both sides empty and clip effects never appear`() {
    val negotiated = negotiate(effects = emptyList(), clipEffects = listOf(Brightness(1.2f), FakeOverlay(FIRST)))

    negotiated.tracks
      .single()
      .clips
      .single()
      .effects
      .ids() shouldBe listOf(EffectIds.BRIGHTNESS, FIRST)
    negotiated.effectsUnderFill.shouldBeEmpty()
    negotiated.effectsOverFill.shouldBeEmpty()
  }

  @Test
  fun `a hand built resolved composition with an overlay before a color effect fails the split`() {
    val resolved = negotiate(effects = listOf(Brightness(1.2f), FakeOverlay(FIRST)))
    val outOfOrder =
      ResolvedComposition(
        tracks = resolved.tracks,
        compositionGeometry = resolved.compositionGeometry,
        compositionInputSize = resolved.compositionInputSize,
        compositionEffects = resolved.compositionEffects.reversed(),
        output = resolved.output,
        layoutSize = resolved.layoutSize,
        fit = resolved.fit,
        fill = resolved.fill,
        duration = resolved.duration,
        hdr = resolved.hdr,
        hdrTransfer = resolved.hdrTransfer,
        path = resolved.path,
        audio = resolved.audio,
        adjustments = resolved.adjustments,
      )

    outOfOrder.compositionEffects.ids() shouldBe listOf(FIRST, EffectIds.BRIGHTNESS)
    shouldThrow<IllegalArgumentException> { outOfOrder.effectsUnderFill }
    shouldThrow<IllegalArgumentException> { outOfOrder.effectsOverFill }
  }

  private fun negotiate(
    effects: List<EffectSpec>,
    clipEffects: List<EffectSpec> = emptyList(),
  ): ResolvedComposition {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(effects = clipEffects)))), effects = effects)
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)))
    return fixture
      .planner(ladder = listOf(VideoCodec.H264))
      .negotiate(composition, Ask(ExportSpec(), device, fixture.infos(composition)))
      .composition
      .shouldNotBeNull()
  }

  private fun List<ResolvedEffect>.ids(): List<String> = map { it.specId }

  private companion object {
    const val FIRST = "test.overlay.first"
    const val SECOND = "test.overlay.second"
  }
}

/**
 * An effect that places itself in [EffectStage.Composite] the way an overlay does, with nothing to
 * draw, so a test can see where the planner and the fill order put one.
 */
private class FakeOverlay(
  override val id: String,
) : EffectSpec {
  override val stage: EffectStage get() = EffectStage.Composite
}
