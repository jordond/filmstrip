package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FilterSettingsTest {
  @Test
  fun `defaults name no settings`() {
    codeText(filtersCode(FilterSettings())) shouldContain "  vhs()\n"
  }

  @Test
  fun `only changed settings reach the code`() {
    val code = codeText(filtersCode(FilterSettings().withIntensity(0.6f).copy(tape = TapeSpeed.LP)))
    code shouldContain "  vhs {\n    intensity = 0.6f\n    tape = TapeSpeed.LP\n  }"
    code shouldNotContain "tracking"
  }

  @Test
  fun `a settle reaches the code and the look only when it is on`() {
    codeText(filtersCode(FilterSettings())) shouldNotContain "settle"
    codeText(filtersCode(FilterSettings(settle = true))) shouldContain "    settle(from = 1f, over = 3.seconds)"
    filtersEffects(FilterSettings().withIntensity(0.4f)).single().shouldBeInstanceOf<Vhs>().settle shouldBe null
    filtersEffects(FilterSettings(settle = true).withIntensity(0.4f)).single().shouldBeInstanceOf<Vhs>().settle shouldBe
      TapeSettle(1f, 3.seconds)
  }

  @Test
  fun `tracking and snow reach the code only off their defaults`() {
    codeText(filtersCode(FilterSettings(snow = 0.35f))) shouldContain "    tracking = events { snow = 0.35f }"
    codeText(filtersCode(FilterSettings(tracking = TrackingMode.Rolling))) shouldContain "    tracking = rolling()"
    codeText(filtersCode(FilterSettings(tracking = TrackingMode.Rolling, snow = 0.35f))) shouldContain
      "    tracking = rolling { snow = 0.35f }"
    codeText(filtersCode(FilterSettings(tracking = TrackingMode.Off, snow = 0.35f))) shouldContain
      "    tracking = Tracking.Off\n  }"
  }

  @Test
  fun `a camcorder on tape is written inside the vhs`() {
    val camcorder = CamcorderSettings(CamcorderMode.OnTape).toggle(CamcorderPart.Battery)
    codeText(filtersCode(FilterSettings(camcorder = camcorder))) shouldContain
      "  vhs {\n    camcorder {\n      rec()\n      date()\n      clock()\n      battery(0.25f)\n    }\n  }"
  }

  @Test
  fun `a clean camcorder is written after the vhs`() {
    val camcorder = CamcorderSettings(CamcorderMode.Clean, setOf(CamcorderPart.Counter))
    codeText(filtersCode(FilterSettings(camcorder = camcorder))) shouldContain
      "  vhs()\n  camcorder {\n    counter(withFrames = true)\n  }\n}"
  }

  @Test
  fun `a camcorder with no parts is left out`() {
    val camcorder = CamcorderSettings(CamcorderMode.OnTape, emptySet())
    codeText(filtersCode(FilterSettings(camcorder = camcorder))) shouldNotContain "camcorder"
    filtersEffects(FilterSettings(camcorder = camcorder)).filterIsInstance<Camcorder>().shouldBeEmpty()
  }

  @Test
  fun `the edit carries the vhs settings`() {
    val settings =
      FilterSettings(tape = TapeSpeed.SP, tracking = TrackingMode.Rolling, snow = 0.35f).withIntensity(0.4f)
    val vhs = filtersEffects(settings).single().shouldBeInstanceOf<Vhs>()
    vhs.intensity shouldBe 0.4f
    vhs.tape shouldBe TapeSpeed.SP
    vhs.tracking shouldBe Tracking.Rolling(snow = 0.35f)
    filtersEffects(settings.copy(tracking = TrackingMode.Off)).single().shouldBeInstanceOf<Vhs>().tracking shouldBe
      Tracking.Off
  }

  @Test
  fun `the edit puts a camcorder on tape ahead of the vhs and a clean one after it`() {
    val onTape = filtersEffects(FilterSettings(camcorder = CamcorderSettings(CamcorderMode.OnTape)))
    onTape.map { it::class } shouldContainExactly listOf(Camcorder::class, Vhs::class)
    val clean = filtersEffects(FilterSettings(camcorder = CamcorderSettings(CamcorderMode.Clean)))
    clean.map { it::class } shouldContainExactly listOf(Vhs::class, Camcorder::class)
    clean
      .last()
      .shouldBeInstanceOf<Camcorder>()
      .elements.size shouldBe 3
  }

  @Test
  fun `a hi8 is built from the settings and written with its own speed`() {
    val settings = FilterSettings(format = TapeFormat.Hi8, hi8 = Hi8Speed.LP, tracking = TrackingMode.Off)

    val hi8 = filtersEffects(settings).single().shouldBeInstanceOf<Hi8>()
    hi8.tape shouldBe Hi8Speed.LP
    hi8.tracking shouldBe Tracking.Off
    codeText(filtersCode(settings)) shouldContain "  hi8 {\n    tape = Hi8Speed.LP\n    tracking = Tracking.Off\n  }"
    codeText(filtersCode(FilterSettings(format = TapeFormat.Hi8, hi8 = Hi8Speed.LP))) shouldContain
      "  hi8 {\n    tape = Hi8Speed.LP\n  }"
    codeText(filtersCode(FilterSettings(format = TapeFormat.Hi8))) shouldContain "  hi8()\n"
    filterSummary(settings) shouldBe "HI8 · LP · OFF"
  }

  @Test
  fun `a betamax is built from the settings and keeps the speed vhs was on`() {
    val settings = FilterSettings(format = TapeFormat.Betamax, tape = TapeSpeed.SP, beta = BetaSpeed.I)

    filtersEffects(settings).single().shouldBeInstanceOf<Betamax>().tape shouldBe BetaSpeed.I
    codeText(filtersCode(settings)) shouldContain "  betamax {\n    tape = BetaSpeed.I\n  }"
    codeText(filtersCode(settings.copy(beta = BetaSpeed.II))) shouldContain "  betamax()\n"
    filterSummary(settings) shouldBe "BETAMAX · I"
    filtersEffects(settings.copy(format = TapeFormat.Vhs)).single().shouldBeInstanceOf<Vhs>().tape shouldBe
      TapeSpeed.SP
  }

  @Test
  fun `a camcorder on tape is written inside the hi8`() {
    val settings = FilterSettings(format = TapeFormat.Hi8, camcorder = CamcorderSettings(CamcorderMode.OnTape))

    codeText(filtersCode(settings)) shouldContain "  hi8 {\n    camcorder {\n      rec()"
    filtersEffects(settings).map { it::class } shouldContainExactly listOf(Camcorder::class, Hi8::class)
  }

  @Test
  fun `a lut parses its table before the clip`() {
    val code = codeText(filtersCode(FilterSettings(look = FilterLook.Lut)))
    code shouldContain "val table = CubeLut.parse(tealAndOrangeCube)"
    code shouldContain "lut(table)"
  }

  @Test
  fun `vhs settings only belong to vhs`() {
    val settings =
      FilterSettings(
        look = FilterLook.Crt,
        tape = TapeSpeed.SP,
        tracking = TrackingMode.Rolling,
        camcorder = CamcorderSettings(CamcorderMode.Clean),
      )
    val code = codeText(filtersCode(settings))
    code shouldContain "crt()"
    code shouldNotContain "TapeSpeed"
    code shouldNotContain "rolling"
    code shouldNotContain "camcorder"
    filtersEffects(settings).filterIsInstance<Camcorder>().shouldBeEmpty()
  }

  @Test
  fun `a glitch builds the look the code names`() {
    val settings = FilterSettings(look = FilterLook.Glitch).withIntensity(0.4f)

    codeText(filtersCode(settings)) shouldContain "glitch(intensity = 0.4f)"
    filtersEffects(settings) shouldBe listOf(Glitch(0.4f))
    filterSummary(settings) shouldBe "GLITCH 0.4"

    val broken = settings.copy(blocks = true)
    codeText(filtersCode(broken)) shouldContain "glitch(intensity = 0.4f, blocks = 1f)"
    filtersEffects(broken) shouldBe listOf(Glitch(0.4f, blocks = 1f))
    filterSummary(broken) shouldBe "GLITCH 0.4 · BLOCKS"
  }

  @Test
  fun `each look keeps its own intensity`() {
    val settings = FilterSettings().withIntensity(0.3f).copy(look = FilterLook.Crt)
    settings.intensity() shouldBe FilterSettings.FULL
    settings.copy(look = FilterLook.Tape).intensity() shouldBe 0.3f
  }

  @Test
  fun `the summary names the look, a moved intensity, the tape, the tracking and the camcorder`() {
    filterSummary(FilterSettings()) shouldBe "VHS · EP"
    filterSummary(
      FilterSettings(tracking = TrackingMode.Off, camcorder = CamcorderSettings(CamcorderMode.Clean)),
    ) shouldBe
      "VHS · EP · OFF · CLEAN"
    filterSummary(FilterSettings(look = FilterLook.Crt).withIntensity(0.5f)) shouldBe "CRT 0.5"
    filterSummary(FilterSettings(look = FilterLook.None)) shouldBe "NO LOOK"
  }

  @Test
  fun `night vision calls its builder by its camel case name`() {
    val settings = FilterSettings(look = FilterLook.NightVision).withIntensity(0.5f)

    codeText(filtersCode(settings)) shouldContain "  nightVision(intensity = 0.5f)"
    filterSummary(settings) shouldBe "NIGHT VISION 0.5"
  }

  @Test
  fun `a pixel look names its palette and no intensity`() {
    val settings = FilterSettings(look = FilterLook.Pixel, colours = PixelColours.Cga)

    codeText(filtersCode(settings)) shouldContain "pixel(palette = PixelPalette.Cga)"
    codeText(filtersCode(settings.copy(colours = PixelColours.Mosaic))) shouldContain "  pixel()"
    filterSummary(settings) shouldBe "PIXEL · CGA"
  }

  @Test
  fun `film takes its super 8 preset and no intensity`() {
    val plain = FilterSettings(look = FilterLook.Film).withIntensity(0.5f)
    val super8 = plain.copy(super8 = true)

    codeText(filtersCode(plain)) shouldContain "  film()"
    codeText(filtersCode(super8)) shouldContain "  add(Film.Super8)"
    filterSummary(plain) shouldBe "FILM"
    filterSummary(super8) shouldBe "FILM · SUPER 8"
  }

  @Test
  fun `the teal and orange table parses`() {
    tealAndOrange.size shouldBe 3
  }

  private fun filtersEffects(settings: FilterSettings) =
    filtersEdit(MediaSource.ofUri("file:///clip.mp4"), settings).effects
}
