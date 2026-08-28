package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.sigmaFor
import dev.jordond.filmstrip.webcodecs.internal.blurPlan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure downscale, sigma and tap radius arithmetic [blurPlan] drives, without a WebGL context
 * behind it.
 *
 * The sigma each test feeds in comes from [sigmaFor], the shared contract every backend's blur
 * agrees to, so a change to that contract fails the pinned numbers below. The downscale, sigma and
 * tap radius [blurPlan] settles on stay literals, since those pin this realisation of the
 * contract, not the contract itself.
 */
class BrowserBlurPlanTest {
  @Test
  fun blurPlanMatchesTheDocumentedSigmaContract() {
    val sigma = Fill.Blurred(radius = 0.04f).sigmaFor(Size(1080, 1920))
    val plan = blurPlan(sigma, width = 1080, height = 1920)

    assertEquals(6, plan.downscale)
    assertEquals(180, plan.smallWidth)
    assertEquals(320, plan.smallHeight)
    assertTrue(abs(plan.sigma - 7.2f) < 0.01f, "sigma was ${plan.sigma}, expected close to 7.2")
    assertEquals(22, plan.tapRadius)
  }

  @Test
  fun blurPlanStaysWellUnderItsTapBudgetAtTheHighestRadius() {
    // radius = 1f is the top of Fill.Blurred's documented range. Downscaling keeps the small
    // sigma at eight regardless of how large the true sigma gets, so the tap radius this settles
    // on never approaches MAX_BLUR_TAP_RADIUS, which stays a real backstop.
    val sigma = Fill.Blurred(radius = 1f).sigmaFor(Size(4320, 7680))
    val plan = blurPlan(sigma, width = 4320, height = 7680)

    assertTrue(plan.tapRadius <= 24, "the tap radius was ${plan.tapRadius}, expected at most 24")
  }
}
