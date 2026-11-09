package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class FilterSettingsTest {
  @Test
  fun `defaults name no arguments`() {
    codeText(filtersCode(FilterSettings())) shouldContain "  vhs()"
  }

  @Test
  fun `only changed arguments reach the code`() {
    val code = codeText(filtersCode(FilterSettings().withIntensity(0.6f).copy(tape = TapeSpeed.LP)))
    code shouldContain "vhs(intensity = 0.6f, tape = TapeSpeed.LP)"
  }

  @Test
  fun `a lut parses its table before the clip`() {
    val code = codeText(filtersCode(FilterSettings(look = FilterLook.Lut)))
    code shouldContain "val table = CubeLut.parse(tealAndOrangeCube)"
    code shouldContain "lut(table)"
  }

  @Test
  fun `tape only belongs to vhs`() {
    val code = codeText(filtersCode(FilterSettings(look = FilterLook.Crt, tape = TapeSpeed.SP)))
    code shouldContain "crt()"
    code shouldNotContain "TapeSpeed"
  }

  @Test
  fun `each look keeps its own intensity`() {
    val settings = FilterSettings().withIntensity(0.3f).copy(look = FilterLook.Crt)
    settings.intensity() shouldBe FilterSettings.FULL
    settings.copy(look = FilterLook.Vhs).intensity() shouldBe 0.3f
  }

  @Test
  fun `the summary names the look, a moved intensity and the tape`() {
    filterSummary(FilterSettings()) shouldBe "VHS · EP"
    filterSummary(FilterSettings(look = FilterLook.Crt).withIntensity(0.5f)) shouldBe "CRT 0.5"
    filterSummary(FilterSettings(look = FilterLook.None)) shouldBe "NO LOOK"
  }

  @Test
  fun `the teal and orange table parses`() {
    tealAndOrange.size shouldBe 3
  }
}
