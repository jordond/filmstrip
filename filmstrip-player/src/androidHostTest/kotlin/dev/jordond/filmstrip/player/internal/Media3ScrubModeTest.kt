package dev.jordond.filmstrip.player.internal

import android.os.Handler
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

/**
 * When the player standing is put in media3's scrubbing mode, with the player's thread run inline.
 */
class Media3ScrubModeTest {
  private val set = mutableListOf<Boolean>()
  private val handler =
    mockk<Handler>().also {
      every { it.post(any()) } answers {
        firstArg<Runnable>().run()
        true
      }
    }
  private var playedSinceSeek = false
  private var ended = 0
  private val mode = Media3ScrubMode(handler, holdsOff = { playedSinceSeek }, onEnded = { ended++ }) { set += it }

  @Test
  fun `a scrub ending is heard once neither a scrub nor a shuttle is under way`() {
    mode.scrubbingChanged(true)
    mode.shuttlingChanged(true)
    mode.scrubbingChanged(false)
    ended shouldBe 0

    mode.shuttlingChanged(false)

    ended shouldBe 1
  }

  @Test
  fun `the end of a scrub the player was held out of for all of it is heard too`() {
    playedSinceSeek = true
    mode.scrubbingChanged(true)

    mode.scrubbingChanged(false)

    ended shouldBe 1
  }

  @Test
  fun `a player built while a scrub is under way is put in the mode`() {
    mode.scrubbingChanged(true)
    set.clear()

    mode.carry()

    set shouldBe listOf(true)
  }

  @Test
  fun `a player built while a shuttle is under way is put in the mode`() {
    mode.shuttlingChanged(true)
    set.clear()

    mode.carry()

    set shouldBe listOf(true)
  }

  @Test
  fun `a player built with no scrub or shuttle under way is left as it starts`() {
    mode.scrubbingChanged(true)
    mode.scrubbingChanged(false)
    set.clear()

    mode.carry()

    set.shouldBeEmpty()
  }

  @Test
  fun `a player that has played since its last seek is held out of the mode until a seek is handed over`() {
    playedSinceSeek = true

    mode.scrubbingChanged(true)

    set.shouldBeEmpty()
    mode.entered shouldBe false

    mode.enter()

    set shouldBe listOf(true)
    mode.entered shouldBe true
  }

  @Test
  fun `a player resting on a seek is put in the mode as the scrub begins and only once`() {
    mode.scrubbingChanged(true)
    mode.enter()

    set shouldBe listOf(true)
  }

  @Test
  fun `a scrub that ends while the player is held out of the mode takes it out and leaves nothing to enter`() {
    playedSinceSeek = true
    mode.scrubbingChanged(true)

    mode.scrubbingChanged(false)
    mode.enter()

    set shouldBe listOf(false)
    mode.entered shouldBe false
  }
}
