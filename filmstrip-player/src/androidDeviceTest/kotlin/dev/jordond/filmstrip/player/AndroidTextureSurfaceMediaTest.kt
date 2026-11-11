package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.internal.SDR_SURFACE_MAPS_HDR
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A preview drawing into a `TextureView`, against a real window.
 *
 * media3 takes no `TextureView`, so the engine owns a surface over the view's texture. These hold that path to what
 * the `SurfaceView` one does: a first frame, a rebuild, a view taken up again, a resize, the still a swap holds, and
 * the frame a paused seek shows. The picture is read off the view with `getBitmap`, which is the only way into a
 * texture.
 */
class AndroidTextureSurfaceMediaTest {
  private val context = contractContext()

  @Test
  fun attachingATextureRendersAFirstFrameAndDetachingLeavesThePlayerReleasable() =
    onTexture(androidFixtureComposition()) { rig ->
      val handle = rig.player.attachPreviewSurface(rig.view)
      rig.player.awaitReady()
      rig.player.play()
      awaitOrFail("a first frame to reach the texture") { rig.firstFrames == 1 }
      withClue("an SDR edit has nothing for the texture to tone map") {
        rig.player.previewInfo.value.fidelity shouldNotContain SDR_SURFACE_MAPS_HDR
      }

      handle.cancel()
      handle.cancel()
    }

