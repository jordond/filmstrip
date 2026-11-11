package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A preview of the long fixture clip drawing onto a real surface, with what it reported and a way to read the
 * surface back.
 *
 * @param player The preview, loaded, paused and drawn once.
 * @param view The surface it draws onto.
 * @param events Every event the preview has raised, in order.
 */
internal class ShuttleSurface(
  val player: VideoPlayer,
  private val view: SurfaceView,
  val events: List<PlaybackEvent>,
) {
  private val bitmap = Bitmap.createBitmap(PATCH_WIDTH, PATCH_HEIGHT, Bitmap.Config.ARGB_8888)
  private val pixels = IntArray(PATCH_WIDTH * PATCH_HEIGHT)
  private val copier = HandlerThread("shuttle-copier").apply { start() }
  private val copies = Handler(copier.looper)

  val seeksCompleted: Int get() = events.count { it is PlaybackEvent.SeekCompleted }

  /**
   * A hash of what the surface shows now, scaled down to a patch small enough to copy many times a second.
   */
  suspend fun picture(): Int {
    // The compositor times a copy out while the surface is taking a buffer, which an emulator's can be for a while.
    var status = copy()
    awaitOrFail("a copy off the surface to stop timing out") {
      status != PixelCopy.ERROR_TIMEOUT || copy().also { status = it } != PixelCopy.ERROR_TIMEOUT
    }
    if (status != PixelCopy.SUCCESS) fail("PixelCopy off the surface answered $status")
    bitmap.getPixels(pixels, 0, PATCH_WIDTH, 0, 0, PATCH_WIDTH, PATCH_HEIGHT)
    return pixels.contentHashCode()
  }

  /**
   * Waits for the compositor to hold a picture of the surface.
   *
   * The player reports its first frame once the frame is queued to the surface, a moment before the compositor
   * takes it, and a copy asked for in between answers that the surface has no data.
   */
  suspend fun awaitPicture() {
    awaitOrFail("the compositor to hold a picture of the surface") { copy() == PixelCopy.SUCCESS }
  }

  /**
   * The picture the surface shows once it has moved off [from], or the one it still shows when [within] runs out.
   */
  suspend fun pictureAfter(
    from: Int,
    within: Duration,
  ): Int {
    var shown = picture()
    withTimeoutOrNull(within) {
      while (shown == from) {
        delay(POLL)
        shown = picture()
      }
    }
    return shown
  }

  private suspend fun copy(): Int =
    suspendCancellableCoroutine { continuation ->
      PixelCopy.request(view, bitmap, { continuation.resume(it) }, copies)
    }

  /**
   * Counts the pictures the surface changes to while [body] runs.
   */
  suspend fun picturesDuring(body: suspend () -> Unit): Int {
    var shown = 0
    withContext(Dispatchers.Default) {
      val sampler =
        launch {
          var last = picture()
          while (true) {
            val next = picture()
            if (next != last) shown++
            last = next
          }
        }
      body()
      sampler.cancel()
    }
    return shown
  }

  suspend fun seekAndAwait(position: Duration) {
    val before = seeksCompleted
    player.seekTo(position, SeekAccuracy.Exact)
    awaitOrFail("the seek to $position to complete") { seeksCompleted > before }
  }

  fun close() {
    copier.quitSafely()
  }

  private companion object {
    const val PATCH_WIDTH = 160
    const val PATCH_HEIGHT = 90
  }
}

/**
 * Runs [body] against a preview of the long fixture clip under [effects], paused on a surface that has drawn.
 */
internal suspend fun CoroutineScope.withShuttleSurface(
  effects: List<EffectSpec> = emptyList(),
  configure: FilmstripBuilder.() -> Unit = {},
  body: suspend (ShuttleSurface) -> Unit,
) {
  ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
    var host: SurfaceHostActivity? = null
    scenario.onActivity { activity -> host = activity }
    val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
    withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }

    val composition =
      Composition(tracks = listOf(Track(listOf(Clip(androidFixture(LONG_CLIP))))), effects = effects)
    val player =
      Filmstrip(contractContext()) {
        playerBackend()
        configure()
      }.preview(composition)
    val events = CopyOnWriteArrayList<PlaybackEvent>()
    val collector = launch { player.events.collect { events += it } }
    val surface = ShuttleSurface(player, activity.surfaceView, events)
    try {
      player.attachPreviewSurface(activity.surfaceView)
      awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
      awaitOrFail("the surface to draw") { events.any { it is PlaybackEvent.FirstFrameRendered } }
      player.pause()
      surface.awaitPicture()
      body(surface)
    } finally {
      surface.close()
      collector.cancel()
      player.close()
    }
  }
}

internal suspend fun awaitOrFail(
  description: String,
  budget: Duration = 30.seconds,
  condition: suspend () -> Boolean,
) {
  val met =
    withTimeoutOrNull(budget) {
      while (!condition()) delay(POLL)
      true
    }
  if (met != true) fail("Timed out after $budget waiting for $description.")
}

/**
 * Whether the tests run on an emulator, which draws through a software renderer and composites late.
 */
internal val runsOnEmulator: Boolean
  get() =
    Build.HARDWARE in EMULATOR_HARDWARE || Build.FINGERPRINT.startsWith("generic") || Build.PRODUCT.startsWith("sdk_")

private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")

/**
 * How long the long fixture clip runs.
 */
internal val LONG_CLIP_LENGTH: Duration = 12.seconds

internal const val LONG_CLIP_RATE = 30

private const val LONG_CLIP = "apple_export_long.mp4"
private val POLL: Duration = 10.milliseconds
