package dev.jordond.filmstrip.compose

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.SimpleBasePlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.player.playerBackend
import dev.jordond.filmstrip.test.writeAtomically
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * The Android surface against a real window, where a `SurfaceView` is laid out and handed to media3.
 *
 * Every box here is sized in pixels rather than dp, so the view a test reads back is in the same unit as the rectangle
 * it is checked against.
 */
@OptIn(ExperimentalTestApi::class)
class VideoSurfaceAndroidTest {
  @Test
  fun aPlayerWithNoMedia3BackendLaysOutAnEmptyBoxOfItsSize() =
    runAndroidComposeUiTest<ComponentActivity> {
      val filmstrip = filmstripWith(RecordingEngine())
      setContent {
        val player = rememberFilmstripPlayer(filmstrip, testComposition("surface.mp4"))
        VideoSurface(player, Modifier.pixelSize(BOX).testTag(SURFACE))
      }
      waitForIdle()

      onNodeWithTag(SURFACE).fetchSemanticsNode().size shouldBe BOX
      surfaceViews().shouldBeEmpty()
    }

  @Test
  fun fitSizesTheViewToTheLetterboxAndCentresIt() =
    runAndroidComposeUiTest<ComponentActivity> {
      showFakeSurface(VideoContentScale.Fit)

      val view = surfaceViews().single()
      view.size() shouldBe expectedSize(VideoContentScale.Fit)
      offsetFromBox(view).shouldBeCentred(expectedSize(VideoContentScale.Fit))
    }

  @Test
  fun cropOverflowsTheBoxOnOneAxisAndCentresTheOverflow() =
    runAndroidComposeUiTest<ComponentActivity> {
      showFakeSurface(VideoContentScale.Crop)

      val view = surfaceViews().single()
      val expected = expectedSize(VideoContentScale.Crop)
      view.size() shouldBe expected
      offsetFromBox(view).shouldBeCentred(expected)
      // The overflow is clipped, so the surface reports no more than the box upwards.
      onNodeWithTag(SURFACE).fetchSemanticsNode().size shouldBe BOX
    }

  @Test
  fun stretchHandsTheViewTheWholeBox() =
    runAndroidComposeUiTest<ComponentActivity> {
      showFakeSurface(VideoContentScale.Stretch)

      surfaceViews().single().size() shouldBe BOX
    }

  /**
   * A row that scrolls sideways leaves the width unbounded, so the fit comes off the height alone and a crop takes
   * that same rectangle.
   */
  @Test
  fun anUnboundedWidthSizesTheViewFromTheHeightAlone() =
    runAndroidComposeUiTest<ComponentActivity> {
      val engine = RecordingEngine(nativePlayer = IdlePlayer())
      // A filmstrip built inside the content would open a new player each time the scale recomposes it.
      val filmstrip = filmstripWith(engine)
      var contentScale by mutableStateOf(VideoContentScale.Fit)
      setContent {
        val player = rememberFilmstripPlayer(filmstrip, testComposition("surface.mp4"))
        Row(Modifier.pixelHeight(BOX.height).horizontalScroll(rememberScrollState())) {
          VideoSurface(player, Modifier.testTag(SURFACE), contentScale = contentScale)
        }
      }
      engine.reportPreviewInfo(previewInfo(OUTPUT))
      waitForIdle()

      val available = ComposeSize(Float.POSITIVE_INFINITY, BOX.height.toFloat())
      val fit = videoRect(OUTPUT, available, VideoContentScale.Fit).rounded()
      surfaceViews().single().size() shouldBe fit

      contentScale = VideoContentScale.Crop
      waitForIdle()
      surfaceViews().single().size() shouldBe fit
    }

  /**
   * The first frame media3 draws into the view is what lifts the shutter, and a surface that leaves composition and
   * comes back has to be attached again for the next one to arrive.
   */
  @Test
  fun aMedia3PlayerRendersIntoTheSurfaceAndAgainOnceItReturns() =
    runAndroidComposeUiTest<ComponentActivity> {
      val filmstrip = Filmstrip(context) { playerBackend() }
      val composition = fixtureComposition()
      val firstFrames = mutableListOf<PlaybackEvent>()
      var shown by mutableStateOf(true)
      lateinit var player: VideoPlayer
      lateinit var state: VideoSurfaceState
      setContent {
        player = rememberFilmstripPlayer(filmstrip, composition)
        state = rememberVideoSurfaceState(player)
        PlaybackEventEffect(player) { event -> if (event is PlaybackEvent.FirstFrameRendered) firstFrames += event }
        if (shown) VideoSurface(player, Modifier.pixelSize(BOX))
      }
      waitUntil("the preview to become ready", BUDGET_MILLIS) { player.state.value.status == PlaybackStatus.Ready }
      runOnUiThread { player.play() }
      waitUntil("a first frame to lift the shutter", BUDGET_MILLIS) { !state.coverSurface }

      state.outputSize shouldBe FIXTURE_FRAME
      surfaceViews().single().size() shouldBe videoRect(FIXTURE_FRAME, BOX.toCompose(), VideoContentScale.Fit).rounded()
      firstFrames shouldHaveSize 1

      runOnUiThread { player.pause() }
      shown = false
      waitForIdle()
      surfaceViews().shouldBeEmpty()

      shown = true
      runOnUiThread { player.play() }
      waitUntil("the returned surface to render", BUDGET_MILLIS) { firstFrames.size == 2 }
      surfaceViews() shouldHaveSize 1
    }

