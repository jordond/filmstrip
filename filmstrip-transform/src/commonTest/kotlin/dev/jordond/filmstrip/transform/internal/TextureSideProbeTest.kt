package dev.jordond.filmstrip.transform.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a backend's longest texture side is read, kept and floored at what its graphics API promises.
 */
class TextureSideProbeTest {
  @Test
  fun `a context that cannot be read answers the floor`() {
    assertEquals(FLOOR, TextureSideProbe(FLOOR) { null }.side())
  }

  @Test
  fun `a failed read is asked again and a successful one is kept`() {
    val reads = mutableListOf(null, LARGE_SIDE, LARGE_SIDE * 2)
    var asked = 0
    val probe =
      TextureSideProbe(FLOOR) {
        asked++
        reads.removeAt(0)
      }

    assertEquals(FLOOR, probe.side())
    assertEquals(LARGE_SIDE, probe.side())
    assertEquals(LARGE_SIDE, probe.side())
    assertEquals(2, asked, "the probe read the context $asked times")
  }

  @Test
  fun `a read below the floor is held to it and one at or above is kept`() {
    for (read in listOf(0, FLOOR / 2, FLOOR - 1)) {
      assertEquals(FLOOR, TextureSideProbe(FLOOR) { read }.side(), "a context that read $read")
    }
    for (read in listOf(FLOOR, FLOOR + 1, LARGE_SIDE)) {
      assertEquals(read, TextureSideProbe(FLOOR) { read }.side(), "a context that read $read")
    }
  }

  private companion object {
    const val FLOOR = 2048
    const val LARGE_SIDE = 16_384
  }
}
