package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.assertRasterReads
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds a HEVC image sequence ImageIO writes to one answer however often it is read.
 *
 * Read over and over in both orders among every still the determinism case reads, each representative HEIC handed over
 * as written and a JPEG ImageIO writes, the HEICS reads the same samples every time. The iOS simulator's ImageIO writes
 * no HEICS, which ImageIoSimulatorTest pins.
 */
class ImageSequenceReadTest {
  @Test
  fun `the raster reads a heics the same way every time`() {
    assertRasterReads()
    val misses = orderMisses(orderedReads(listOf(HEICS, JPEG)))

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private companion object {
    const val HEICS = "public.heics"
    const val JPEG = "public.jpeg"
  }
}
