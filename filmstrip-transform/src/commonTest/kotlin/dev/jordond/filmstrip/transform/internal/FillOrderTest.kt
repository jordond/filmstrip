package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertNotNull

class FillOrderTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a colour effect runs under the fill and the overlays run over it in declared order`() {
    val negotiated = negotiate(effects = listOf(Brightness(1.2f), FakeOverlay(FIRST), FakeOverlay(SECOND)))

    negotiated.effectsUnderFill.ids() shouldBe listOf(EffectIds.BRIGHTNESS)
    negotiated.effectsOverFill.ids() shouldBe listOf(FIRST, SECOND)
    (negotiated.effectsUnderFill + negotiated.effectsOverFill) shouldBe negotiated.compositionEffects

    val resolved = negotiated.toResolvedComposition()
    resolved.effectsUnderFill shouldBe negotiated.effectsUnderFill
    resolved.effectsOverFill shouldBe negotiated.effectsOverFill
    (resolved.effectsUnderFill + resolved.effectsOverFill) shouldBe resolved.compositionEffects
  }

  @Test
  fun `an overlay written before a colour effect still runs over the fill`() {
    val negotiated = negotiate(effects = listOf(FakeOverlay(FIRST), Brightness(1.2f)))

    negotiated.effectsUnderFill.ids() shouldBe listOf(EffectIds.BRIGHTNESS)
    negotiated.effectsOverFill.ids() shouldBe listOf(FIRST)
    (negotiated.effectsUnderFill + negotiated.effectsOverFill) shouldBe negotiated.compositionEffects
  }

  @Test
  fun `a folded colour run takes the stage of the effects it was folded from`() {
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
    negotiated.toResolvedComposition().effectsUnderFill.shouldBeEmpty()
    negotiated.toResolvedComposition().effectsOverFill.shouldBeEmpty()
  }

  @Test
  fun `a hand built resolved composition with an overlay before a colour effect fails the split`() {
    val resolved = negotiate(effects = listOf(Brightness(1.2f), FakeOverlay(FIRST))).toResolvedComposition()
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

  @Test
  fun `a hand built negotiated composition with an overlay before a colour effect fails the split`() {
    val negotiated = negotiate(effects = listOf(Brightness(1.2f), FakeOverlay(FIRST)))
    val outOfOrder =
      NegotiatedComposition(
        tracks = negotiated.tracks,
        compositionGeometry = negotiated.compositionGeometry,
        compositionInputSize = negotiated.compositionInputSize,
        compositionEffects = negotiated.compositionEffects.reversed(),
        output = negotiated.output,
        layoutSize = negotiated.layoutSize,
        fit = negotiated.fit,
        fill = negotiated.fill,
        duration = negotiated.duration,
        hdr = negotiated.hdr,
        hdrTransfer = negotiated.hdrTransfer,
        path = negotiated.path,
        audio = negotiated.audio,
        adjustments = negotiated.adjustments,
        encoderName = negotiated.encoderName,
      )

    outOfOrder.compositionEffects.ids() shouldBe listOf(FIRST, EffectIds.BRIGHTNESS)
    shouldThrow<IllegalArgumentException> { outOfOrder.effectsUnderFill }
    shouldThrow<IllegalArgumentException> { outOfOrder.effectsOverFill }
  }

  private fun negotiate(
    effects: List<EffectSpec>,
    clipEffects: List<EffectSpec> = emptyList(),
  ): NegotiatedComposition {
    val composition =
      EditComposition(listOf(Track(listOf(fixture.clip(effects = clipEffects)))), effects = effects)
    val device = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264)))
    return assertNotNull(
      fixture
        .planner(ladder = listOf(VideoCodec.H264))
        .negotiate(composition, ExportSpec(), device, fixture.infos(composition))
        .composition,
    )
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
