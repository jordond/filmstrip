package dev.jordond.filmstrip.transform.internal.copy

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The decode-order prefix a copy writes to cover the grid, read off packet lists laid out the way an H264 stream with
 * two B-frames per reference orders them.
 */
class CopyPrefixTest {
  // A window away from zero, so a cap read off the window's end alone, or off its length without the margin, misses.
  @Test
  fun `caps a packet read at the window and its margin at the maximum rate`() {
    copyPacketCap(4.seconds, 6_500.milliseconds) shouldBe 3_500
    copyPacketCap(4.seconds, 4_000_001.seconds / 1_000_000) shouldBe 1_001
  }

  // Frame 5 decodes after frames 8 and 6, which it references, so both are written ahead of it.
  @Test
  fun `a reorder group straddling the grid end writes the references past it`() {
    copyPrefixOf(reordered(frames = 40), Duration.ZERO, videoSlots = 6, frameRate = RATE) should packets(6 + 2)
  }

  // Frames 0 to 4 are the first five packets in decode order, so nothing past the grid is needed.
  @Test
  fun `a reorder group ending on the grid end writes the grid alone`() {
    copyPrefixOf(reordered(frames = 40), Duration.ZERO, videoSlots = 5, frameRate = RATE) should packets(5)
  }

  @Test
  fun `a stream with no B-frames writes the grid alone`() {
    copyPrefixOf(inOrder(frames = 40), Duration.ZERO, videoSlots = 17, frameRate = RATE) should packets(17)
  }

  // A read that opened on an earlier sync sample hands over packets the copy never writes, so the count starts on the
  // sync sample the copy opens on and the grid counts from there.
  @Test
  fun `the count starts on the sync sample the copy opens on`() {
    val packets = reordered(frames = 60, keyEvery = 20)

    copyPrefixOf(packets, frameTime(20), videoSlots = 6, frameRate = RATE) should packets(6 + 2)
    copyPrefixOf(packets, frameTime(20), videoSlots = 5, frameRate = RATE) should packets(5)
  }

  // Frames 0 to 6 and 8 are written for a six slot grid, so the video shows through frame 8 and stops on frame 9.
  @Test
  fun `the counted video stops showing on the first frame presented after it`() {
    val prefix =
      copyPrefixOf(reordered(frames = 40), Duration.ZERO, videoSlots = 6, frameRate = RATE)
        .shouldBeInstanceOf<CopyPrefix.Packets>()

    prefix.end shouldBe frameTime(9)
  }

  @Test
  fun `counted video that reaches the listing's end has no end of its own`() {
    copyPrefixOf(inOrder(frames = 10), Duration.ZERO, videoSlots = 30, frameRate = RATE) should
      runs(Run(frameTime(0), frameTime(9), null))
  }

  @Test
  fun `frames written in presentation order make one run`() {
    copyPrefixOf(inOrder(frames = 40), Duration.ZERO, videoSlots = 17, frameRate = RATE) should
      runs(Run(frameTime(0), frameTime(16), frameTime(17)))
  }

  // Frame 7 decodes after frame 5, the last on the grid, so it is left out between frame 6 and the reference 8.
  @Test
  fun `a frame left out between written ones splits the runs`() {
    copyPrefixOf(reordered(frames = 40), Duration.ZERO, videoSlots = 6, frameRate = RATE) should
      runs(
        Run(frameTime(0), frameTime(6), frameTime(7)),
        Run(frameTime(8), frameTime(8), frameTime(9)),
      )
  }

  // A pyramid of eight decodes 8, 4 and 2 ahead of 1, so a grid ending on frame 2 leaves out 3 and 5 to 7.
  @Test
  fun `several frames left out make a run between each`() {
    copyPrefixOf(pyramid(frames = 40), Duration.ZERO, videoSlots = 3, frameRate = RATE) should
      runs(
        Run(frameTime(0), frameTime(2), frameTime(3)),
        Run(frameTime(4), frameTime(4), frameTime(5)),
        Run(frameTime(8), frameTime(8), frameTime(9)),
      )
  }

  // A copy opening on a later sync sample has runs on the source's own timeline, not counted from zero.
  @Test
  fun `the runs of a copy opening mid source sit on the source's timeline`() {
    copyPrefixOf(reordered(frames = 60, keyEvery = 20), frameTime(20), videoSlots = 6, frameRate = RATE) should
      runs(
        Run(frameTime(20), frameTime(26), frameTime(27)),
        Run(frameTime(28), frameTime(28), frameTime(29)),
      )
  }

  // Frames 6 and 8 are written only as references for frame 5, so the frames on the grid stop showing on frame 6.
  @Test
  fun `the shown runs hold the counted frames on the grid alone`() {
    copyPrefixOf(reordered(frames = 40), Duration.ZERO, videoSlots = 6, frameRate = RATE) should
      shown(Run(frameTime(0), frameTime(5), frameTime(6)))
  }

  // Frames 4 and 8 sit between and past the left out ones, and none of them is on a three slot grid.
  @Test
  fun `the shown runs leave out every reference past the grid`() {
    copyPrefixOf(pyramid(frames = 40), Duration.ZERO, videoSlots = 3, frameRate = RATE) should
      shown(Run(frameTime(0), frameTime(2), frameTime(3)))
  }

