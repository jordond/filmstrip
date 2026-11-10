package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
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
  fun `each look keeps its own intensity`() {
    val settings = FilterSettings().withIntensity(0.3f).copy(look = FilterLook.Crt)
    settings.intensity() shouldBe FilterSettings.FULL
    settings.copy(look = FilterLook.Vhs).intensity() shouldBe 0.3f
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
  fun `the teal and orange table parses`() {
    tealAndOrange.size shouldBe 3
  }

  private fun filtersEffects(settings: FilterSettings) =
    filtersEdit(MediaSource.ofUri("file:///clip.mp4"), settings).effects
}
