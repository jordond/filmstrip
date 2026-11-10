package dev.jordond.filmstrip.media3.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.test.Test

class HdrDecodeLimitTest {
  @Test
  fun `media3 decodes no HDR below API 29`() {
    (26..28).forEach { hdrDecodeLimit(it).shouldNotBeNull() }
  }

  @Test
  fun `API 29 and later decode HDR`() {
    listOf(29, 30, 33, 36).forEach { hdrDecodeLimit(it).shouldBeNull() }
  }
}
