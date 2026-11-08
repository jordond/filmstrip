package dev.jordond.filmstrip.transform.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ExportProgressTest {
  @Test
  fun `a quarter done after ten seconds has thirty left`() {
    remainingAt(0.25f, 10.seconds) shouldBe 30.seconds
  }

  @Test
  fun `most of the way through has the rest left at the same rate`() {
    remainingAt(0.75f, 9.seconds) shouldBe 3.seconds
  }

  @Test
  fun `a finished export has nothing left`() {
    remainingAt(1f, 8.seconds).shouldBeNull()
  }
}
