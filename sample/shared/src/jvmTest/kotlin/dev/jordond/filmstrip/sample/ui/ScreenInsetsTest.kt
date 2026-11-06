package dev.jordond.filmstrip.sample.ui

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ScreenInsetsTest {
  @Test
  fun `the flag on its own asks for the tube`() {
    isTubeEmbed("?embed=tv") shouldBe true
  }

  @Test
  fun `the flag among other parameters still counts`() {
    isTubeEmbed("?a=1&embed=tv&b=2") shouldBe true
  }

  @Test
  fun `no flag, another value or a lookalike does not`() {
    isTubeEmbed("") shouldBe false
    isTubeEmbed("?embed=phone") shouldBe false
    isTubeEmbed("?reembed=tv") shouldBe false
  }
}
