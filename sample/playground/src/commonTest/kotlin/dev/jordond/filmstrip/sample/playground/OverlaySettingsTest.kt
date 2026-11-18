package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.FontWeight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

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

  @Test
  fun `a spinner reaches the code and the summary`() {
    val settings = OverlaySettings(spinner = true, spinnerPlays = SpinnerPlays.Once, spinnerFrameMs = 40)
    val code = codeText(overlaysCode(settings))
    code shouldContain "val spinner = ImageSource.ofUri(\"sheet.png\")"
    code shouldContain "    image = spinner,"
    code shouldContain "    corner = Corner.BottomStart,"
    code shouldContain
      "    sheet = SpriteSheet(columns = 4, rows = 3, frameDuration = 40.milliseconds, plays = FramePlays.Once),"
    overlaySummary(settings) shouldBe "CAPTION · LOGO · SPINNER · FADE"
  }

  @Test
  fun `a looping spinner leaves plays out`() {
    val code = codeText(overlaysCode(OverlaySettings(spinner = true)))
    code shouldContain "frameDuration = 80.milliseconds)"
    code shouldNotContain "plays ="
  }

  @Test
  fun `the edit carries the spinner sheet with the settings duration and plays`() {
    val sheet = ImageSource.ofUri("sheet.png")
    val settings = OverlaySettings(caption = false, logo = false, spinner = true, spinnerFrameMs = 160)
    val edit =
      overlaysEdit(
        MediaSource.of("clip.mp4"),
        ImageSource.ofUri("logo.png"),
        sheet,
        ::stickerOf,
        settings.copy(spinnerPlays = SpinnerPlays.Once),
      )
    val overlay = edit.effects.filterIsInstance<ImageOverlay>().single()
    overlay.image shouldBe sheet
    overlay.sheet?.columns shouldBe 4
    overlay.sheet?.rows shouldBe 3
    overlay.sheet?.frameCount shouldBe 12
    overlay.sheet?.frameDurations?.toSet() shouldBe setOf(160.milliseconds)
    overlay.sheet?.plays shouldBe FramePlays.Once
  }

  @Test
  fun `a spinner that is off leaves no sheet in the edit`() {
    val edit =
      overlaysEdit(
        MediaSource.of("clip.mp4"),
        ImageSource.ofUri("logo.png"),
        ImageSource.ofUri("sheet.png"),
        ::stickerOf,
        OverlaySettings(),
      )
    edit.effects.filterIsInstance<ImageOverlay>().map { it.sheet } shouldBe listOf(null)
  }

  @Test
  fun `a sticker reaches the code and the summary`() {
    val settings = OverlaySettings(sticker = true, stickerFormat = StickerFormat.Webp)
    val code = codeText(overlaysCode(settings))
    code shouldContain "val sticker = ImageSource.ofUri(\"sticker.webp\")"
    code shouldContain "    image = sticker,"
    code shouldContain "    corner = Corner.BottomEnd,"
    overlaySummary(settings) shouldBe "CAPTION · LOGO · STICKER · FADE"
  }

  @Test
  fun `the edit draws the sticker in the format the settings pick with no sheet`() {
    val settings = OverlaySettings(caption = false, logo = false, sticker = true, stickerFormat = StickerFormat.Apng)
    val edit =
      overlaysEdit(
        MediaSource.of("clip.mp4"),
        ImageSource.ofUri("logo.png"),
        ImageSource.ofUri("sheet.png"),
        ::stickerOf,
        settings,
      )
    val overlay = edit.effects.filterIsInstance<ImageOverlay>().single()
    overlay.image shouldBe stickerOf(StickerFormat.Apng)
    overlay.corner shouldBe Corner.BottomEnd
    overlay.sheet shouldBe null
  }

  private fun stickerOf(format: StickerFormat): ImageSource = ImageSource.ofUri(format.file)
}
