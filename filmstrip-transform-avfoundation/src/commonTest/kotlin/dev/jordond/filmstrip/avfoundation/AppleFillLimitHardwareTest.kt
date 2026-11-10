package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.internal.chainedProber
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the Apple driver says about a fill the kernels cannot draw.
 */
class AppleFillLimitHardwareTest {
  private val prober = chainedProber(ComponentRegistry.Builder().build())

  @Test
  fun `a blurred fill takes the blur kernel limit and a solid one never asks`() {
    var asked = 0
    val driver =
      AvDriver(
        prober,
        blurLimit = {
          asked++
          LIMIT
        },
      )

    driver.fillLimit(Fill.Blurred()) shouldBe LIMIT
    driver.fillLimit(Fill.Black).shouldBeNull()
    asked shouldBe 1
  }

  @Test
  fun `a device that compiles the kernels has no limit`() {
    AvDriver(prober).fillLimit(Fill.Blurred()).shouldBeNull()
  }

  private companion object {
    const val LIMIT = "this device cannot compile a kernel"
  }
}
