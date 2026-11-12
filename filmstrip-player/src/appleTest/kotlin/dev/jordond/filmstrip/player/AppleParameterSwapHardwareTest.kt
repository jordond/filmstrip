package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.bgraVideoOutput
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.videoComposition
import platform.CoreMedia.CMTime
import platform.CoreVideo.CVPixelBufferRelease
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the item's own video output shows when an edit changes parameters only, which is what a player layer shows.
 *
 * The engine's readback draws its frames apart from the item, so it reads the new parameters whether or not the item
 * ever drew them. The output is fed by the item the way a layer is.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleParameterSwapHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a paused parameter swap draws the held frame again with the new parameters`() =
    swapTest { engine, recorder, item, output ->
      val dim = output.awaitFrame(item, "the held frame")
      assertFramesSimilar(
        appleFixtureExportFrame(DIM_EDIT, HELD_AT),
        dim,
        message = "the held frame is not the dim one",
      )
      val redraws = RedrawCounter().also { engine.addListener(it) }
      val mark = recorder.mark()
      val playhead = recorder.playhead
      val loads = engine.platformLoads

      engine.awaitComposition(BRIGHT_EDIT).shouldBeInstanceOf<SetCompositionResult.Success>()
      val bright = output.awaitFrame(item, "the held frame under the new parameters")

      bright.meanLevel() shouldBeGreaterThan dim.meanLevel() + LEVEL_STEP
      assertFramesSimilar(
        expected = appleFixtureExportFrame(BRIGHT_EDIT, HELD_AT),
        actual = bright,
        message = "the redrawn frame is not the bright one",
      )
      redraws.count shouldBe 1
      engine.platformLoads shouldBe loads
      recorder.playhead shouldBe playhead
      recorder.statesSince(mark).forEach { it.status shouldBe PlaybackStatus.Ready }
      recorder.lastState.playWhenReady shouldBe false
    }

  @Test
  fun `a paused parameter swap draws the held frame once`() =
    swapTest { engine, _, item, output ->
      output.awaitFrame(item, "the held frame")

      engine.awaitComposition(BRIGHT_EDIT).shouldBeInstanceOf<SetCompositionResult.Success>()
      output.awaitFrame(item, "the held frame under the new parameters")
      settle()

      output.hasNewPixelBufferForItemTime(item.currentTime()) shouldBe false
    }

  @Test
  fun `a playing parameter swap leaves the item its composition`() =
    swapTest { engine, recorder, item, _ ->
      val composition = item.videoComposition
      engine.play()
      awaitContract("the playhead to advance") {
        recorder.lastState.isPlaying && (recorder.playhead ?: Duration.ZERO) > HELD_AT
      }

      engine.awaitComposition(BRIGHT_EDIT).shouldBeInstanceOf<SetCompositionResult.Success>()

      item.videoComposition shouldBe composition
      recorder.lastState.playWhenReady shouldBe true
    }

  @Test
  fun `a paused parameter swap that starts playback leaves the item its composition`() =
    swapTest { engine, recorder, item, _ ->
      val composition = item.videoComposition

      engine.awaitComposition(BRIGHT_EDIT, playWhenReady = true).shouldBeInstanceOf<SetCompositionResult.Success>()

      item.videoComposition shouldBe composition
      awaitContract("the playhead to advance") {
        recorder.lastState.isPlaying && (recorder.playhead ?: Duration.ZERO) > HELD_AT
      }
    }

  private fun swapTest(
    body: suspend (
      engine: AvPlayerEngine,
      recorder: ContractRecorder,
      item: AVPlayerItem,
      output: AVPlayerItemVideoOutput,
    ) -> Unit,
  ) = contractTest { scope ->
    diff(DIM_EDIT, BRIGHT_EDIT) shouldBe CompositionDiff.ParametersOnly

    val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
    withEngine(engine) { recorder ->
      // Full, so the output's frame is the size the export writes.
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(DIM_EDIT).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      val item = engine.platformPlayer.currentItem ?: fail("the player holds no item")
      val output = bgraVideoOutput()
      item.addOutput(output as AVPlayerItemOutput)
      val completed = recorder.seekCompletions.size
      engine.seekTo(HELD_AT, SeekAccuracy.Exact)
      awaitContract("the seek to $HELD_AT to complete") { recorder.seekCompletions.size > completed }
      body(engine, recorder, item, output)
    }
  }

  // The output can hand over the frame it drew when it was attached before the seek's, so a frame shown anywhere but
  // the held time is passed over.
  private suspend fun AVPlayerItemVideoOutput.awaitFrame(
    item: AVPlayerItem,
    what: String,
  ): TestFrame {
    var frame: TestFrame? = null
    awaitContract("$what to reach the output") {
      val time = item.currentTime()
      if (hasNewPixelBufferForItemTime(time)) frame = heldFrameAt(time)
      frame != null
    }
    return frame ?: fail("no frame for $what")
  }

  private fun AVPlayerItemVideoOutput.heldFrameAt(time: CValue<CMTime>): TestFrame? =
    memScoped {
      val display = alloc<CMTime>()
      val buffer = copyPixelBufferForItemTime(time, itemTimeForDisplay = display.ptr) ?: return null
      try {
        val shown = display.readValue().toDuration()
        buffer.bgraToTestFrame().takeIf { (shown - HELD_AT).absoluteValue < FRAME_SLACK }
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }

  private fun TestFrame.meanLevel(): Double {
    var sum = 0L
    for (index in pixels.indices) if (index % CHANNELS != ALPHA) sum += pixels[index].toInt() and BYTE_MASK
    return sum.toDouble() / (pixels.size / CHANNELS * ALPHA)
  }

  private class RedrawCounter : EngineListener {
    var count = 0
      private set

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) = Unit

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) = Unit

    override fun onFrameRedrawn() {
      count++
    }
  }

  private companion object {
    const val DIM = 0.4f
    const val BRIGHT = 1.4f
    const val CHANNELS = 4
    const val ALPHA = 3
    const val BYTE_MASK = 0xFF

    // Of 255, well past what two draws of one frame differ by.
    const val LEVEL_STEP = 20.0
    val HELD_AT: Duration = PROBE_POSITIONS.first()

    // Wider than a frame, and narrower than the way from the start to the held time.
    val FRAME_SLACK: Duration = 100.milliseconds
    val DIM_EDIT = appleFixtureComposition(listOf(Brightness(DIM)))
    val BRIGHT_EDIT = appleFixtureComposition(listOf(Brightness(BRIGHT)))
  }
}
