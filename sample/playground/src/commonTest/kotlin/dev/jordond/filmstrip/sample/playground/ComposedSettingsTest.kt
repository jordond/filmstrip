package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class ComposedSettingsTest {
  @Test
  fun `defaults place both elements and leave an unit scale out`() {
    val code = codeText(composedCode(ComposedSettings()))

    code shouldContain "var caption by remember { mutableStateOf(OverlayTransform(Anchor(0.5f, 0.22f))) }"
    code shouldContain
      "var badge by remember { mutableStateOf(OverlayTransform(Anchor(0.84f, 0.8f), rotationDegrees = -8f)) }"
    code shouldNotContain "scale ="
    code shouldContain "ComposedOverlay(caption, onTransformChange = { caption = it }) { Caption(\"Day one\") }"
    code shouldContain "VideoStage(player, outputAspect, overlays = overlays) {"
    code shouldContain "filmstrip.planWithOverlays(composition, spec, overlays)"
  }

  @Test
  fun `a moved caption shows its scale and turn and escapes its text`() {
    val moved = OverlayTransform(Anchor(0.3f, 0.6f), scale = 1.5f, rotationDegrees = 12.5f)
    val settings = ComposedSettings(text = "Say \"hi\"").with(ComposedElement.Caption, moved)

    val code = codeText(composedCode(settings))

    code shouldContain "OverlayTransform(Anchor(0.3f, 0.6f), scale = 1.5f, rotationDegrees = 12.5f)"
    code shouldContain "Caption(\"Say \\\"hi\\\"\")"
  }

  @Test
  fun `moving one element leaves the other where it was`() {
    val moved = OverlayTransform(Anchor(0.1f, 0.1f))

    val settings = ComposedSettings().with(ComposedElement.Badge, moved)

    settings.badge shouldBe moved
    settings.caption shouldBe ComposedSettings.DEFAULT_CAPTION
  }
}
