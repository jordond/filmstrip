package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.webcodecs.BROWSER_AAC_ENCODER
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The page's own device, as the WebCodecs backend reads it.
 */
internal suspend fun pageDevice(): DeviceCapabilities =
  webCodecsExportEngine(CONTRACT_COMPONENTS)
    .capabilities()
    .shouldBeInstanceOf<CapabilitiesResult.Success>()
    .capabilities

/**
 * The page's own device with its audio encoders swapped for [audio].
 *
 * Chrome on Linux has no AAC encoder, so a test that is about something other than the encoder hands its planner this
 * with [BROWSER_AAC_ENCODER] and reads the same on every host.
 */
internal suspend fun pageDeviceWith(audio: List<AudioEncoderCapability>): DeviceCapabilities {
  val page = pageDevice()
  return DeviceCapabilities(page.video, audio, page.supportsHdrEncoding, page.concurrentSessionBudget)
}

/**
 * The page's own device with [BROWSER_AAC_ENCODER] as its only audio encoder.
 *
 * It writes audio on every host, Chrome on Linux included.
 */
internal suspend fun pageDeviceWithAac(): DeviceCapabilities = pageDeviceWith(listOf(BROWSER_AAC_ENCODER))
