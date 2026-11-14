package dev.jordond.filmstrip.effect

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class EffectIdTest {
  @Test
  fun `an id prints as its value`() {
    EffectId("acme.filmgrain").toString() shouldBe "acme.filmgrain"
    "refused ${EffectId("acme.filmgrain")}" shouldBe "refused acme.filmgrain"
  }

  @Test
  fun `equality follows the value`() {
    EffectId("acme.filmgrain") shouldBe EffectId("acme.filmgrain")
    EffectId("acme.filmgrain").hashCode() shouldBe EffectId("acme.filmgrain").hashCode()
    EffectId("acme.filmgrain") shouldNotBe EffectId("acme.reverb")
  }
}
