package dev.jordond.filmstrip.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins a mid signal of every [CicpOetf] curve to the light ITU-T H.273 Table 3 gives it, and checks each curve that
 * [cicpCurve] holds as well reads the same there.
 */
class CicpOetfTest {
  @Test
  fun `a mid signal on every curve decodes to the light Table 3 gives`() {
    for ((transfer, light) in MID_LIGHT) {
      val curve = cicpOetf(transfer)
      abs(curve.decode(0.5) - light) shouldBeAtMost 1e-12
      abs(curve.encode(light) - 0.5) shouldBeAtMost 1e-12
    }
  }

  @Test
  fun `curves held by both tables agree across their range`() {
    for ((transfer, tolerance) in listOf(4 to 1e-12, 5 to 1e-12, 7 to 1e-12, 8 to 1e-12, 13 to 1e-6, 17 to 1e-12)) {
      val table = assertNotNull(cicpCurve(transfer))
      val curve = cicpOetf(transfer)
      for (step in 0..45) {
        val signal = step / 50.0
        abs(table.evaluate(signal) - curve.decode(signal)) shouldBeAtMost tolerance
      }
    }
  }

  @Test
  fun `no curve is made for PQ HLG or a code point another value stands for`() {
    for (transfer in listOf(16, 18, 6, 14, 2)) {
      assertFailsWith<IllegalArgumentException>("$transfer") { CicpOetf(transfer) }
    }
    assertFailsWith<IllegalArgumentException> { cicpOetf(16) }
    assertFailsWith<IllegalArgumentException> { cicpOetf(18) }
  }

  private infix fun Double.shouldBeAtMost(limit: Double) = assertTrue(this <= limit, "$this is past $limit")

  private companion object {
    // The light a signal of 0.5 decodes to under each curve, worked from the Table 3 formulas with their constants.
    val MID_LIGHT =
      mapOf(
        1 to 0.2597194371011775,
        4 to 0.217637640824031,
        5 to 0.1435872943746294,
        7 to 0.2650357335786772,
        8 to 0.5,
        9 to 0.1,
        10 to 0.05623413251903491,
        13 to 0.21404114048223255,
        17 to 0.1799547637686887,
      )
  }
}
