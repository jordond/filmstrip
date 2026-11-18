package dev.jordond.filmstrip.player

import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * An animated picture in a preview that is paused on a real surface, which has to show the frame the export writes
 * there.
 *
 * The preview draws the same resolved overlay the export does, so each pause is read against the frame the picture's
 * own timeline puts on its slot. The delays are uneven, and the pauses are chosen so that no two of them show the same
 * frame, which a surface that kept an earlier one would fail. The second pause is in the second play and the last two
 * go backward, which media3 draws by seeking to the slot again.
 */
class AndroidAnimatedPicturePreviewMediaTest {
  @Test
  fun aPausedPreviewShowsTheFrameTheTimelineNamesAfterSeekingBackward() =
    onSurface { rig ->
      val shown = mutableListOf<Int>()
      rig.showPausedAt(FIRST)
      shown += rig.shouldShowTheFrameOf(FIRST)
      for (at in listOf(LATER, EARLIER, FIRST)) {
        rig.seekAndSettle(at)
        shown += rig.shouldShowTheFrameOf(at)
      }

      withClue("the pauses were chosen to land on different frames: $shown") {
        shown.take(PAUSES_DISTINCT).toSet().size shouldBe PAUSES_DISTINCT
      }
    }

  private suspend fun SurfaceRig.shouldShowTheFrameOf(at: Duration): Int {
    val slot = outputSlotOf(at, FIXTURE_FRAME_RATE)
    val expected = expectedFrame(slot)
    val frame = view.copyPixels(FIXTURE_FRAME)
    withClue("at $at, slot $slot") { indexIn(frame, rect()) shouldBe expected }
    return expected
  }

  private fun expectedFrame(slot: Long): Int {
    val timeline = (animatedPictureOf(FILE) as AnimatedRead.Animated).picture.timeline
    val span = TimeRange.of(Duration.ZERO, CLIP_LENGTH)
    return timeline.frameOnSlot(OVERLAY.drawnRangeWithin(span).start, slot, FIXTURE_FRAME_RATE)
  }

  private fun rect(): NormalizedRect =
    OVERLAY.placedOn(FIXTURE_FRAME, AnimatedPictures.COUNTER_CANVAS).rectOn(FIXTURE_FRAME)

  // The index the eight squares of the counter write, each read at its middle and snapped to black or white.
  private fun indexIn(
    frame: TestFrame,
    rect: NormalizedRect,
  ): Int? {
    val canvas = AnimatedPictures.COUNTER_CANVAS
    var index = 0
    for (bit in 0 until SQUARES) {
      val x =
        rect.left * frame.size.width +
          (SQUARE_LEFT + SQUARE_STEP * bit + 1f) * rect.width * frame.size.width / canvas.width
      val y = rect.top * frame.size.height + (SQUARE_ROW + 1f) * rect.height * frame.size.height / canvas.height
      val base =
        (
          y.toInt().coerceIn(
            0,
            frame.size.height - 1,
          ) * frame.size.width + x.toInt().coerceIn(0, frame.size.width - 1)
        ) *
          4
      val channels = IntArray(3) { frame.pixels[base + it].toInt() and BYTE }
      index =
        when {
          channels.all { it > LIGHT } -> (index shl 1) or 1
          channels.all { it < DARK } -> index shl 1
          else -> return null
        }
    }
    return index
  }

  private fun onSurface(body: suspend CoroutineScope.(SurfaceRig) -> Unit) =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) {
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
          withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }
          activity.resizeSurface(WIDE.width, WIDE.height)
          awaitOrFail("the view to take its shape") { activity.surfaceView.width == WIDE.width }

          val player =
            Filmstrip(contractContext()) { playerBackend() }.preview(androidFixtureComposition(listOf(OVERLAY)))
          val events = CopyOnWriteArrayList<PlaybackEvent>()
          val collector = launch { player.events.collect { events += it } }
          try {
            body(SurfaceRig(activity, player, events))
          } finally {
            collector.cancel()
            player.close()
          }
        }
      }
    }

  private class SurfaceRig(
    val activity: SurfaceHostActivity,
    val player: VideoPlayer,
    private val events: List<PlaybackEvent>,
  ) {
    val view get() = activity.surfaceView
    private val seeksCompleted: Int get() = events.count { it is PlaybackEvent.SeekCompleted }

    /**
     * Attaches the surface, draws once, and leaves the preview paused on the frame at [at].
     */
    suspend fun showPausedAt(at: Duration) {
      player.attachPreviewSurface(view)
      awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
      player.play()
      awaitOrFail("the composition to render") { events.any { it is PlaybackEvent.FirstFrameRendered } }
      player.pause()
      seekAndSettle(at)
    }

    suspend fun seekAndSettle(at: Duration) {
      val seeks = seeksCompleted
      player.seekTo(at, SeekAccuracy.Exact)
      awaitOrFail("the seek to $at to land") { seeksCompleted > seeks }
      awaitOrFail("the seek to stop buffering") { !player.state.value.isStalled }
      delay(SETTLE)
    }
  }

  private companion object {
    val TIMEOUT: Duration = 5.minutes

    // Past the 250 ms an emulator takes to composite what the player drew.
    val SETTLE: Duration = 700.milliseconds

    val WIDE = Size(960, 540)

    // Twelve counters with uneven delays, which come to 800 ms for a cycle.
    val DELAYS = listOf(30, 70, 40, 120, 50, 90)
    val OPS: List<Op> =
      AnimatedPictures.counterOps(12).mapIndexed { index, op ->
        Op(op.x, op.y, op.tile, op.disposal, op.blend, DELAYS[index % DELAYS.size])
      }
    val FILE: ByteArray = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, OPS)

    val OVERLAY =
      ImageOverlay(
        image = ImageSource.ofBytes(FILE),
        corner = Corner.TopStart,
        margin = 0f,
        scale = 0.5f,
      )

    // Slots inside the first play, the second play and the first again, with a different frame on each.
    val FIRST: Duration = outputSlotTime(outputSlotOf(450.milliseconds, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
    val LATER: Duration = outputSlotTime(outputSlotOf(1_100.milliseconds, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
    val EARLIER: Duration = outputSlotTime(outputSlotOf(50.milliseconds, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
    const val PAUSES_DISTINCT = 3

    const val SQUARES = 8
    const val SQUARE_LEFT = 2
    const val SQUARE_STEP = 3
    const val SQUARE_ROW = 7

    // What a white and a black square read on the surface.
    const val LIGHT = 200
    const val DARK = 55
    const val BYTE = 0xFF
  }
}
