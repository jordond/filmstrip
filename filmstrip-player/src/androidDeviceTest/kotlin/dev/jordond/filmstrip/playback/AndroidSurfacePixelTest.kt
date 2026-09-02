package dev.jordond.filmstrip.playback

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the display actually shows, read back off the `SurfaceView` the player draws into.
 *
 * Every other pixel test here reads through `FrameExtractor`, which builds a graph of its own from
 * the same effect objects. That graph is configured fresh on every request, so it draws the right
 * picture whatever state the player's standing graph is in. Only a copy off the surface sees what
 * the player is really rendering.
 *
 * Each case loads one edit into a running preview, changes it, and compares the surface against a
 * second preview that loaded the changed edit from nothing. The two are the same edit, so they have
 * to be the same picture. A change the standing graph took but could not honour shows up here and
 * nowhere else.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidSurfacePixelTest {
  private val context = contractContext()

  /**
   * The one the sample hits: a quarter turn changes the output frame, so the surface the preview
   * draws into is resized under a player that is already running.
   */
  @Test
  fun aRotationThatResizesTheSurfaceShowsWhatAFreshLoadShows() =
    surfaceComparison(
      first = androidFixtureComposition(listOf(Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      resize = true,
    )

  @Test
  fun aRotationAppliedToARunningPreviewShowsWhatAFreshLoadShows() =
    surfaceComparison(
      first = androidFixtureComposition(listOf(Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
    )

  @Test
  fun aQuarterTurnChangedToAnotherShowsWhatAFreshLoadShows() =
    surfaceComparison(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(THREE_QUARTERS), Brightness(DIM))),
    )

  @Test
  fun aFillChangedUnderALetterboxShowsWhatAFreshLoadShows() =
    surfaceComparison(
      first = androidFixtureComposition(listOf(Rotate(QUARTER)), Fill.Solid(RED)),
      second = androidFixtureComposition(listOf(Rotate(QUARTER)), Fill.Solid(BLUE)),
    )

  @Test
  fun aBrightnessChangedUnderARotationShowsWhatAFreshLoadShows() =
    surfaceComparison(
      first = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(DIM))),
      second = androidFixtureComposition(listOf(Rotate(QUARTER), Brightness(BRIGHT))),
    )

  /**
   * Loads [first], then [second], and compares the surface against a preview that only ever loaded
   * [second].
   */
  private fun surfaceComparison(
    first: EditComposition,
    second: EditComposition,
    resize: Boolean = false,
  ) = runTest(timeout = TIMEOUT) {
    realTime {
      // The changed run starts on the shape the first edit asks for and is resized under the
      // player, the way a preview surface sized to the output frame is. The fresh run only ever
      // had the second edit, so it starts on the shape that edit asks for and never moves.
      val changed = surfaceOf(first, second, if (resize) LANDSCAPE else null, if (resize) PORTRAIT else null)
      val fresh = surfaceOf(second, null, if (resize) PORTRAIT else null, null)

      assertFramesSimilar(
        expected = fresh,
        actual = changed,
        minPsnrDb = MIN_PSNR_DB,
        minSsim = MIN_SSIM,
        message = "the surface after the edit changed is not the surface a fresh load of it draws",
      )
    }
  }

  /**
   * Plays [first] on a real surface, replaces it with [second] where there is one, and copies what
   * the surface is showing.
   */
  private suspend fun surfaceOf(
    first: EditComposition,
    second: EditComposition?,
    startShape: Size? = null,
    endShape: Size? = null,
  ): TestFrame =
    coroutineScope {
      var captured: TestFrame? = null
      ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
        var host: SurfaceHostActivity? = null
        scenario.onActivity { activity -> host = activity }
        val activity = assertNotNull(host, "the host activity never started")
        assertTrue(activity.awaitSurface(), "the host window produced no surface")

        val player = Filmstrip(context) { playerBackend() }.preview(first)
        val events = mutableListOf<PlaybackEvent>()
        val collector = launch { player.events.collect { events += it } }

        try {
          player.attachPreviewSurface(activity.surfaceView)
          player.awaitReady()
          player.play()
          awaitOrFail("the first composition to render") {
            events.any { it is PlaybackEvent.FirstFrameRendered }
          }

          if (second != null) {
            withTimeoutOrNull(BUDGET) { player.setComposition(second) } ?: fail("the change never settled")
            player.awaitReady()
          }

          player.pause()
          player.seekTo(PROBE, SeekAccuracy.Exact)
          awaitOrFail("the probe seek to land") {
            events.filterIsInstance<PlaybackEvent.SeekCompleted>().isNotEmpty()
          }
          // The seek resolves when media3 accepts it, and the frame reaches the display after that.
          delay(SETTLE)

          captured = activity.surfaceView.copyPixels()
        } finally {
          collector.cancel()
          player.close()
        }
      }
      assertNotNull(captured, "nothing was copied off the surface")
    }

  private suspend fun SurfaceView.copyPixels(): TestFrame {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val status =
      suspendCancellableCoroutine { continuation ->
        PixelCopy.request(this, bitmap, { result -> continuation.resume(result) }, Handler(Looper.getMainLooper()))
      }
    if (status != PixelCopy.SUCCESS) fail("PixelCopy off the surface answered $status")
    return bitmap.toTestFrame()
  }

  private fun Bitmap.toTestFrame(): TestFrame {
    val colors = IntArray(width * height)
    getPixels(colors, 0, width, 0, 0, width, height)

    val pixels = ByteArray(colors.size * CHANNELS)
    for (index in colors.indices) {
      val color = colors[index]
      val base = index * CHANNELS
      pixels[base] = (color shr RED_SHIFT).toByte()
      pixels[base + 1] = (color shr GREEN_SHIFT).toByte()
      pixels[base + 2] = color.toByte()
      pixels[base + 3] = OPAQUE
    }
    return TestFrame(pixels, Size(width, height))
  }

  private suspend fun SurfaceHostActivity.takeShape(
    shape: Size,
    description: String,
  ) {
    resizeSurface(shape.width, shape.height)
    awaitOrFail("the surface to take $description") {
      surfaceView.width == shape.width && surfaceView.height == shape.height
    }
  }

  private suspend fun realTime(body: suspend () -> Unit) = withContext(Dispatchers.Default) { body() }

  private suspend fun VideoPlayer.awaitReady() =
    awaitOrFail("the preview to become ready, it sat on ${state.value.status}") {
      state.value.status == PlaybackStatus.Ready
    }

  private suspend fun awaitOrFail(
    description: String,
    condition: () -> Boolean,
  ) {
    val met =
      withTimeoutOrNull(BUDGET) {
        while (!condition()) delay(POLL)
        true
      }
    if (met != true) fail("Timed out after $BUDGET waiting for $description.")
  }

  private companion object {
    val TIMEOUT: Duration = 5.minutes
    val BUDGET: Duration = 30.seconds
    val POLL: Duration = 50.milliseconds
    val SETTLE: Duration = 700.milliseconds
    val PROBE: Duration = PROBE_POSITIONS.first()

    const val QUARTER = 90
    const val THREE_QUARTERS = 270
    const val DIM = 0.4f
    const val BRIGHT = 1.4f
    val RED = 0xFFFF0000.toInt()
    val BLUE = 0xFF0000FF.toInt()

    // A 16:9 frame and the 9:16 one a quarter turn of it asks for, both inside the phone's window.
    val LANDSCAPE = Size(960, 540)
    val PORTRAIT = Size(540, 960)

    const val CHANNELS = 4
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val OPAQUE = 0xFF.toByte()

    // Two runs of the same graph on the same display, so anything but decoder noise is a real
    // difference. Well below the rebuild-versus-swap gap this exists to catch.
    const val MIN_PSNR_DB = 35.0
    const val MIN_SSIM = 0.97
  }
}
