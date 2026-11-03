package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.fixtures.EMBEDDED_COLOR_CONTAINERS
import dev.jordond.filmstrip.picture.fixtures.summary
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Cuts each of [EMBEDDED_COLOR_CONTAINERS] at every length, so a read that throws, or that reads a part of a container
 * as something else, is caught.
 */
class EmbeddedColorFuzzTest {
  @Test
  fun `every container reads a description whole`() {
    EMBEDDED_COLOR_CONTAINERS.forEach { pictureColor(it).shouldNotBeNull() }
  }

  @Test
  fun `a cut container gives no description or the whole one and never throws`() {
    for (tagged in EMBEDDED_COLOR_CONTAINERS) {
      val whole = pictureColor(tagged).summary()
      for (length in tagged.indices) {
        val read = pictureColor(tagged.copyOf(length))
        if (read != null) read.summary() shouldBe whole
      }
    }
  }
}
