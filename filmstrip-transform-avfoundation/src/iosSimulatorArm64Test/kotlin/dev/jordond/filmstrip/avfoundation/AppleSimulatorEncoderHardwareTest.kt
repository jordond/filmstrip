package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.test.encodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The iOS simulator's own encoder answers. VideoToolbox's session there does not say whether an encoder is hardware and
 * its encoder list tags none, so every encoder reports no hardware flag. It advertises an HDR encoder and writes a
 * Display P3 HEIC, as measured on it.
 *
 * The hardware claim is held on macOS and an iPhone by AppleHardwareEncoderTest.
 */
class AppleSimulatorEncoderHardwareTest {
  @Test
  fun `the simulator reports no hardware flag for any video encoder`() =
    runTest(timeout = TIMEOUT) {
      val result = withContext(Dispatchers.Default) { Filmstrip { avFoundationBackend() }.capabilities() }
      val video = result.shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities.video

      withClue("expected at least one video encoder") { video.isNotEmpty().shouldBeTrue() }
      withClue(
        "the simulator said whether an encoder is hardware in $video, so VideoToolbox's limit there no longer holds",
      ) {
        video.all { it.isHardwareAccelerated == null }.shouldBeTrue()
      }
    }

  @Test
  fun `the simulator advertises an hdr encoder`() =
    runTest(timeout = TIMEOUT) {
      val encodesHdr = withContext(Dispatchers.Default) { Filmstrip { avFoundationBackend() }.encodesHdr() }
      withClue("the simulator's own measured answer is an HDR encoder, and it advertised none") {
        encodesHdr.shouldBeTrue()
      }
    }

  @Test
  fun `imageio writes a display p3 heic on the simulator`() {
    withClue("the simulator's own measured answer is a written HEIC, and it wrote none") {
      imageIoWrites("public.heic").shouldBeTrue()
    }
  }

  private companion object {
    val TIMEOUT = 5.minutes
  }
}
