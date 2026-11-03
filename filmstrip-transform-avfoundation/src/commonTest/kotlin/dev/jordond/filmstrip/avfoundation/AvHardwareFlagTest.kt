package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.writer.hardwareFlag
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The hardware flag an encoder reports for every pairing of the session answer and the encoder list tag.
 *
 * Runs on every Apple target, so the row VideoToolbox gives below iOS 17.4, no session answer and no list tag, is held
 * without a device on that OS.
 */
class AvHardwareFlagTest {
  @Test
  fun `a session answer wins and an untagged codec with no answer is unknown`() {
    val rows =
      listOf(
        Triple(true, false, true),
        Triple(false, true, false),
        Triple(null, true, true),
        Triple(null, false, null),
      )

    for ((session, listed, expected) in rows) {
      withClue("session $session, listed $listed") { hardwareFlag(session, listed) shouldBe expected }
    }
  }
}
