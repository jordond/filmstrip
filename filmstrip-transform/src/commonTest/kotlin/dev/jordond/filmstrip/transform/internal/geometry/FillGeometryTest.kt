package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FillGeometryTest {
  @Test
  fun `sigma is the radius times the output's shorter side`() {
    Fill.Blurred(radius = 0.04f).sigmaFor(Size(1080, 1920)) shouldBe (43.2f plusOrMinus 0.01f)
    Fill.Blurred(radius = 0.04f).sigmaFor(Size(1920, 1080)) shouldBe (43.2f plusOrMinus 0.01f)
  }

  @Test
  fun `sigma never falls below one however small the radius`() {
    Fill.Blurred(radius = 0f).sigmaFor(Size(1080, 1920)) shouldBe 1f
  }

  @Test
  fun `the background gain is a multiply so half dim lands halfway to black`() {
    Fill.Blurred(dim = 0.5f).backgroundGain shouldBe 0.5f
    Fill.Blurred(dim = 0f).backgroundGain shouldBe 1f
    Fill.Blurred(dim = 1f).backgroundGain shouldBe 0f
  }

  @Test
  fun `cover grows the source past the output and contain shrinks it inside`() {
    val source = Size(1920, 1080)
    val output = Size(1080, 1920)

    coverScale(source, output) shouldBe (1920f / 1080f plusOrMinus 0.001f)
    containScale(source, output) shouldBe (1080f / 1920f plusOrMinus 0.001f)
    (coverScale(source, output) > containScale(source, output)).shouldBeTrue()
  }

  @Test
  fun `cover and contain agree when the aspects already match`() {
    val source = Size(1920, 1080)
    val output = Size(3840, 2160)

    containScale(source, output) shouldBe (coverScale(source, output) plusOrMinus 0.001f)
  }
}
