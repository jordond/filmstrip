package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.playback.internal.isExternalRateChange
import io.kotest.matchers.shouldBe
import platform.AVFoundation.AVPlayerRateDidChangeReasonAppBackgrounded
import platform.AVFoundation.AVPlayerRateDidChangeReasonAudioSessionInterrupted
import platform.AVFoundation.AVPlayerRateDidChangeReasonSetRateCalled
import platform.AVFoundation.AVPlayerRateDidChangeReasonSetRateFailed
import kotlin.test.Test

/**
 * The mapping from a rate change reason to whether the engine acts on it.
 *
 * The engine reads this before it reports anything, and the two reasons it acts on sit beside two
 * it must not. `setRateCalled` is the one that matters most: the engine's own `pause` posts it on
 * the way through every occasion it handles, so a filter that let it past would turn each pause
 * back into an external change.
 *
 * The constants come from AVFoundation rather than from string literals, so a reason the system
 * renames moves this suite with it.
 */
class RateChangeReasonTest {
  @Test
  fun `an audio session interruption is external`() {
    isExternalRateChange(AVPlayerRateDidChangeReasonAudioSessionInterrupted) shouldBe true
  }

  @Test
  fun `the app being backgrounded is external`() {
    isExternalRateChange(AVPlayerRateDidChangeReasonAppBackgrounded) shouldBe true
  }

  @Test
  fun `a rate somebody asked for is not external`() {
    isExternalRateChange(AVPlayerRateDidChangeReasonSetRateCalled) shouldBe false
    isExternalRateChange(AVPlayerRateDidChangeReasonSetRateFailed) shouldBe false
  }

  @Test
  fun `a missing or unrecognised reason is not external`() {
    isExternalRateChange(null) shouldBe false
    isExternalRateChange("") shouldBe false
    isExternalRateChange("AVPlayerRateDidChangeReasonFromALaterSystem") shouldBe false
  }
}
