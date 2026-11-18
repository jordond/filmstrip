package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.bgraVideoOutput
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.CounterCells
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
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
import platform.CoreMedia.CMTime
import platform.CoreVideo.CVPixelBufferRelease
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the item's own video output shows of an animated picture overlay, which is what a player layer shows.
 *
 * The frame is read after a seek forward into the second play and after seeks back, since the chain draws the frame
 * its own composition time names and a scrub has no memory of the frame drawn before it. The output hands back the time
 * it displays the frame for, and the expected frame is the one the file's timeline gives that time.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAnimatedPicturePreviewHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `an animated picture shows the timeline's frame after seeks forward and back`() =
    contractTest { scope ->
      val bytes = AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps(FRAMES))
      val timeline = animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture.timeline
      val overlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, margin = 0f, scale = SCALE)
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        engine
          .awaitComposition(appleFixtureComposition(listOf(overlay)))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val item = engine.platformPlayer.currentItem ?: fail("the player holds no item")
        val output = bgraVideoOutput()
        item.addOutput(output as AVPlayerItemOutput)

        val shown = mutableListOf<Int>()
        SEEKS.forEach { target ->
          val completed = recorder.seekCompletions.size
          engine.seekTo(target, SeekAccuracy.Exact)
          awaitContract("the seek to $target to complete") { recorder.seekCompletions.size > completed }
          val (displayed, frame) = output.awaitFrameNear(item, target)
          val canvas = overlay.placedOn(frame.size, AnimatedPictures.COUNTER_CANVAS).rectOn(frame.size)
          val read = withClue("the frame at $target holds no counter") { frame.counterIn(canvas).shouldNotBeNull() }
          withClue("the frame shown for $displayed after a seek to $target") {
            read shouldBe timeline.frameAt(Duration.ZERO, displayed, RATE)
          }
          shown += read
        }

        withClue("the seeks read $shown, which has to differ from frame to frame for the check to mean anything") {
          shown.distinct().size shouldBe SEEKS.size
        }
      }
    }

  // The output can hand over the frame it drew before the seek, so a frame shown anywhere but near the target is
  // passed over.
  private suspend fun AVPlayerItemVideoOutput.awaitFrameNear(
    item: AVPlayerItem,
    target: Duration,
  ): Pair<Duration, TestFrame> {
    var found: Pair<Duration, TestFrame>? = null
    awaitContract("a frame near $target to reach the output") {
      val time = item.currentTime()
      if (hasNewPixelBufferForItemTime(time)) found = frameAt(time, target)
      found != null
    }
    return found ?: fail("no frame near $target")
  }

  private fun AVPlayerItemVideoOutput.frameAt(
    time: CValue<CMTime>,
    target: Duration,
  ): Pair<Duration, TestFrame>? =
    memScoped {
      val display = alloc<CMTime>()
      val buffer = copyPixelBufferForItemTime(time, itemTimeForDisplay = display.ptr) ?: return null
      try {
        val shown = display.readValue().toDuration()
        if ((shown - target).absoluteValue < FRAME_SLACK) shown to buffer.bgraToTestFrame() else null
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }

  // The counter written in the top left of the canvas drawn in [canvas], or null when a square is neither dark nor
  // light.
  private fun TestFrame.counterIn(canvas: NormalizedRect): Int? {
    val cell = AnimatedPictures.COUNTER_CELL
    val shown = AnimatedPictures.COUNTER_CANVAS
    val stepX = canvas.width * size.width / shown.width
    val stepY = canvas.height * size.height / shown.height
    val texels =
      IntArray(cell.width * cell.height) { index ->
        val x = (canvas.left * size.width + (index % cell.width + HALF) * stepX).toInt().coerceIn(0, size.width - 1)
        val y = (canvas.top * size.height + (index / cell.width + HALF) * stepY).toInt().coerceIn(0, size.height - 1)
        val at = (y * size.width + x) * CHANNELS
        val sum =
          (pixels[at].toInt() and BYTE_MASK) + (pixels[at + 1].toInt() and BYTE_MASK) +
            (pixels[at + 2].toInt() and BYTE_MASK)
        if (sum > THRESHOLD) LIGHT else DARK
      }
    return runCatching { CounterCells.indexOf(texels) }.getOrNull()
  }

  private companion object {
    const val RATE = 30
    const val SCALE = 0.6f

    // A play is 20 frames of 40 ms. Forward into the second play, then back through the first, then back again.
    const val FRAMES = 20
    val SEEKS = listOf(1_500.milliseconds, 300.milliseconds, 100.milliseconds)

    // Wider than a frame, and narrower than the way between two seeks.
    val FRAME_SLACK: Duration = 100.milliseconds
    const val CHANNELS = 4
    const val BYTE_MASK = 0xFF
    const val HALF = 0.5f
    const val THRESHOLD = 3 * 128
    const val LIGHT = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF000000.toInt()
  }
}