  @Test
  fun `the shown runs of a copy opening mid source count the grid from the opening`() {
    copyPrefixOf(reordered(frames = 60, keyEvery = 20), frameTime(20), videoSlots = 6, frameRate = RATE) should
      shown(Run(frameTime(20), frameTime(25), frameTime(26)))
  }

  @Test
  fun `the shown runs match the runs when nothing is written past the grid`() {
    val prefix =
      copyPrefixOf(inOrder(frames = 40), Duration.ZERO, videoSlots = 17, frameRate = RATE)
        .shouldBeInstanceOf<CopyPrefix.Packets>()

    prefix.shown.map { it.toRun() } shouldBe prefix.runs.map { it.toRun() }
  }

  // The read opened on a sync sample before the copy's, whose frames are never shown, so no run starts before it.
  @Test
  fun `no run starts before the copy opens`() {
    val runs = copyPresentationRunsOf(inOrder(frames = 10), count = 6, opensAt = frameTime(2)).map { it.toRun() }

    runs shouldBe listOf(Run(frameTime(2), frameTime(5), frameTime(6)))
  }

  // ffprobe lists every packet of an AVI holding B-frames with no presentation time.
  @Test
  fun `a listing with no presentation times is untimed`() {
    val untimed = reordered(frames = 40).map { CopyPacket(Duration.INFINITE, it.key) }

    copyPrefixOf(untimed, Duration.ZERO, videoSlots = 6, frameRate = RATE) shouldBe CopyPrefix.Untimed
  }

  // One packet without a time could sit anywhere on the grid, so the count would be a guess.
  @Test
  fun `one packet with no time in the middle makes the listing untimed`() {
    val packets = inOrder(frames = 10).toMutableList().apply { set(5, CopyPacket(Duration.INFINITE, key = false)) }

    copyPrefixOf(packets, Duration.ZERO, videoSlots = 8, frameRate = RATE) shouldBe CopyPrefix.Untimed
  }

  @Test
  fun `an empty listing is untimed`() {
    copyPrefixOf(emptyList(), Duration.ZERO, videoSlots = 6, frameRate = RATE) shouldBe CopyPrefix.Untimed
  }

  @Test
  fun `a grid no packet lands on is none on grid rather than a count of zero`() {
    copyPrefixOf(inOrder(frames = 10), frameTime(4), videoSlots = 0, frameRate = RATE) shouldBe CopyPrefix.NoneOnGrid
  }

  // Every packet is timed, but the whole listing sits before the copy opens.
  @Test
  fun `timed packets all before the opening are none on grid`() {
    copyPrefixOf(inOrder(frames = 10), frameTime(20), videoSlots = 6, frameRate = RATE) shouldBe CopyPrefix.NoneOnGrid
  }

  private companion object {
    const val RATE = 30

    fun packets(count: Int): Matcher<CopyPrefix> =
      Matcher { prefix ->
        MatcherResult(
          (prefix as? CopyPrefix.Packets)?.count == count,
          { "expected $count packets but was ${(prefix as? CopyPrefix.Packets)?.count ?: prefix}" },
          { "expected anything but $count packets" },
        )
      }

    data class Run(
      val start: Duration,
      val lastFrameAt: Duration,
      val end: Duration?,
    )

    fun CopyRun.toRun(): Run = Run(start, lastFrameAt, end)

    fun runs(vararg expected: Run): Matcher<CopyPrefix> =
      Matcher { prefix ->
        val actual = (prefix as? CopyPrefix.Packets)?.runs?.map { it.toRun() }
        MatcherResult(
          actual == expected.toList(),
          { "expected runs ${expected.toList()} but was ${actual ?: prefix}" },
          { "expected anything but runs ${expected.toList()}" },
        )
      }

    fun shown(vararg expected: Run): Matcher<CopyPrefix> =
      Matcher { prefix ->
        val actual = (prefix as? CopyPrefix.Packets)?.shown?.map { it.toRun() }
        MatcherResult(
          actual == expected.toList(),
          { "expected shown runs ${expected.toList()} but was ${actual ?: prefix}" },
          { "expected anything but shown runs ${expected.toList()}" },
        )
      }

    fun frameTime(frame: Int): Duration = frame.seconds / RATE

    // A sync sample, then groups of eight in hierarchical B-frame order: the reference, the middle, the quarter, the
    // frame before it, then the rest.
    fun pyramid(frames: Int): List<CopyPacket> =
      listOf(CopyPacket(frameTime(0), key = true)) +
        (1 until frames)
          .chunked(8)
          .flatMap { run ->
            if (run.size < 8) run else listOf(7, 3, 1, 0, 2, 5, 4, 6).map { run[it] }
          }.map { CopyPacket(frameTime(it), key = false) }

    fun inOrder(frames: Int): List<CopyPacket> = (0 until frames).map { CopyPacket(frameTime(it), key = it == 0) }

    // A sync sample, then groups of four in the order x264 writes them with two B-frames: the reference four frames on,
    // the B-frame between, then the two either side of it.
    fun reordered(
      frames: Int,
      keyEvery: Int = frames,
    ): List<CopyPacket> =
      (0 until frames step keyEvery).flatMap { key ->
        val group = (key + 1 until minOf(key + keyEvery, frames)).toList()
        listOf(CopyPacket(frameTime(key), key = true)) +
          group
            .chunked(4)
            .flatMap { run ->
              if (run.size < 4) run else listOf(run[3], run[1], run[0], run[2])
            }.map { CopyPacket(frameTime(it), key = false) }
      }
  }
}
