package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.geometry.Fit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class EffectSettingsTest {
  @Test
  fun `float literals keep at most two decimals`() {
    floatLiteral(1f) shouldBe "1f"
    floatLiteral(1.5f) shouldBe "1.5f"
    floatLiteral(1.25f) shouldBe "1.25f"
    floatLiteral(0.05f) shouldBe "0.05f"
    floatLiteral(-90f) shouldBe "-90f"
    floatLiteral(-0.4f) shouldBe "-0.4f"
  }

  @Test
  fun `defaults list no effects`() {
    effectsCodeText(EffectSettings()) shouldContain "// Pick an effect"
    chainOf(EffectSettings()) shouldBe emptyList()
  }

  @Test
  fun `only changed values reach the code`() {
    val settings = EffectSettings(flipHorizontal = true).withColor(ColorControl.Contrast, 1.2f)
    val code = effectsCodeText(settings)
    code shouldContain "contrast(1.2f)"
    code shouldContain "flip(FlipAxis.Horizontal)"
    code shouldNotContain "brightness"
    code shouldNotContain "Pick an effect"
  }

  @Test
  fun `crop names only the arguments that differ from their defaults`() {
    effectsCodeText(EffectSettings(crop = CropChoice.Square)) shouldContain "crop(AspectRatio.Square)"
    val moved = EffectSettings(crop = CropChoice.Portrait, fit = Fit.Contain, anchor = NamedAnchor.TopCenter)
    effectsCodeText(moved) shouldContain "crop(AspectRatio.Portrait, fit = Fit.Contain, anchor = Anchor.TopCenter)"
  }

  @Test
  fun `the chain lists effects in stage order whatever was set first`() {
    val settings =
      EffectSettings(kenBurns = true)
        .withColor(ColorControl.Sepia, 0.5f)
        .copy(rotation = 90)
    chainOf(settings).map { it.stage } shouldContainExactly listOf(Stage.Geometry, Stage.Color, Stage.Motion)
  }

  @Test
  fun `removing a chain item turns off only that effect`() {
    val settings = EffectSettings(rotation = 180, flipVertical = true)
    val rotate = chainOf(settings).first { it.label.startsWith("ROTATE") }
    rotate.without shouldBe EffectSettings(flipVertical = true)
  }

  @Test
  fun `a quarter turn makes the output portrait unless a crop sets it`() {
    outputLabel(EffectSettings(rotation = 90)) shouldBe "OUT 9:16 · SOURCE HEIGHT"
    outputLabel(EffectSettings(rotation = 90, crop = CropChoice.Square, scaleHeight = 720)) shouldBe
      "OUT 1:1 · 720 TALL"
  }

  @Test
  fun `a look replaces every colour value and is recognised afterwards`() {
    val graded = EffectSettings().withColor(ColorControl.Hue, 40f).withLook(Look.Noir)
    graded.value(ColorControl.Hue) shouldBe 0f
    graded.value(ColorControl.Saturation) shouldBe 0f
    graded.showsLook(Look.Noir) shouldBe true
    graded.showsLook(Look.None) shouldBe false
  }

  @Test
  fun `snap lands on neutral near it and rounds elsewhere`() {
    snap(1.015f, ColorControl.Brightness) shouldBe 1f
    snap(1.456f, ColorControl.Brightness) shouldBe 1.46f
    snap(1.4f, ColorControl.Hue) shouldBe 0f
    snap(37.6f, ColorControl.Hue) shouldBe 38f
  }
}
