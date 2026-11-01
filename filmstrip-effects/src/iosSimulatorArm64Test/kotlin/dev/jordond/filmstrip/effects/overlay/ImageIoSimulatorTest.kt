package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.test.AppleDisplayP3
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The iOS simulator's own ImageIO answers. It writes no HEVC image sequence, so ImageSequenceReadTest is left out of
 * the simulator's run.
 *
 * This fails once the simulator writes a HEICS, which says that case can run here too.
 */
class ImageIoSimulatorTest {
  @Test
  fun `imageio writes no heics on the simulator`() {
    assertNull(AppleDisplayP3.picture(HEICS, SIZE, TEXEL), "the simulator now writes $HEICS")
  }

  private companion object {
    const val HEICS = "public.heics"
  }
}
