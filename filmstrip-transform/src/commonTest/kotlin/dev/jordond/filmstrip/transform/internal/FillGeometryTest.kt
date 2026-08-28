package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillGeometryTest {
  @Test
  fun `sigma is the radius times the output's shorter side`() {
    assertEquals(43.2f, Fill.Blurred(radius = 0.04f).sigmaFor(Size(1080, 1920)), absoluteTolerance = 0.01f)
    assertEquals(43.2f, Fill.Blurred(radius = 0.04f).sigmaFor(Size(1920, 1080)), absoluteTolerance = 0.01f)
  }

  @Test
  fun `sigma never falls below one however small the radius`() {
    assertEquals(1f, Fill.Blurred(radius = 0f).sigmaFor(Size(1080, 1920)))
  }

  @Test
  fun `the background gain is a multiply so half dim lands halfway to black`() {
    assertEquals(0.5f, Fill.Blurred(dim = 0.5f).backgroundGain)
    assertEquals(1f, Fill.Blurred(dim = 0f).backgroundGain)
    assertEquals(0f, Fill.Blurred(dim = 1f).backgroundGain)
  }

  @Test
  fun `cover grows the source past the output and contain shrinks it inside`() {
    val source = Size(1920, 1080)
    val output = Size(1080, 1920)

    assertEquals(1920f / 1080f, coverScale(source, output), absoluteTolerance = 0.001f)
    assertEquals(1080f / 1920f, containScale(source, output), absoluteTolerance = 0.001f)
    assertTrue(coverScale(source, output) > containScale(source, output))
  }

  @Test
  fun `cover and contain agree when the aspects already match`() {
    val source = Size(1920, 1080)
    val output = Size(3840, 2160)

    assertEquals(coverScale(source, output), containScale(source, output), absoluteTolerance = 0.001f)
  }
}
