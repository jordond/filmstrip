package dev.jordond.filmstrip.media3.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How the device's longest texture side is floored at what OpenGL ES 3.0 promises.
 */
class Media3TextureSideProbeTest {
  @Test
  fun `a device that cannot be asked reads the es 3 floor`() {
    media3TextureSideProbe { null }.side() shouldBe ES3_MIN_TEXTURE_SIDE
  }

  @Test
  fun `a read below the es 3 floor is held to it`() {
    media3TextureSideProbe { ES3_MIN_TEXTURE_SIDE - 1 }.side() shouldBe ES3_MIN_TEXTURE_SIDE
  }

  @Test
  fun `a device that answers after a failed read is kept at its answer`() {
    val reads = mutableListOf(null, LARGE_SIDE)
    val probe = media3TextureSideProbe { reads.removeAt(0) }

    probe.side() shouldBe ES3_MIN_TEXTURE_SIDE
    probe.side() shouldBe LARGE_SIDE
    probe.side() shouldBe LARGE_SIDE
  }

  private companion object {
    const val LARGE_SIDE = 16_384
  }
}
