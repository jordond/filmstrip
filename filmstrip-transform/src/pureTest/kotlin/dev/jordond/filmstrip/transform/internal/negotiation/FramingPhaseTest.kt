package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.geometry.measuredThrough
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class FramingPhaseTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a fixed frame is the same size whichever clip comes first`() {
    val wide = fixture.clip(size = Size(1920, 1080))
    val tall = fixture.clip(size = Size(720, 1280))
    val fixed = OutputFrame.Fixed(Size(640, 480))

    framing(composition(wide, tall).withFrame(fixed)).natural.size shouldBe Size(640, 480)
    framing(composition(tall, wide).withFrame(fixed)).natural.size shouldBe Size(640, 480)
    framing(composition(tall, wide).withFrame(fixed)).compositionInputSize shouldBe Size(640, 480)
  }

  @Test
  fun `an aspect frame is the frame a contain crop placed first leaves`() {
    for (size in listOf(Size(1920, 1080), Size(999, 541))) {
      val clip = fixture.clip(size = size)
      val crop = Crop(AspectRatio.Classic, Fit.Contain)
      val recipe = measuredThrough(MeasuredFrame(size), listOf(listOf(crop)))

      val framing = framing(composition(clip).withFrame(OutputFrame.Aspect(AspectRatio.Classic)))

      framing.natural.size shouldBe recipe.size
      framing.natural.aspect shouldBe recipe.aspect
      framing.compositionInputSize shouldBe recipe.size
    }
  }

  @Test
  fun `an aspect frame takes its height from the first clip after that clip's own effects`() {
    val clip = fixture.clip(size = Size(1920, 1080), effects = listOf(Scale(720)))
    val recipe =
      measuredThrough(MeasuredFrame(Size(1920, 1080)), listOf(listOf(Scale(720)), listOf(Crop(AspectRatio.Square))))

    framing(composition(clip).withFrame(OutputFrame.Aspect(AspectRatio.Square))).natural.size shouldBe recipe.size
  }

  @Test
  fun `a fixed frame is the frame a contain crop and a scale placed first leave`() {
    for (size in listOf(Size(640, 480), Size(1000, 543), Size(1080, 1920))) {
      val clip = fixture.clip(size = Size(1920, 1080))
      val recipe = recipeFor(size)

      framing(composition(clip).withFrame(OutputFrame.Fixed(size))).natural.size shouldBe
        framing(composition(clip).withEffects(recipe)).natural.size
    }
  }

  @Test
  fun `composition geometry still runs on a fixed frame`() {
    val clip = fixture.clip(size = Size(1920, 1080))
    val edit =
      composition(clip)
        .withFrame(OutputFrame.Fixed(Size(640, 480)))
        .withEffects(listOf(Crop(AspectRatio.Square)))

    framing(edit).natural.size shouldBe Size(480, 480)
    framing(edit).compositionInputSize shouldBe Size(640, 480)
  }

  @Test
  fun `an export height is taken as given against a fixed frame`() {
    val edit = composition(fixture.clip(size = Size(1920, 1080))).withFrame(OutputFrame.Fixed(Size(640, 480)))

    framing(edit, ExportSpec(heightPx = 960)).requestedSize shouldBe Size(1280, 960)
    framing(edit, ExportSpec(heightPx = 240)).requestedSize shouldBe Size(320, 240)
  }

  @Test
  fun `an export height above an aspect frame keeps it the way it keeps an automatic one`() {
    val edit = composition(fixture.clip(size = Size(1280, 720))).withFrame(OutputFrame.Aspect(AspectRatio.Square))

    framing(edit, ExportSpec(heightPx = 1080)).requestedSize shouldBe Size(720, 720)
    framing(edit, ExportSpec(heightPx = 360)).requestedSize shouldBe Size(360, 360)
  }

  @Test
  fun `an aspect frame over a track of stills measures the first still`() {
    val still = fixture.imageClip(size = Size(1600, 1200))
    val edit = composition(still).withFrame(OutputFrame.Aspect(AspectRatio.Square))

    framing(edit).natural.size shouldBe Size(1200, 1200)
  }

  @Test
  fun `the automatic frame is unchanged`() {
    val clip = fixture.clip(size = Size(1920, 1080), effects = listOf(Crop(AspectRatio.Portrait)))
    val edit = composition(clip, fixture.clip(size = Size(640, 480)))

    framing(edit).natural.size shouldBe framing(edit.withFrame(OutputFrame.Auto)).natural.size
    framing(edit).natural.size shouldBe
      measuredThrough(
        MeasuredFrame(Size(1920, 1080)),
        listOf(listOf(Crop(AspectRatio.Portrait))),
      ).size
  }

  private fun recipeFor(size: Size): List<EffectSpec> =
    listOf(Crop(AspectRatio(size.width, size.height), Fit.Contain), Scale(size.height))

  private fun framing(
    edit: Composition,
    spec: ExportSpec = ExportSpec(),
  ): Framing {
    val ask = Ask(spec, fixture.device(), fixture.infos(edit), emptyMap(), null)
    return framingOf(edit, ask).shouldBeInstanceOf<Phase.Next<Framing>>().value
  }
}
