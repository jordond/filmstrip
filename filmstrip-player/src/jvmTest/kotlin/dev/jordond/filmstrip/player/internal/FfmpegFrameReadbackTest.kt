package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.jvmFixtureComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegFrameReadbackTest {
  private val plan = fixturePlan()
  private val rate = plan.frameRate
  private val edit = jvmFixtureComposition()

  @Test
  fun `a windowed stream hands back its first frame on the slot it opened on`() =
    runTest {
      val stream = IndexedStream(slotTime(SLOT), frames = 3)

      val frame = stream.frameAt(slotTime(SLOT), rate).shouldNotBeNull()

      frame.position shouldBe slotTime(SLOT)
      frame.pixels.single() shouldBe 0.toByte()
    }

  @Test
  fun `a stream opened at its head is read forward to the slot a position rounds to`() =
    runTest {
      val stream = IndexedStream(Duration.ZERO, frames = 40)
      val between = slotTime(SLOT) + slotTime(1) / 3

      val frame = stream.frameAt(between, rate).shouldNotBeNull()

      outputSlotOf(between, rate) shouldBe SLOT
      frame.position shouldBe slotTime(SLOT)
      frame.pixels.single() shouldBe SLOT.toByte()
    }

  @Test
  fun `a stream that ends before the slot hands back nothing`() =
    runTest {
      IndexedStream(Duration.ZERO, frames = SLOT.toInt()).frameAt(slotTime(SLOT), rate).shouldBeNull()
    }

  @Test
  fun `nothing loaded reads back as an unreadable source`() =
    runTest {
      val planner = StreamPlanner { IndexedStream(it, 1).opened() }

      val result = read(readback(planner, plan = null), slotTime(SLOT))

      result.shouldBeInstanceOf<ReadbackResult.Failure>().error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
      planner.opened.shouldBeEmpty()
    }

  @Test
  fun `a playhead a tick grid floored short of the frame on screen reads that frame without spawning`() =
    runTest {
      val planner = StreamPlanner { IndexedStream(it, 1).opened() }
      val standing = PreviewFrame(slotTime(SLOT), byteArrayOf(STANDING))

      val result = read(readback(planner, presented = standing), slotTime(SLOT) - slotTime(1) * 3 / 4)

      val frame = result.shouldBeInstanceOf<ReadbackResult.Success>().frame
      frame.pixels.single() shouldBe STANDING
      frame.presentationTime shouldBe slotTime(SLOT)
      planner.opened.shouldBeEmpty()
    }

  @Test
  fun `a position that rounds to the frame on screen reads that frame without spawning`() =
    runTest {
      val planner = StreamPlanner { IndexedStream(it, 1).opened() }
      val standing = PreviewFrame(slotTime(SLOT), byteArrayOf(STANDING))
      val snap: (Duration) -> Duration = { outputSlotTime(outputSlotOf(it, rate), rate) }

      val result = read(readback(planner, presented = standing, snap = snap), slotTime(SLOT) + slotTime(1) / 3)

      result
        .shouldBeInstanceOf<ReadbackResult.Success>()
        .frame.pixels
        .single() shouldBe STANDING
      planner.opened.shouldBeEmpty()
    }

  @Test
  fun `the slots either side of the frame on screen are each rendered`() =
    runTest {
      for (slot in listOf(SLOT - 1, SLOT + 1)) {
        val planner = StreamPlanner { IndexedStream(it, 1).opened() }
        val standing = PreviewFrame(slotTime(SLOT), byteArrayOf(STANDING))

        val frame =
          read(readback(planner, presented = standing), slotTime(slot))
            .shouldBeInstanceOf<ReadbackResult.Success>()
            .frame

        frame.pixels.single() shouldBe 0.toByte()
        frame.presentationTime shouldBe slotTime(slot)
        planner.opened shouldBe listOf(slotTime(slot))
      }
    }

  @Test
  fun `a position away from the one on screen is rendered by a process of its own and closed after`() =
    runTest {
      val streams = mutableListOf<IndexedStream>()
      val planner = StreamPlanner { at -> IndexedStream(at, 1).also { streams += it }.opened() }
      val standing = PreviewFrame(slotTime(SLOT - 2), byteArrayOf(STANDING))

      val result = read(readback(planner, presented = standing), slotTime(SLOT))

      val frame = result.shouldBeInstanceOf<ReadbackResult.Success>().frame
      frame.pixels.single() shouldBe 0.toByte()
      frame.presentationTime shouldBe slotTime(SLOT)
      frame.size shouldBe plan.resolved.output.size
      frame.colorSpace shouldBe ColorSpace.Bt709
      frame.renderScale shouldBe plan.info.renderScale
      planner.opened shouldBe listOf(slotTime(SLOT))
      streams.single().closed shouldBe true
    }

  @Test
  fun `a position is snapped before it is rendered`() =
    runTest {
      val planner = StreamPlanner { IndexedStream(it, 1).opened() }
      val snap: (Duration) -> Duration = { outputSlotTime(outputSlotOf(it, rate), rate) }

      read(readback(planner, snap = snap), slotTime(SLOT) + 4.milliseconds)

      planner.opened shouldBe listOf(slotTime(SLOT))
    }

  @Test
  fun `a process that runs out first fails the readback and is still closed`() =
    runTest {
      val streams = mutableListOf<IndexedStream>()
      val planner = StreamPlanner { at -> IndexedStream(at, 0).also { streams += it }.opened() }

      val result = read(readback(planner), slotTime(SLOT))

      result.shouldBeInstanceOf<ReadbackResult.Failure>().error.shouldBeInstanceOf<PlaybackError.Underlying>()
      streams.single().closed shouldBe true
    }

  // The source went between the plan and the process, so only the exit can say which one it was.
  @Test
  fun `a process that fails on a missing source fails the readback with the source the stream names`() =
    runTest {
      val stream =
        IndexedStream(slotTime(SLOT), frames = 0, status = FAILED, stderr = NO_SUCH_FILE, failure = GONE)
      val planner = StreamPlanner { stream.opened() }

      val result = read(readback(planner), slotTime(SLOT))

      val error = result.shouldBeInstanceOf<ReadbackResult.Failure>().error
      error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>()
      error shouldBe GONE.toPlaybackError()
    }

  @Test
  fun `a process that could not open fails the readback with why`() =
    runTest {
      val planner = StreamPlanner { PreviewStreamResult.Refused(FilmstripError.ToolchainMissing("ffmpeg", null, WHY)) }

      val result = read(readback(planner), slotTime(SLOT))

      val error = result.shouldBeInstanceOf<ReadbackResult.Failure>().error
      error.shouldBeInstanceOf<PlaybackError.DecoderUnavailable>().message shouldBe WHY
    }

  @Test
  fun `a request cancelled before it runs is never answered`() =
    runTest {
      val planner = StreamPlanner { IndexedStream(it, 1).opened() }
      val answers = mutableListOf<ReadbackResult>()

      readback(planner).requestFrame(slotTime(SLOT)) { answers += it }.cancel()
      runCurrent()

      answers.shouldBeEmpty()
      planner.opened.shouldBeEmpty()
    }

  private fun TestScope.readback(
    planner: StreamPlanner,
    plan: PreviewPlan? = this@FfmpegFrameReadbackTest.plan,
    composition: Composition? = edit,
    presented: PreviewFrame? = null,
    snap: (Duration) -> Duration = { it },
  ): FfmpegFrameReadback = FfmpegFrameReadback(this, planner, { plan }, { composition }, { presented }, snap)

  private fun TestScope.read(
    readback: FfmpegFrameReadback,
    at: Duration,
  ): ReadbackResult {
    var answer: ReadbackResult? = null
    readback.requestFrame(at) { answer = it }
    runCurrent()
    return answer.shouldNotBeNull()
  }

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, rate)

  private fun IndexedStream.opened(): PreviewStreamResult = PreviewStreamResult.Opened(this)

  private companion object {
    // Well inside the fixture's grid, clear of either end of it.
    const val SLOT = 21L

    const val STANDING: Byte = 0x5A

    const val WHY = "ffmpeg was not found on the PATH."

    // What ffmpeg exits with on an input it could not open.
    const val FAILED = 1

    const val NO_SUCH_FILE = "scripted.mp4: No such file or directory"

    val GONE = FilmstripError.SourceUnreadable(source = "scripted.mp4", message = NO_SUCH_FILE)
  }
}
