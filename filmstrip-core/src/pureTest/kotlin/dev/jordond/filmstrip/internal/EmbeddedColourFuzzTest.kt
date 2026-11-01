package dev.jordond.filmstrip.internal

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Cuts each of [EMBEDDED_COLOUR_CONTAINERS] at every length, so a read that throws, or that reads a part of a container
 * as something else, is caught.
 */
class EmbeddedColourFuzzTest {
  @Test
  fun `every container reads a description whole`() {
    EMBEDDED_COLOUR_CONTAINERS.forEach { pictureColour(it).shouldNotBeNull() }
  }

  @Test
  fun `a cut container gives no description or the whole one and never throws`() {
    for (tagged in EMBEDDED_COLOUR_CONTAINERS) {
      val whole = pictureColour(tagged).summary()
      for (length in tagged.indices) {
        val read = pictureColour(tagged.copyOf(length))
        if (read != null) read.summary() shouldBe whole
      }
    }
  }
}
