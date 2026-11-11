package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.transformer.CompositionPlayer
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.engineFor
import dev.jordond.filmstrip.player.internal.surfaceRepresents
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What a `SurfaceView` shows once its surface changes under a preview that is not playing.
 *
 * media3 draws the frame it had queued behind the one on screen onto any output it is handed, which on a paused
 * preview is the slot after the one the playhead reports. Each test pauses inside the panned clip, where two
 * neighbouring slots are a different picture, and holds the view against the export of the slot it was paused on.
 */
class AndroidPausedSurfaceMediaTest {
  private val context = contractContext()

  @Test
  fun aSurfaceHiddenAndShownUnderAPausedPlayerShowsTheSlotItHeld() =
    onSurface(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val seeks = rig.seeksCompleted

      rig.activity.hideSurface()
      awaitOrFail("the surface to be destroyed") { !rig.view.holder.surface.isValid }
      rig.activity.showSurface()
      awaitOrFail("the view to be given a surface again") { rig.view.holder.surface.isValid }
      delay(SETTLE)

      rig.shouldShowTheHeldSlot(seeks)
    }

  /**
   * A buffer pinned to another size reaches media3 through the holder it follows, with no surface lost before it.
   */
  @Test
  fun aBufferPinnedToAnotherSizeUnderAPausedPlayerShowsTheSlotItHeld() =
    onSurface(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val seeks = rig.seeksCompleted
      val represented = rig.engine.surfaceRepresents

      rig.activity.resizeBuffer(FIXTURE_FRAME.width / HALVED, FIXTURE_FRAME.height / HALVED)
      awaitOrFail("the resized surface to be seeked back") { rig.engine.surfaceRepresents > represented }
      delay(SETTLE)

      rig.shouldShowTheHeldSlot(seeks)
    }

  /**
   * A seek asked for as the surface goes is still owed its frame, and is answered once by the surface that comes back.
   */
  @Test
  fun aSeekInFlightAsTheSurfaceGoesLandsOnceOnTheSurfaceThatComesBack() =
    onSurface(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val seeks = rig.seeksCompleted

      rig.player.seekTo(LATER_SLOT, SeekAccuracy.Exact)
      rig.activity.hideSurface()
      awaitOrFail("the surface to be destroyed") { !rig.view.holder.surface.isValid }
      rig.activity.showSurface()
      awaitOrFail("the view to be given a surface again") { rig.view.holder.surface.isValid }
      awaitOrFail("the seek to land") { rig.seeksCompleted > seeks }
      awaitOrFail("the seek to stop buffering") { !rig.player.state.value.isStalled }
      delay(SETTLE)

      rig.shouldShow(LATER_SLOT)
      withClue("the one seek was reported more than once") { rig.seeksCompleted shouldBe seeks + 1 }
    }

  /**
   * A preview that ran to its end holds its last slot, with nothing queued behind it for a new surface to be given.
   */
  @Test
  fun aSurfaceHiddenAndShownUnderAPreviewThatRanToItsEndShowsTheLastSlot() =
    onSurface(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val length =
        rig.player.state.value.duration
          .shouldNotBeNull()
      val seeks = rig.seeksCompleted
      rig.player.seekTo(length - RUN_IN, SeekAccuracy.Exact)
      awaitOrFail("the seek near the end to land") { rig.seeksCompleted > seeks }
      rig.player.play()
      awaitOrFail("the preview to run to its end") { rig.player.state.value.status == PlaybackStatus.Ended }
      delay(SETTLE)

      rig.activity.hideSurface()
      awaitOrFail("the surface to be destroyed") { !rig.view.holder.surface.isValid }
      rig.activity.showSurface()
      awaitOrFail("the view to be given a surface again") { rig.view.holder.surface.isValid }
      delay(SETTLE)

      val last = outputSlotTime(outputSlotOf(length, FIXTURE_FRAME_RATE) - 1, FIXTURE_FRAME_RATE)
      val exported = androidExportFrame(androidPannedClipComposition(), last)
      assertFramesSimilar(
        exported,
        rig.view.copyPixels(FIXTURE_FRAME),
        MIN_PSNR_DB,
        MIN_SSIM,
        "the surface does not show the last slot",
      )
      withClue("the preview stopped saying it had ended") {
        rig.player.state.value.status shouldBe PlaybackStatus.Ended
      }
    }

