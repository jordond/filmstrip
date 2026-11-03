package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalTestApi::class, ExperimentalForeignApi::class)
class VideoSurfaceIosTest {
  /**
   * The test host draws into an offscreen scene with no UIKit container, so a surface that reached for its interop
   * view here would fail to compose at all.
   */
  @Test
  fun aPlayerWithNoAppleBackendLaysOutAnEmptyBoxOfItsSize() =
    runComposeUiTest {
      val filmstrip = filmstripWith(RecordingEngine())
      setContent {
        val player = rememberFilmstripPlayer(filmstrip, testComposition("surface.mp4"))
        VideoSurface(player, Modifier.size(BOX_DP.dp).testTag(SURFACE))
      }

      onNodeWithTag(SURFACE).assertWidthIsEqualTo(BOX_DP.dp).assertHeightIsEqualTo(BOX_DP.dp)
    }

  /**
   * A real window, since only a Compose view controller gives a `UIKitView` somewhere to live.
   */
  @Test
  fun theLayerTakesThePlayerAndTheGravityOfTheScaleAndLetsGoOnRelease() {
    val native = AVPlayer()
    val player = filmstripWith(RecordingEngine(nativePlayer = native)).preview(testComposition("surface.mp4"))
    var contentScale by mutableStateOf(VideoContentScale.Fit)
    var shown by mutableStateOf(true)
    val window = UIWindow(frame = CGRectMake(0.0, 0.0, BOX_DP.toDouble(), BOX_DP.toDouble()))
    window.rootViewController =
      ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        if (shown) VideoSurface(player, Modifier.fillMaxSize(), contentScale)
      }
    window.makeKeyAndVisible()

    try {
      val layer = awaitValue("the player layer to be laid out") { window.playerLayer() }
      // NSObject equality, since each read of the property hands back a wrapper of its own around the same player.
      layer.player shouldBe native
      layer.videoGravity shouldBe AVLayerVideoGravityResizeAspect

      contentScale = VideoContentScale.Crop
      awaitValue("the crop gravity") { layer.videoGravity.takeIf { it == AVLayerVideoGravityResizeAspectFill } }

      contentScale = VideoContentScale.Stretch
      awaitValue("the stretch gravity") { layer.videoGravity.takeIf { it == AVLayerVideoGravityResize } }

      shown = false
      awaitValue("the layer to let go of the player") { true.takeIf { layer.player == null } }
    } finally {
      window.hidden = true
      window.rootViewController = null
      player.close()
    }
  }

  private fun UIView.playerLayer(): AVPlayerLayer? =
    (layer as? AVPlayerLayer) ?: subviews.firstNotNullOfOrNull { (it as UIView).playerLayer() }

  /**
   * Turns the main run loop until [read] gives a value, which is what lets Compose recompose and UIKit lay out.
   */
  private fun <T : Any> awaitValue(
    description: String,
    read: () -> T?,
  ): T {
    val deadline = TimeSource.Monotonic.markNow() + BUDGET
    while (deadline.hasNotPassedNow()) {
      read()?.let { return it }
      NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(TICK_SECONDS))
    }
    return read() ?: fail("Timed out after $BUDGET waiting for $description.")
  }

  private companion object {
    const val SURFACE = "surface"
    const val BOX_DP = 300
    const val TICK_SECONDS = 0.05
    val BUDGET = 10.seconds
  }
}
