package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.export.ExportError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a capabilities answer reads as an HDR encoder, and that a failed query fails rather than skips.
 */
class HdrEncodingTest {
  @Test
  fun answersWhatTheDeviceAdvertises() {
    assertTrue(CapabilitiesResult.Success(deviceWith(supportsHdrEncoding = true)).encodesHdr())
    assertFalse(CapabilitiesResult.Success(deviceWith(supportsHdrEncoding = false)).encodesHdr())
  }

  @Test
  fun failsOnAFailedQueryInsteadOfReadingAsNoHdr() {
    val error = ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, "probe died")
    val failed = CapabilitiesResult.Failure(error)

    val thrown = assertFailsWith<AssertionError> { failed.encodesHdr() }
    assertTrue("probe died" in thrown.message.orEmpty(), "the failure should name the error, was ${thrown.message}")
  }

  private fun deviceWith(supportsHdrEncoding: Boolean): DeviceCapabilities =
    DeviceCapabilities(
      video = emptyList(),
      audio = emptyList(),
      supportsHdrEncoding = supportsHdrEncoding,
      concurrentSessionBudget = null,
    )
}
