package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.assertRasterReads
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Holds a HEVC image sequence ImageIO writes to one answer however often it is read.
 *
 * Read over and over in both orders among every still the determinism case reads, each representative HEIC handed over
 * as written and a JPEG ImageIO writes, the HEICS reads the same samples every time. The iOS simulator's ImageIO writes
 * no HEICS, which ImageIoSimulatorHardwareTest pins.
 */
class ImageSequenceReadHardwareTest {
  @Test
  fun `the raster reads a heics the same way every time`() {
    assertRasterReads()
    val misses = orderMisses(orderedReads(listOf(HEICS, JPEG)))

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  private companion object {
    const val HEICS = "public.heics"
    const val JPEG = "public.jpeg"
  }
}
