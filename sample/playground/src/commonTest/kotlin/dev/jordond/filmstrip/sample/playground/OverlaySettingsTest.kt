package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.style.FontWeight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class OverlaySettingsTest {
  @Test
  fun `defaults name the caption style, the window and the animation`() {
    val code = codeText(overlaysCode(OverlaySettings()))
    code shouldContain "val animation = fade(500.milliseconds, 500.milliseconds)"
    code shouldContain "    text = \"Day one\","
    code shouldContain "    at = 1.seconds..9.seconds,"
    code shouldContain "    style = TextStyle(weight = FontWeight.Bold, backgroundColor = 0x80000000.toInt()),"
    code shouldNotContain "anchor ="
    code shouldContain "    corner = Corner.TopEnd,"
    code shouldContain "    scale = 0.1f,"
    code shouldContain "    opacity = 0.8f,"
  }

  @Test
  fun `a plain caption with no motion leaves its defaults out`() {
    val settings =
      OverlaySettings(
        logo = false,
        plate = false,
        weight = FontWeight.Regular,
        motion = OverlayMotion.None,
        window = OverlayWindow.Always,
      )
    val code = codeText(overlaysCode(settings))
    code shouldNotContain "style ="
    code shouldNotContain "animation"
    code shouldNotContain "at ="
    code shouldNotContain "logo"
  }

  @Test
  fun `a moved anchor and corner reach the code`() {
    val code = codeText(overlaysCode(OverlaySettings(anchor = NamedAnchor.TopStart, corner = Corner.BottomEnd)))
    code shouldContain "    anchor = Anchor.TopStart,"
    code shouldContain "    corner = Corner.BottomEnd,"
  }

  @Test
  fun `caption text is escaped as a Kotlin string`() {
    kotlinString("say \"hi\" to \$you\\") shouldBe "say \\\"hi\\\" to \\\$you\\\\"
  }

  @Test
  fun `pulse is one instance so rebuilt compositions compare equal`() {
    animationOf(OverlayMotion.Pulse) shouldBe animationOf(OverlayMotion.Pulse)
    (animationOf(OverlayMotion.Pulse) === pulse) shouldBe true
  }

  @Test
  fun `the summary names what is on`() {
    overlaySummary(OverlaySettings()) shouldBe "CAPTION · LOGO · FADE"
    overlaySummary(OverlaySettings(logo = false, motion = OverlayMotion.None)) shouldBe "CAPTION"
    overlaySummary(OverlaySettings(caption = false, logo = false)) shouldBe "NO OVERLAYS"
  }
}
