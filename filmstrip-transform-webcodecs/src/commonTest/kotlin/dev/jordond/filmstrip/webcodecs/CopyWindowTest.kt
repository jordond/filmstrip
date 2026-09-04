package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.copyWindow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule a stream copy walks its packets by, driven by a written-out decode order rather than by
 * a demuxer, so a stream that reorders its frames can be described exactly.
 *
 * Timestamps here are presentation times in seconds, in the order the packets decode.
 */
class CopyWindowTest {
  @Test
  fun aHeldRunPastTheEndIsWrittenWhenAPacketInsideTheWindowNeedsIt() =
    runTest {
      val written =
        copied(
          endSeconds = 1.0,
          packets = listOf(0.0 to true, 0.4 to false, 1.2 to false, 1.3 to false, 0.8 to false),
        )

      assertEquals(listOf(0.0, 0.4, 1.2, 1.3, 0.8), written)
    }

  @Test
  fun aHeldRunIsDroppedWhenAKeyPacketEndsTheWalkFirst() =
    runTest {
      val written =
        copied(
          endSeconds = 1.0,
          packets = listOf(0.0 to true, 0.4 to false, 1.2 to false, 1.5 to true, 0.8 to false),
        )

      assertEquals(listOf(0.0, 0.4), written)
    }

  @Test
  fun aHeldRunIsDroppedWhenThePacketsRunOut() =
    runTest {
      val written =
        copied(
          endSeconds = 1.0,
          packets = listOf(0.0 to true, 0.4 to false, 1.2 to false, 1.3 to false),
        )

      assertEquals(listOf(0.0, 0.4), written)
    }

  // The window is half open, so the packet on the boundary belongs to whatever comes after the cut.
  // Nothing inside the window follows it here, which is what tells a held packet apart from a
  // written one.
  @Test
  fun aPacketExactlyOnTheEndIsPastIt() =
    runTest {
      val written = copied(endSeconds = 1.0, packets = listOf(0.0 to true, 0.4 to false, 1.0 to false))

      assertEquals(listOf(0.0, 0.4), written)
    }

  /**
   * The timestamps [copyWindow] wrote, in the order it wrote them, for [packets] given as
   * timestamp to whether the packet is a sync sample.
   */
  private suspend fun copied(
    endSeconds: Double,
    packets: List<Pair<Double, Boolean>>,
  ): List<Double> {
    val written = mutableListOf<Double>()
    val remaining = packets.iterator()
    copyWindow(
      endSeconds = endSeconds,
      next = { if (remaining.hasNext()) remaining.next() else null },
      timestampOf = { it.first },
      isKeyPacket = { it.second },
      write = { written += it.first },
    )
    return written
  }
}