  @Test
  fun rebuildingTheGraphReportsAnotherFirstFrame() =
    onTexture(androidFixtureComposition()) { rig ->
      rig.player.attachPreviewSurface(rig.view)
      rig.player.awaitReady()
      rig.player.play()
      awaitOrFail("the first composition to render") { rig.firstFrames == 1 }

      // A shorter trim, which changes the duration and so rebuilds rather than updating parameters in place.
      val trimmed = Composition(listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, SHORTER))))))
      val reloaded =
        withTimeoutOrNull(BUDGET) { rig.player.setComposition(trimmed, playWhenReady = true) }
          ?: fail("The rebuild never settled within $BUDGET.")
      reloaded.shouldBeInstanceOf<SetCompositionResult.Success>()

      awaitOrFail("the rebuilt composition to render") { rig.firstFrames == 2 }
    }

  @Test
  fun aTextureTakenUpAgainRendersAndSaysSo() =
    onTexture(androidFixtureComposition()) { rig ->
      val handle = rig.player.attachPreviewSurface(rig.view)
      rig.player.awaitReady()
      rig.player.play()
      awaitOrFail("the first frame to reach the texture") { rig.firstFrames == 1 }
      rig.player.pause()
      handle.cancel()

      rig.player.attachPreviewSurface(rig.view)
      rig.player.play()
      awaitOrFail("the texture taken up again to render") { rig.firstFrames == 2 }
    }

  /**
   * A view that leaves the window loses its texture and is given another when it comes back.
   *
   * The surface over the first texture has to come off the player before the texture goes, and the second has to be
   * drawn onto without anything asking for it. media3 draws the frame after the one it was paused on when a paused
   * player is handed a new surface, on a `SurfaceView` as well, so either of the two is the picture here.
   */
  @Test
  fun aTextureDestroyedAndMadeAgainUnderAPausedPlayerIsDrawnOntoAgain() =
    onTexture(androidPannedClipComposition()) { rig ->
      val composition = androidPannedClipComposition()
      val paused = androidExportFrame(composition, PANNED_SLOT)
      val after = androidExportFrame(composition, PANNED_SLOT + FIXTURE_FRAME_STEP)
      rig.showPausedAt(PANNED_SLOT)

      rig.activity.removeTexture()
      awaitOrFail("the texture to be destroyed") { !rig.view.isAvailable }
      rig.activity.restoreTexture()
      awaitOrFail("the view to be given a texture again") { rig.view.isAvailable }
      delay(SETTLE)

      val shown = rig.view.read(FIXTURE_FRAME)
      val showsEither =
        listOf(paused, after).any { exported ->
          runCatching { assertFramesSimilar(exported, shown, SEEK_MIN_PSNR_DB, SEEK_MIN_SSIM, "") }.isSuccess
        }
      withClue("the new texture shows neither the paused frame nor the one after it") { showsEither.shouldBeTrue() }
    }

  @Test
  fun aTextureResizedUnderAPausedPlayerStillShowsTheFrame() =
    onTexture(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val before = rig.view.read(FIXTURE_FRAME)

      rig.activity.resizeTexture(TALL.width, TALL.height)
      awaitOrFail("the view to take its second shape") { rig.view.width == TALL.width }
      delay(SETTLE)

      // The buffer holds the rendered frame, so the view stretches the same picture over its new shape.
      assertFramesSimilar(
        before,
        rig.view.read(FIXTURE_FRAME),
        MIN_PSNR_DB,
        MIN_SSIM,
        "the resized texture shows another frame",
      )
    }

  /**
   * A swap that changes the output geometry holds the frame the texture last drew.
   */
  @Test
  fun theStillASwapHoldsIsTheFrameOnTheTexture() =
    onTexture(androidPannedClipComposition()) { rig ->
      rig.showPausedAt(PANNED_SLOT)
      val before = rig.view.read(WIDE)
      val stills = CopyOnWriteArrayList<Bitmap>()
      val handle = withContext(Dispatchers.Main) { rig.player.observePreviewStill { still -> still?.let(stills::add) } }

      try {
        // The pan moves the picture a long way between two frames, so a still of any other frame fails below.
        val turned = androidFixtureComposition(listOf(Rotate(QUARTER)))
        withTimeoutOrNull(BUDGET) { rig.player.setComposition(turned) }
          ?: fail("The swap never settled within $BUDGET.")
        awaitOrFail("the swap to hold a still") { stills.isNotEmpty() }

        val still = stills.first()
        withClue("the still is the size of the view") { Size(still.width, still.height) shouldBe WIDE }
        assertFramesSimilar(
          before,
          still.toTestFrame(),
          MIN_PSNR_DB,
          MIN_SSIM,
          "the still is not the frame that was showing",
        )
      } finally {
        withContext(Dispatchers.Main) { handle.cancel() }
      }
    }

  /**
   * A paused seek shows the slot it landed on, and the same picture a `SurfaceView` shows there.
   *
   * The probe sits inside the panned clip, where the picture moves a long way between two frames, so a texture a slot
   * off or one still holding the frame before the seek fails against the export.
   */
  @Test
  fun aPausedSeekShowsItsSlotAndWhatASurfaceViewShowsThere() =
    runTest(timeout = TIMEOUT) {
      realTime {
        val composition = androidPannedClipComposition()
        val landing = PANNED_SLOT
        val slot = outputSlotOf(landing, FIXTURE_FRAME_RATE)
        val exported = androidExportFrame(composition, landing)
        val before = androidExportFrame(composition, outputSlotTime(slot - 1, FIXTURE_FRAME_RATE))
        assertFramesDiffer(exported, before, SEEK_MIN_PSNR_DB, SEEK_MIN_SSIM, "slot $slot exports like the one before")

        var onTexture: TestFrame? = null
        withTexture(composition) { rig ->
          rig.showPausedAt(landing)
          onTexture = rig.view.read(FIXTURE_FRAME)
        }
        val onSurface = surfaceViewAfterSeek(composition, landing)
        val shown = onTexture.shouldNotBeNull()

        assertFramesSimilar(
          exported,
          shown,
          SEEK_MIN_PSNR_DB,
          SEEK_MIN_SSIM,
          "a seek to slot $slot showed another frame",
        )
        assertFramesSimilar(
          onSurface,
          shown,
          MIN_PSNR_DB,
          MIN_SSIM,
          "the texture and the SurfaceView show different frames",
        )
      }
    }

  /**
   * An HDR edit on a texture shows the frame an export tone mapped to SDR writes, and says the surface is why.
   *
   * A texture is drawn by the app's own SDR layer, so the preview plans the grade an export keeps as a tone map while
   * one is attached. Only a device with a ten-bit decoder plays the fixture, and no emulator image has one. The
   * floors are the ones an SDR read holds against its export, until a phone says what a tone mapped one scores.
   */
  @Test
  fun anHdrEditOnATextureShowsTheFrameAToneMappedExportWrites() =
    runTest(timeout = TIMEOUT) {
      realTime {
        assumeDecodesTenBitHevc()
        val composition = Composition(listOf(Track(listOf(Clip(androidFixture(HDR_CLIP))))))
        val exported = androidToneMappedExportFrame(composition, HDR_PROBE)

        withTexture(composition) { rig ->
          rig.showPausedAt(HDR_PROBE)

          rig.player.previewInfo.value.fidelity shouldContain SDR_SURFACE_MAPS_HDR
          assertFramesSimilar(
            exported,
            rig.view.read(exported.size),
            SEEK_MIN_PSNR_DB,
            SEEK_MIN_SSIM,
            "the texture does not show the tone mapped frame",
          )
        }
      }
    }

  private suspend fun CoroutineScope.surfaceViewAfterSeek(
    composition: Composition,
    at: Duration,
  ): TestFrame {
    var captured: TestFrame? = null
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
        player.attachPreviewSurface(activity.surfaceView)
        player.awaitReady()
        player.play()
        awaitOrFail("the composition to render") { events.any { it is PlaybackEvent.FirstFrameRendered } }
        player.pause()
        player.seekTo(at, SeekAccuracy.Exact)
        awaitOrFail("the seek to $at to land") { events.any { it is PlaybackEvent.SeekCompleted } }
        delay(SETTLE)
        captured = activity.surfaceView.copyPixels(FIXTURE_FRAME)
      } finally {
        collector.cancel()
        player.close()
      }
    }
    return captured.shouldNotBeNull()
  }

  private suspend fun SurfaceView.copyPixels(size: Size): TestFrame {
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val status =
      suspendCancellableCoroutine { continuation ->
        PixelCopy.request(this, bitmap, { result -> continuation.resume(result) }, Handler(Looper.getMainLooper()))
      }
    if (status != PixelCopy.SUCCESS) fail("PixelCopy off the surface answered $status")
    return bitmap.toTestFrame()
  }

  private suspend fun TextureView.read(size: Size): TestFrame {
    val bitmap: Bitmap? = withContext(Dispatchers.Main) { getBitmap(size.width, size.height) }
    return bitmap?.toTestFrame() ?: fail("the texture had nothing to read")
  }

  private fun onTexture(
    composition: Composition,
    body: suspend CoroutineScope.(TextureRig) -> Unit,
  ) = runTest(timeout = TIMEOUT) { realTime { withTexture(composition, body) } }

  private suspend fun CoroutineScope.withTexture(
    composition: Composition,
    body: suspend CoroutineScope.(TextureRig) -> Unit,
  ) {
    ActivityScenario.launch(TextureHostActivity::class.java).use { scenario ->
      var host: TextureHostActivity? = null
      scenario.onActivity { activity -> host = activity }
      val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
      activity.resizeTexture(WIDE.width, WIDE.height)
      awaitOrFail("the view to take its shape and a texture") {
        activity.textureView.isAvailable && activity.textureView.width == WIDE.width
      }

      val player = Filmstrip(context) { playerBackend() }.preview(composition)
      val events = CopyOnWriteArrayList<PlaybackEvent>()
      val collector = launch { player.events.collect { events += it } }
      try {
        body(TextureRig(activity, player, events))
      } finally {
        collector.cancel()
        player.close()
      }
    }
  }

  private class TextureRig(
    val activity: TextureHostActivity,
    val player: VideoPlayer,
    private val events: List<PlaybackEvent>,
  ) {
    val view: TextureView get() = activity.textureView
    val firstFrames: Int get() = events.count { it is PlaybackEvent.FirstFrameRendered }

    /**
     * Attaches the texture, draws once, and leaves the preview paused on the frame at [at].
     */
    suspend fun showPausedAt(at: Duration) {
      player.attachPreviewSurface(view)
      player.awaitReady()
      player.play()
      awaitOrFail("the composition to render") { firstFrames == 1 }
      player.pause()
      val seeks = events.count { it is PlaybackEvent.SeekCompleted }
      player.seekTo(at, SeekAccuracy.Exact)
      awaitOrFail("the seek to $at to land") { events.count { it is PlaybackEvent.SeekCompleted } > seeks }
      delay(SETTLE)
    }
  }

  private suspend fun realTime(body: suspend CoroutineScope.() -> Unit) = withContext(Dispatchers.Default) { body() }

  private companion object {
    val TIMEOUT: Duration = 5.minutes
    val BUDGET: Duration = 30.seconds
    val SHORTER: Duration = 900.milliseconds

    // Past the 250 ms an emulator takes to composite what the player drew.
    val SETTLE: Duration = 700.milliseconds

    // A slot inside the panned clip, where the frames either side are a different picture.
    val PANNED_SLOT: Duration =
      outputSlotTime(outputSlotOf(PANNED_CLIP_START + MID_GOP_POSITION, FIXTURE_FRAME_RATE) + 1, FIXTURE_FRAME_RATE)
    const val QUARTER = 90

    const val HDR_CLIP = "android_export_hdr.mp4"
    val HDR_PROBE: Duration = 500.milliseconds

    // A 16:9 view and the 9:16 one a quarter turn of the same edit asks for.
    val WIDE = Size(960, 540)
    val TALL = Size(540, 960)

    // Two reads of one paused frame, so anything but scaling noise is a real difference.
    const val MIN_PSNR_DB = 35.0
    const val MIN_SSIM = 0.97

    // The floors the SurfaceView read holds against the export at the slot it showed.
    const val SEEK_MIN_PSNR_DB = 32.0
    const val SEEK_MIN_SSIM = 0.975
  }
}

private suspend fun VideoPlayer.awaitReady() =
  awaitOrFail("the preview to become ready") { state.value.status == PlaybackStatus.Ready }
