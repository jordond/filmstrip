package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * What a preview under a quality cap tells a resolver about the frame.
 *
 * The cap shrinks everything a resolver draws against, and text is the one thing that must not
 * follow it down: laid out at the preview's own width it wraps on different words than the export.
 * So the lowering carries two frames, and this pins which is which.
 */
class AppleTextLayoutMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a capped preview lowers at the capped frame and lays text out at the export's`() =
    contractTest {
      val composition = appleFixtureComposition(listOf(TextOverlay(CAPTION, CAPTION_STYLE)))
      val uncapped =
        AvPreviewPlanner(CONTRACT_COMPONENTS)
          .plan(composition, PreviewQualityPolicy.Full, ExportSpec())
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan
      val exportFrame = uncapped.info.outputSize
      exportFrame shouldBe FIXTURE_FRAME

      val recorded = RecordingResolver()
      val capped =
        AvPreviewPlanner(ComponentRegistry.Builder(CONTRACT_COMPONENTS).addEffectResolver(recorded).build())
          .plan(
            composition,
            PreviewQualityPolicy.CapHeight(CAP_HEIGHT),
            ExportSpec(),
          ).shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan
      val previewFrame = capped.resolved.output.size

      previewFrame shouldNotBe exportFrame
      previewFrame.height shouldBe CAP_HEIGHT
      capped.resolved.layoutSize shouldBe exportFrame

      // The natural frame is settled before the cap is applied to it, so the cap's own lowering is
      // the second of the two.
      recorded.seen.size shouldBe 2
      val natural = recorded.seen.first()
      natural.inputSize shouldBe exportFrame
      natural.layoutSize shouldBe exportFrame

      val underCap = recorded.seen.last()
      underCap.inputSize shouldBe previewFrame
      underCap.layoutSize shouldBe exportFrame
    }
}

/**
 * Records the attributes every effect is lowered against, then declines so the real resolver runs.
 */
private class RecordingResolver : EffectResolver {
  val seen: MutableList<Attributes> = mutableListOf()

  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    seen += attributes
    return null
  }
}
