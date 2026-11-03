package dev.jordond.filmstrip.transform.internal.probe

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How a backend's longest texture side is read, kept and floored at what its graphics API promises.
 */
class TextureSideProbeTest {
  @Test
  fun `a context that cannot be read answers the floor`() {
    TextureSideProbe(FLOOR) { null }.side() shouldBe FLOOR
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

    probe.side() shouldBe FLOOR
    probe.side() shouldBe LARGE_SIDE
    probe.side() shouldBe LARGE_SIDE
    withClue("the probe read the context $asked times") { asked shouldBe 2 }
  }

  @Test
  fun `a read below the floor is held to it and one at or above is kept`() {
    for (read in listOf(0, FLOOR / 2, FLOOR - 1)) {
      withClue("a context that read $read") { TextureSideProbe(FLOOR) { read }.side() shouldBe FLOOR }
    }
    for (read in listOf(FLOOR, FLOOR + 1, LARGE_SIDE)) {
      withClue("a context that read $read") { TextureSideProbe(FLOOR) { read }.side() shouldBe read }
    }
  }

  private companion object {
    const val FLOOR = 2048
    const val LARGE_SIDE = 16_384
  }
}