  private suspend fun SurfaceRig.shouldShowTheHeldSlot(seeksBefore: Int) {
    shouldShow(PANNED_SLOT)
    withClue("a seek nobody asked for was reported") { seeksCompleted shouldBe seeksBefore }
  }

  private suspend fun SurfaceRig.shouldShow(slotTime: Duration) {
    val composition = androidPannedClipComposition()
    val held = androidExportFrame(composition, slotTime)
    val after = androidExportFrame(composition, slotTime + FIXTURE_FRAME_STEP)
    assertFramesDiffer(held, after, MIN_PSNR_DB, MIN_SSIM, "the slot exports like the one after it")

    val shown = view.copyPixels(FIXTURE_FRAME)
    assertFramesSimilar(held, shown, MIN_PSNR_DB, MIN_SSIM, "the surface does not show the slot the playhead is on")
    assertFramesDiffer(after, shown, MIN_PSNR_DB, MIN_SSIM, "the surface shows the slot after the playhead's")
    withClue("the playhead is off the slot") {
      outputSlotOf(player.currentPosition(), FIXTURE_FRAME_RATE) shouldBe outputSlotOf(slotTime, FIXTURE_FRAME_RATE)
    }
  }

  private fun onSurface(
    composition: Composition,
    body: suspend CoroutineScope.(SurfaceRig) -> Unit,
  ) = runTest(timeout = TIMEOUT) {
    withContext(Dispatchers.Default) {
      ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
        var host: SurfaceHostActivity? = null
        scenario.onActivity { activity -> host = activity }
        val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
        withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }
        activity.resizeSurface(WIDE.width, WIDE.height)
        awaitOrFail("the view to take its shape") { activity.surfaceView.width == WIDE.width }

        val player = Filmstrip(context) { playerBackend() }.preview(composition)
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
    val view: SurfaceView get() = activity.surfaceView
    val seeksCompleted: Int get() = events.count { it is PlaybackEvent.SeekCompleted }
    val engine: Media3PlayerEngine
      get() = withClue("no engine") { engineFor(player.nativePlayer as CompositionPlayer).shouldNotBeNull() }

    /**
     * Attaches the surface, draws once, and leaves the preview paused on the frame at [at].
     */
    suspend fun showPausedAt(at: Duration) {
      player.attachPreviewSurface(view)
      awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
      player.play()
      awaitOrFail("the composition to render") { events.any { it is PlaybackEvent.FirstFrameRendered } }
      player.pause()
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

    // A slot inside the panned clip, where the frames either side are a different picture.
    val PANNED_SLOT: Duration =
      outputSlotTime(outputSlotOf(PANNED_CLIP_START + MID_GOP_POSITION, FIXTURE_FRAME_RATE) + 1, FIXTURE_FRAME_RATE)

    // Another slot of the panned clip, a few frames on.
    val LATER_SLOT: Duration = PANNED_SLOT + FIXTURE_FRAME_STEP * LATER_FRAMES
    const val LATER_FRAMES = 6

    // How far before the end a preview is started from to run out.
    val RUN_IN: Duration = 400.milliseconds

    val WIDE = Size(960, 540)
    const val HALVED = 2

    // The floors a surface read holds against the export at the slot it showed.
    const val MIN_PSNR_DB = 32.0
    const val MIN_SSIM = 0.975
  }
}

/**
 * Copies what the view's surface shows, scaled to [size].
 */
internal suspend fun SurfaceView.copyPixels(size: Size): TestFrame {
  val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
  val status =
    suspendCancellableCoroutine { continuation ->
      PixelCopy.request(this, bitmap, { result -> continuation.resume(result) }, Handler(Looper.getMainLooper()))
    }
  if (status != PixelCopy.SUCCESS) fail("PixelCopy off the surface answered $status")
  return bitmap.toTestFrame()
}
