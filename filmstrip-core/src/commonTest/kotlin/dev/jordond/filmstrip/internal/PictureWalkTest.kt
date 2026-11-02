package dev.jordond.filmstrip.internal

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

      assertTrue(walking.isCancelled)
      assertFalse(finished)
      assertEquals(1, reads)
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

      assertEquals(0, walk.u8(0))
      assertEquals(WALK_READ_AHEAD, walk.u8(WALK_READ_AHEAD.toLong()))
      assertEquals(2, reads)
    }

  @Test
  fun aWalkInPlaceThatSuspendsFailsRatherThanHandingBackNothing() {
    val failure = assertFailsWith<IllegalStateException> { completeInPlace { suspendCoroutine<Int> { } } }

    assertEquals("A walk over bytes in memory suspended.", failure.message)
  }

  private companion object {
    val FILE = ByteArray(256) { it.toByte() }
    const val FAR = 200L
    const val JUNK: Byte = 0x55
  }
}
