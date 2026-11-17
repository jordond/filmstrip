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
  private val mode = Media3ScrubMode(handler) { set += it }

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
}
