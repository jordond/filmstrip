package dev.jordond.filmstrip

import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.canonicalOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The ordering guarantee, which is what makes normalised coordinates mean one thing.
 */
class CanonicalOrderTest {
  @Test
  fun aBeneathOverlaysSpecRunsAheadOfEveryRankedOverlay() {
    val beneath =
      object : EffectSpec {
        override val id = EffectId("test.beneath")
        override val stage = EffectStage.Composite
        override val beneathOverlays = true
      }

    val ordered =
      canonicalOrder(
        listOf(
          spec(EffectIds.IMAGE_OVERLAY, EffectStage.Composite),
          beneath,
          spec(EffectIds.SCALE, EffectStage.Geometry),
        ),
      )

    ordered.map { it.id } shouldBe listOf(EffectIds.SCALE, EffectId("test.beneath"), EffectIds.IMAGE_OVERLAY)
  }

  @Test
  fun beneathOverlaysChangesNothingOutsideComposite() {
    val color =
      object : EffectSpec {
        override val id = EffectId("test.color")
        override val beneathOverlays = true
      }

    canonicalOrder(listOf(spec(EffectIds.BRIGHTNESS, EffectStage.Color), color)).map { it.id } shouldBe
      listOf(EffectIds.BRIGHTNESS, EffectId("test.color"))
  }

  @Test
  fun callOrderIsNotHonoured() {
    // Written in the order that would break: a watermark added before the crop that would
    // otherwise cut it away.
    val declared =
      listOf(
        spec(EffectIds.IMAGE_OVERLAY, EffectStage.Composite),
        spec(EffectIds.CROP, EffectStage.Geometry),
        spec(EffectIds.TEXT_OVERLAY, EffectStage.Composite),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.SCALE, EffectStage.Geometry),
      )

    canonicalOrder(declared).map { it.id } shouldBe
      listOf(EffectIds.ROTATE, EffectIds.CROP, EffectIds.SCALE, EffectIds.IMAGE_OVERLAY, EffectIds.TEXT_OVERLAY)
  }

  @Test
  fun theSamePipelineComesOutOfEitherWriting() {
    val one =
      listOf(
        spec(EffectIds.SCALE, EffectStage.Geometry),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.IMAGE_OVERLAY, EffectStage.Composite),
      )
    val other =
      listOf(
        spec(EffectIds.IMAGE_OVERLAY, EffectStage.Composite),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.SCALE, EffectStage.Geometry),
      )

    canonicalOrder(other).map { it.id } shouldBe canonicalOrder(one).map { it.id }
  }

  @Test
  fun sizeIsDecidedLastWithinGeometry() {
    // Placed anywhere but last, the size stage pins nothing: crop and rotation both change the
    // frame after it. Asking for 1920x1080 with it first was measured producing 1080x608.
    val ordered =
      canonicalOrder(
        listOf(
          spec(EffectIds.SCALE, EffectStage.Geometry),
          spec(EffectIds.CROP, EffectStage.Geometry),
          spec(EffectIds.ROTATE, EffectStage.Geometry),
        ),
      )

    ordered.last().id shouldBe EffectIds.SCALE
  }

  @Test
  fun aPanTravelsInsideTheFramingTheCropChose() {
    val ordered =
      canonicalOrder(
        listOf(
          spec(EffectIds.SCALE, EffectStage.Geometry),
          spec(EffectIds.KEN_BURNS, EffectStage.Geometry),
          spec(EffectIds.CROP, EffectStage.Geometry),
          spec(EffectIds.ROTATE, EffectStage.Geometry),
          spec(EffectIds.FLIP, EffectStage.Geometry),
        ),
      )

    ordered.map { it.id } shouldBe
      listOf(EffectIds.ROTATE, EffectIds.FLIP, EffectIds.CROP, EffectIds.KEN_BURNS, EffectIds.SCALE)
  }

  @Test
  fun aVignetteRunsAfterABlurAndBeforeAThirdPartySpatialEffect() {
    val ordered =
      canonicalOrder(
        listOf(
          spec(EffectId("acme.bloom"), EffectStage.Spatial),
          spec(EffectIds.VIGNETTE, EffectStage.Spatial),
          spec(EffectIds.GAUSSIAN_BLUR, EffectStage.Spatial),
        ),
      )

    ordered.map { it.id } shouldBe listOf(EffectIds.GAUSSIAN_BLUR, EffectIds.VIGNETTE, EffectId("acme.bloom"))
  }

  @Test
  fun thirdPartyEffectsLandAtTheEndOfTheirStageInDeclarationOrder() {
    val ordered =
      canonicalOrder(
        listOf(
          spec(EffectId("acme.grain"), EffectStage.Color),
          spec(EffectId("acme.bloom"), EffectStage.Color),
          spec(EffectIds.ROTATE, EffectStage.Geometry),
        ),
      )

    ordered.map { it.id } shouldBe listOf(EffectIds.ROTATE, EffectId("acme.grain"), EffectId("acme.bloom"))
  }

  private fun spec(
    specId: EffectId,
    specStage: EffectStage,
  ): EffectSpec =
    object : EffectSpec {
      override val id: EffectId get() = specId
      override val stage: EffectStage get() = specStage
    }
}
