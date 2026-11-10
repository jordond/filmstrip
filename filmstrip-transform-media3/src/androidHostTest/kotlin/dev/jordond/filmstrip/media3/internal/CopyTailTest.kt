package dev.jordond.filmstrip.media3.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CopyTailTest {
  @Test
  fun `the last sample ends one interval after it starts`() {
    trackEndOf(declared = 0, times = listOf(0, 100, 250)) shouldBe 400
  }

  @Test
  fun `samples stored out of the order they show in count the interval between the last two shown`() {
    trackEndOf(declared = 0, times = listOf(0, 300, 100, 200)) shouldBe 400
  }

  @Test
  fun `a declared length past the last sample wins`() {
    trackEndOf(declared = 500, times = listOf(0, 100, 200)) shouldBe 500
  }

  @Test
  fun `a declared length short of the samples loses to them`() {
    trackEndOf(declared = 100, times = listOf(0, 100, 200)) shouldBe 300
  }

  @Test
  fun `a lone sample ends where it starts`() {
    trackEndOf(declared = 0, times = listOf(700)) shouldBe 700
  }

  @Test
  fun `no samples leave the declared length`() {
    trackEndOf(declared = 900, times = emptyList()) shouldBe 900
  }

  @Test
  fun `a track with neither a length nor a sample has no end`() {
    trackEndOf(declared = 0, times = emptyList()).shouldBeNull()
    trackEndOf(declared = 0, times = listOf(0)).shouldBeNull()
  }
}
