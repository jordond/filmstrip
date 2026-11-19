package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class HdrReadbackLimitTest {
  @Test
  fun `media3 reads a preview back as HDR from API 34`() {
    hdrReadbackLimit(34).shouldBeNull()
    hdrReadbackLimit(36).shouldBeNull()
  }

  @Test
  fun `below API 34 the read is refused with the device's level`() {
    val limit = hdrReadbackLimit(31)
    limit shouldNotBe null
    limit!! shouldContain "API 34"
    limit shouldContain "API 31"
    hdrReadbackLimit(33)!! shouldContain "API 33"
  }
}
