package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.RANGE_BLOCK
import dev.jordond.filmstrip.webcodecs.internal.RangeAnswer
import dev.jordond.filmstrip.webcodecs.internal.RangedUrl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The reader that turns a container walk's short reads of a URL into a few range requests, against a server that is
 * a byte array here.
 */
class RangedUrlTest {
  private val file = ByteArray(RANGE_BLOCK * 2 + 1000) { (it * 31 + it / RANGE_BLOCK).toByte() }
  private val asked = mutableListOf<Pair<Long, Int>>()

  @Test
  fun shortReadsInOneBlockCostOneRequest() =
    runTest {
      val url = ranged()

      url.read(100, 64) shouldBe slice(100, 64)
      url.read(40_000, 16) shouldBe slice(40_000, 16)

      asked.shouldContainExactly(0L to RANGE_BLOCK)
    }

  @Test
  fun aReadAcrossTwoBlocksIsJoinedFromBoth() =
    runTest {
      val url = ranged()
      val start = RANGE_BLOCK - 10L

      url.read(start, 64) shouldBe slice(start, 64)

      asked.shouldContainExactly(0L to RANGE_BLOCK, RANGE_BLOCK.toLong() to RANGE_BLOCK)
    }

  @Test
  fun aReadPastTheEndOfTheFileComesBackShort() =
    runTest {
      val start = file.size - 20L

      ranged().read(start, 64) shouldBe slice(start, 20)
    }

  @Test
  fun aReadLongerThanABlockIsAskedForAsItIs() =
    runTest {
      val url = ranged()

      url.read(5, RANGE_BLOCK + 7) shouldBe slice(5, RANGE_BLOCK + 7)

      asked.shouldContainExactly(5L to RANGE_BLOCK + 7)
    }

  @Test
  fun aServerThatSendsTheWholeFileIsAskedOnce() =
    runTest {
      val url =
        RangedUrl { start, count ->
          asked += start to count
          RangeAnswer(file, isWhole = true)
        }
      val start = RANGE_BLOCK + 300L

      url.read(start, 64) shouldBe slice(start, 64)
      url.read(7, RANGE_BLOCK * 2) shouldBe slice(7, RANGE_BLOCK * 2)

      asked.size shouldBe 1
    }

  private fun ranged(): RangedUrl =
    RangedUrl { start, count ->
      asked += start to count
      RangeAnswer(slice(start, count), isWhole = false)
    }

  private fun slice(
    start: Long,
    count: Int,
  ): ByteArray = file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count))
}
