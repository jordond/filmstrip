package dev.jordond.filmstrip.media3.internal.bounds

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class GainmapLimitTest {
  @Test
  fun `API 29 to 33 cannot lay a solid on an HDR grade`() {
    (29..33).forEach { gainmapLimit(it).shouldNotBeNull() shouldContain "API $it" }
  }

  @Test
  fun `API 34 and later lay a solid on an HDR grade`() {
    listOf(34, 35, 36).forEach { gainmapLimit(it).shouldBeNull() }
  }
}
