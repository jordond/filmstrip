package dev.jordond.filmstrip

import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.inCanonicalOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ordering guarantee, which is what makes normalised coordinates mean one thing.
 */
class CanonicalOrderTest {
  @Test
  fun callOrderIsNotHonoured() {
    // Written in the order that would break: a watermark added before the crop that would
    // otherwise cut it away.
    val declared =
      listOf(
        spec(EffectIds.WATERMARK, EffectStage.Composite),
        spec(EffectIds.CROP, EffectStage.Geometry),
        spec(EffectIds.TEXT, EffectStage.Composite),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.SCALE, EffectStage.Geometry),
      )

    assertEquals(
      listOf(EffectIds.ROTATE, EffectIds.CROP, EffectIds.SCALE, EffectIds.WATERMARK, EffectIds.TEXT),
      declared.inCanonicalOrder().map { it.id },
    )
  }

  @Test
  fun theSamePipelineComesOutOfEitherWriting() {
    val one =
      listOf(
        spec(EffectIds.SCALE, EffectStage.Geometry),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.WATERMARK, EffectStage.Composite),
      )
    val other =
      listOf(
        spec(EffectIds.WATERMARK, EffectStage.Composite),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.SCALE, EffectStage.Geometry),
      )

    assertEquals(one.inCanonicalOrder().map { it.id }, other.inCanonicalOrder().map { it.id })
  }

  @Test
  fun sizeIsDecidedLastWithinGeometry() {
    // Placed anywhere but last, the size stage pins nothing: crop and rotation both change the
    // frame after it. Asking for 1920x1080 with it first was measured producing 1080x608.
    val ordered =
      listOf(
        spec(EffectIds.SCALE, EffectStage.Geometry),
        spec(EffectIds.CROP, EffectStage.Geometry),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
      ).inCanonicalOrder()

    assertEquals(EffectIds.SCALE, ordered.last().id)
  }

  @Test
  fun aPanTravelsInsideTheFramingTheCropChose() {
    val ordered =
      listOf(
        spec(EffectIds.SCALE, EffectStage.Geometry),
        spec(EffectIds.KEN_BURNS, EffectStage.Geometry),
        spec(EffectIds.CROP, EffectStage.Geometry),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
        spec(EffectIds.FLIP, EffectStage.Geometry),
      ).inCanonicalOrder()

    assertEquals(
      listOf(EffectIds.ROTATE, EffectIds.FLIP, EffectIds.CROP, EffectIds.KEN_BURNS, EffectIds.SCALE),
      ordered.map { it.id },
    )
  }

  @Test
  fun thirdPartyEffectsLandAtTheEndOfTheirStageInDeclarationOrder() {
    val ordered =
      listOf(
        spec("acme.grain", EffectStage.Color),
        spec("acme.bloom", EffectStage.Color),
        spec(EffectIds.ROTATE, EffectStage.Geometry),
      ).inCanonicalOrder()

    assertEquals(listOf(EffectIds.ROTATE, "acme.grain", "acme.bloom"), ordered.map { it.id })
  }

  private fun spec(
    specId: String,
    specStage: EffectStage,
  ): EffectSpec =
    object : EffectSpec {
      override val id: String get() = specId
      override val stage: EffectStage get() = specStage
    }
}
