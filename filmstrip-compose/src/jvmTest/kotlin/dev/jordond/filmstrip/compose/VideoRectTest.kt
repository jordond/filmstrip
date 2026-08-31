package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * The rectangle every surface lays its video out in, which each platform then applies its own way.
 *
 * Asserted against the aspect the geometry type derives rather than against numbers typed here, so
 * a surface that started sizing itself from the source frame instead of the output one shows up.
 */
class VideoRectTest {
  @Test
  fun fitLeavesBarsOnTheAxisWithRoomToSpare() {
    val wide = videoRect(OUTPUT, ComposeSize(1000f, 1000f), VideoContentScale.Fit)

    wide.width shouldBe 1000f
    wide.height shouldBe 1000f / OUTPUT.aspect
  }

  @Test
  fun fitMatchesTheHeightWhenTheSpaceIsWiderThanTheVideo() {
    val tall = videoRect(OUTPUT, ComposeSize(4000f, 500f), VideoContentScale.Fit)

    tall.height shouldBe 500f
    tall.width shouldBe 500f * OUTPUT.aspect
  }

  @Test
  fun cropCoversBothAxesAndOverflowsOne() {
    val square = videoRect(OUTPUT, ComposeSize(1000f, 1000f), VideoContentScale.Crop)

    square.height shouldBe 1000f
    square.width shouldBe 1000f * OUTPUT.aspect
  }

  @Test
  fun cropOverflowsTheOtherAxisWhenTheSpaceIsTheWideOne() {
    val wide = videoRect(OUTPUT, ComposeSize(4000f, 500f), VideoContentScale.Crop)

    wide.width shouldBe 4000f
    wide.height shouldBe 4000f / OUTPUT.aspect
  }

  @Test
  fun stretchTakesEverythingItIsGiven() {
    val available = ComposeSize(300f, 900f)

    videoRect(OUTPUT, available, VideoContentScale.Stretch) shouldBe available
  }

  /**
   * The aspect is preserved away from the ends of the range, where a fit and a crop agree.
   */
  @Test
  fun bothAspectPreservingScalesKeepTheOutputAspect() {
    val available = ComposeSize(731f, 419f)

    listOf(VideoContentScale.Fit, VideoContentScale.Crop).forEach { scale ->
      val rect = videoRect(OUTPUT, available, scale)
      (rect.width / rect.height).toDouble().shouldBeWithinPercentageOf(OUTPUT.aspect.toDouble(), TOLERANCE)
    }
  }

  @Test
  fun aSpaceWithNoAreaGetsNoRectangle() {
    videoRect(OUTPUT, ComposeSize(0f, 500f), VideoContentScale.Fit) shouldBe ComposeSize.Zero
    videoRect(OUTPUT, ComposeSize(500f, 0f), VideoContentScale.Fit) shouldBe ComposeSize.Zero
  }

  /**
   * An engine that has reported nothing yet, which is every player before its first load.
   */
  @Test
  fun anUnreportedOutputSizeFillsTheSpace() {
    val available = ComposeSize(300f, 900f)

    videoRect(Size(0, 0), available, VideoContentScale.Fit) shouldBe available
    videoRect(Size(0, 0), available, VideoContentScale.Crop) shouldBe available
  }

  /**
   * A host that bounds the surface on one side only, such as a row that scrolls. The unbounded
   * side has no far edge for a crop to overflow into, so it falls back to the same rectangle a fit
   * would use rather than a rectangle sized to that infinite axis.
   */
  @Test
  fun cropAgreesWithFitWhenHeightIsUnbounded() {
    val available = ComposeSize(1000f, Float.POSITIVE_INFINITY)

    val fit = videoRect(OUTPUT, available, VideoContentScale.Fit)
    val crop = videoRect(OUTPUT, available, VideoContentScale.Crop)

    fit shouldBe ComposeSize(1000f, 1000f / OUTPUT.aspect)
    crop shouldBe fit
  }

  @Test
  fun cropAgreesWithFitWhenWidthIsUnbounded() {
    val available = ComposeSize(Float.POSITIVE_INFINITY, 500f)

    val fit = videoRect(OUTPUT, available, VideoContentScale.Fit)
    val crop = videoRect(OUTPUT, available, VideoContentScale.Crop)

    fit shouldBe ComposeSize(500f * OUTPUT.aspect, 500f)
    crop shouldBe fit
  }

  private companion object {
    // 16:9 rather than square, so a rectangle that swapped its axes is not the same rectangle.
    val OUTPUT = Size(1920, 1080)

    const val TOLERANCE = 0.01
  }
}
