package dev.jordond.filmstrip.media3.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the device's longest texture side is floored at what OpenGL ES 3.0 promises.
 */
class Media3TextureSideProbeTest {
  @Test
  fun `a device that cannot be asked reads the es 3 floor`() {
    assertEquals(ES3_MIN_TEXTURE_SIDE, media3TextureSideProbe { null }.side())
  }

  @Test
  fun `a read below the es 3 floor is held to it`() {
    assertEquals(ES3_MIN_TEXTURE_SIDE, media3TextureSideProbe { ES3_MIN_TEXTURE_SIDE - 1 }.side())
  }

  @Test
  fun `a device that answers after a failed read is kept at its answer`() {
    val reads = mutableListOf(null, LARGE_SIDE)
    val probe = media3TextureSideProbe { reads.removeAt(0) }

    assertEquals(ES3_MIN_TEXTURE_SIDE, probe.side())
    assertEquals(LARGE_SIDE, probe.side())
    assertEquals(LARGE_SIDE, probe.side())
  }

  private companion object {
    const val LARGE_SIDE = 16_384
  }
}