  private fun AndroidComposeUiTest<ComponentActivity>.showFakeSurface(contentScale: VideoContentScale) {
    val engine = RecordingEngine(nativePlayer = IdlePlayer())
    val filmstrip = filmstripWith(engine)
    setContent {
      val player = rememberFilmstripPlayer(filmstrip, testComposition("surface.mp4"))
      VideoSurface(player, Modifier.pixelSize(BOX).testTag(SURFACE), contentScale)
    }
    engine.reportPreviewInfo(previewInfo(OUTPUT))
    waitForIdle()
  }

  private fun AndroidComposeUiTest<ComponentActivity>.surfaceViews(): List<SurfaceView> {
    val root = activity?.window?.decorView ?: fail("the host activity never started")
    return root.descendants().filterIsInstance<SurfaceView>().toList()
  }

  private fun AndroidComposeUiTest<ComponentActivity>.offsetFromBox(view: SurfaceView): IntSize {
    val box = onNodeWithTag(SURFACE).fetchSemanticsNode().boundsInWindow
    val location = IntArray(2).also { view.getLocationInWindow(it) }
    return IntSize(location[0] - box.left.roundToInt(), location[1] - box.top.roundToInt())
  }

  private fun View.descendants(): Sequence<View> =
    sequence {
      yield(this@descendants)
      if (this@descendants is ViewGroup) {
        for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
      }
    }

  private fun View.size(): IntSize = IntSize(width, height)

  private fun expectedSize(contentScale: VideoContentScale): IntSize =
    videoRect(OUTPUT, BOX.toCompose(), contentScale).rounded()

  // Within half a pixel of the exact centre, since a centred odd remainder lands on one side or the other.
  private fun IntSize.shouldBeCentred(size: IntSize) {
    width.toFloat() shouldBe ((BOX.width - size.width) / 2f plusOrMinus HALF_PIXEL)
    height.toFloat() shouldBe ((BOX.height - size.height) / 2f plusOrMinus HALF_PIXEL)
  }

  private fun ComposeSize.rounded(): IntSize = IntSize(width.roundToInt(), height.roundToInt())

  private fun IntSize.toCompose(): ComposeSize = ComposeSize(width.toFloat(), height.toFloat())

  private fun Modifier.pixelSize(size: IntSize): Modifier =
    layout { measurable, _ ->
      val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
      layout(size.width, size.height) { placeable.place(0, 0) }
    }

  private fun Modifier.pixelHeight(height: Int): Modifier =
    layout { measurable, constraints ->
      val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
      layout(placeable.width, height) { placeable.place(0, 0) }
    }

  private fun fixtureComposition(): Composition {
    val file = File(context.cacheDir, FIXTURE)
    if (!file.exists()) {
      val loader = javaClass.classLoader ?: fail("the test APK has no class loader")
      val stream = loader.getResourceAsStream(FIXTURE) ?: fail("$FIXTURE was not packaged into the test APK")
      stream.use { input -> file.writeAtomically { input.copyTo(it) } }
    }
    val clip = Clip(MediaSource.of(file.path), TimeRange.of(Duration.ZERO, FIXTURE_LENGTH))
    return Composition(listOf(Track(listOf(clip))))
  }

  /**
   * A media3 player that no filmstrip engine built, so the surface lays out a view for it and has nothing to attach.
   */
  private class IdlePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State = State.Builder().build()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
  }

  private companion object {
    const val SURFACE = "surface"
    const val FIXTURE = "android_export_a.mp4"
    const val BUDGET_MILLIS = 30_000L
    const val HALF_PIXEL = 0.5f

    // Square, so the 16:9 output letterboxes under a fit and overflows sideways under a crop.
    val BOX = IntSize(300, 300)
    val OUTPUT = Size(1920, 1080)
    val FIXTURE_FRAME = Size(640, 360)
    val FIXTURE_LENGTH: Duration = 1500.milliseconds

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  }
}
