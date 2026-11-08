package dev.jordond.filmstrip.picture

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PictureWalkTest {
  // The read cancels the coroutine walking it, so the walk's next read is never asked for and the cancellation is not
  // taken for a malformed picture.
  @Test
  fun aWalkWhoseCoroutineIsCancelledAsksForNoMoreReads() =
    runTest {
      var reads = 0
      var finished = false
      val walking =
        launch {
          val picture =
            RangedPicture(FILE.size.toLong()) { start, count ->
              reads++
              currentCoroutineContext().cancel()
              FILE.copyOfRange(start.toInt(), start.toInt() + count)
            }
          walkPicture(picture) { u8(0) + u8(FAR) }
          finished = true
        }
      walking.join()

      walking.isCancelled.shouldBeTrue()
      finished.shouldBeFalse()
      reads shouldBe 1
    }

  // Every byte of the file is its own offset, so a byte taken from the extra the reader hands back reads as JUNK.
  @Test
  fun aReaderThatHandsBackMoreThanItWasAskedForIsHeldToWhatItWasAsked() =
    runTest {
      var reads = 0
      val picture =
        RangedPicture(FILE.size.toLong()) { start, count ->
          reads++
          FILE.copyOfRange(start.toInt(), start.toInt() + count) + ByteArray(WALK_READ_AHEAD) { JUNK }
        }
      val walk = PictureWalk(picture)

      walk.u8(0) shouldBe 0
      walk.u8(WALK_READ_AHEAD.toLong()) shouldBe WALK_READ_AHEAD
      reads shouldBe 2
    }

  @Test
  fun aWalkInPlaceThatSuspendsFailsRatherThanHandingBackNothing() {
    val failure = assertFailsWith<IllegalStateException> { completeInPlace { suspendCoroutine<Int> { } } }

    failure.message shouldBe "A walk over bytes in memory suspended."
  }

  private companion object {
    val FILE = ByteArray(256) { it.toByte() }
    const val FAR = 200L
    const val JUNK: Byte = 0x55
  }
}
