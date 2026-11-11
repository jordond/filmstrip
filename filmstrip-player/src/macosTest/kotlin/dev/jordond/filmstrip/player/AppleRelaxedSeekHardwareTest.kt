package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.NEAREST_SEEK_TOLERANCE
import dev.jordond.filmstrip.player.internal.bgraVideoOutput
import dev.jordond.filmstrip.player.internal.frameRate
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.CoreMedia.CMTime
import platform.CoreVideo.CVPixelBufferRelease
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a relaxed seek on the AVFoundation engine shows, read off the item's video output, against what it reports.
 *
 * The long fixture has sync samples far enough apart that AVFoundation settles a relaxed seek away from the request,
 * behind it at one position and ahead of it at another. Each landing has to report the slot of the frame the output
 * displays, and that frame has to be the one a reader decodes at the same time.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleRelaxedSeekHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a relaxed seek reports the slot of the frame it shows`() =
    contractTest(timeout = TIMEOUT) { scope ->
      val edit = longEdit()
      val rate = appleExportLowering(edit).frameRate
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val item = engine.platformPlayer.currentItem ?: fail("the player holds no item")
        val output = bgraVideoOutput()
        item.addOutput(output as AVPlayerItemOutput)

        for (at in RELAXED_POSITIONS) {
          val before = recorder.seekCompletions.size
          engine.seekTo(at, SeekAccuracy.Nearest)
          awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > before }
          engine.drainCallbacks()

          val reported = recorder.seekCompletions.last().position
          val time = item.currentTime()
          awaitContract("a pixel buffer after the seek to $at") { output.hasNewPixelBufferForItemTime(time) }
          val (displayed, shown) = output.displayedAt(time, at)

          withClue("the relaxed seek to $at, shown at $displayed") {
            reported shouldBe outputSlotTime(outputSlotOf(displayed, rate), rate)
            (displayed - at).absoluteValue shouldBeLessThanOrEqualTo NEAREST_SEEK_TOLERANCE
            shown.pixels.contentEquals(appleExportFrame(edit, displayed).pixels) shouldBe true
          }
          recorder.playhead shouldBe reported
        }

        // Measured on this fixture, and what keeps the forward side of the tolerance covered.
        recorder.seekCompletions.last().position shouldBeGreaterThan RELAXED_POSITIONS.last()
      }
    }

  private fun AVPlayerItemVideoOutput.displayedAt(
    time: CValue<CMTime>,
    at: Duration,
  ): Pair<Duration, TestFrame> =
    memScoped {
      val display = alloc<CMTime>()
      val buffer = copyPixelBufferForItemTime(time, itemTimeForDisplay = display.ptr) ?: fail("no buffer after $at")
      try {
        display.readValue().toDuration() to buffer.bgraToTestFrame()
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }

  private fun longEdit(): Composition =
    Composition(
      tracks =
        listOf(Track(listOf(Clip(MediaSource.of(appleFixture(LONG)), TimeRange.of(Duration.ZERO, LONG_LENGTH))))),
    )

  private companion object {
    const val LONG = "apple_export_long.mp4"
    val LONG_LENGTH: Duration = 5.seconds
    val TIMEOUT: Duration = 3.minutes

    // Two that settle behind the request and, last, one that settles ahead of it. The two around 3.9 s settle on the
    // same frame, so one sits between them and the output has a new buffer for each.
    val RELAXED_POSITIONS: List<Duration> = listOf(4011.milliseconds, 1111.milliseconds, 3811.milliseconds)
  }
}
