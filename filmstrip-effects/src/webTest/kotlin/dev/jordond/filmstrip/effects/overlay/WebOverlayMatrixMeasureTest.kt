package dev.jordond.filmstrip.effects.overlay

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What this browser's Chrome does to a limited range BT.709 or BT.2020 AVIF overlay, read the way
 * [WebOverlayMatrixTest] reads it.
 *
 * Chrome turns those samples into RGB with coefficients coarser than ITU-T H.273's, so its decode sits further from
 * core's reading than its rounding alone puts it. This fails once a Chrome build decodes them within
 * [WebOverlayMatrixTest.CHROME_ROUNDING], which says Chrome changed and its drift can come down.
 */
class WebOverlayMatrixMeasureTest {
  @Test
  fun chromeDecodesLimitedRangeBt709AndBt2020PastItsRounding() =
    runTest {
      val worst = WebOverlayMatrixTest().limitedRangeDrift()

      assertTrue(worst > WebOverlayMatrixTest.CHROME_ROUNDING, "Chrome's limited range drift fell to $worst")
    }
}
