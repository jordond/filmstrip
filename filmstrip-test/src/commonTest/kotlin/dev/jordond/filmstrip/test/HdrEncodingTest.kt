package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How a capabilities answer reads as an HDR encoder, and that a failed query fails rather than skips.
 */
class HdrEncodingTest {
  @Test
  fun answersWhatTheDeviceAdvertises() {
    CapabilitiesResult.Success(deviceWith(supportsHdrEncoding = true)).encodesHdr() shouldBe true
    CapabilitiesResult.Success(deviceWith(supportsHdrEncoding = false)).encodesHdr() shouldBe false
  }

  @Test
  fun failsOnAFailedQueryInsteadOfReadingAsNoHdr() {
    val error = FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, "probe died")
    val failed = CapabilitiesResult.Failure(error)

    val thrown = assertFailsWith<AssertionError> { failed.encodesHdr() }
    withClue("the failure should name the error, was ${thrown.message}") {
      ("probe died" in thrown.message.orEmpty()) shouldBe true
    }
  }

  private fun deviceWith(supportsHdrEncoding: Boolean): DeviceCapabilities =
    DeviceCapabilities(
      video = emptyList(),
      audio = emptyList(),
      supportsHdrEncoding = supportsHdrEncoding,
      concurrentSessionBudget = null,
    )
}
